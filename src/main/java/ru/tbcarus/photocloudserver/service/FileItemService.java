package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import ru.tbcarus.photocloudserver.exception.FileItemNotFoundException;
import ru.tbcarus.photocloudserver.model.*;
import ru.tbcarus.photocloudserver.model.dto.FileChecksumDto;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;
import ru.tbcarus.photocloudserver.model.dto.mapper.FileItemMapper;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.StoredObjectRepository;
import ru.tbcarus.photocloudserver.service.metadata.ExtractedFileMetadata;
import ru.tbcarus.photocloudserver.service.metadata.FileMetadataExtractor;
import ru.tbcarus.photocloudserver.service.storage.FilenameSanitizer;
import ru.tbcarus.photocloudserver.service.storage.StorageKeyGenerator;
import ru.tbcarus.photocloudserver.service.storage.StoragePathResolver;
import ru.tbcarus.photocloudserver.util.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileItemService {

    private final FileItemRepository fileItemRepository;
    private final StoredObjectRepository storedObjectRepository;
    private final FolderService folderService;
    private final FileItemMapper fileItemMapper;
    private final StorageKeyGenerator storageKeyGenerator;
    private final StoragePathResolver storagePathResolver;
    private final FilenameSanitizer filenameSanitizer;
    private final FileMetadataExtractor fileMetadataExtractor;
    private final PlatformTransactionManager transactionManager;

    public FileItemDto uploadFile(MultipartFile file, User user) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой");
        }

        String mimeType = file.getContentType();
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("MIME-тип не определён");
        }

        byte[] fileBytes = file.getBytes();
        String checksum = FileUtils.calculateSHA256(fileBytes);
        Optional<FileItem> duplicate = fileItemRepository.findByUserIdAndChecksum(user.getId(), checksum);
        if (duplicate.isPresent()) {
            return fileItemMapper.toDto(duplicate.get());
        }

        LocalDateTime uploadedAt = LocalDateTime.now();
        FileType fileType = FileType.fromMimeType(mimeType);
        ExtractedFileMetadata extractedMetadata = fileMetadataExtractor.extract(fileBytes, mimeType);
        LocalDateTime capturedAt = extractedMetadata.getCapturedAt() == null ? uploadedAt : extractedMetadata.getCapturedAt();
        Folder folder = folderService.getDefaultFolder(user, fileType);
        String originalFilename = truncateOriginalFilename(filenameSanitizer.safeName(file.getOriginalFilename()));
        String storageKey = storageKeyGenerator.generate(user.getId(), checksum, originalFilename, mimeType);
        Path destination = storagePathResolver.resolve(storageKey);

        try {
            Files.createDirectories(destination.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }

            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            FileItem saved = transactionTemplate.execute(status -> saveFileItem(
                    user,
                    folder,
                    originalFilename,
                    mimeType,
                    file.getSize(),
                    checksum,
                    fileType,
                    capturedAt,
                    uploadedAt,
                    storageKey,
                    extractedMetadata
            ));
            return fileItemMapper.toDto(saved);
        } catch (RuntimeException | IOException ex) {
            Files.deleteIfExists(destination);
            throw ex;
        }
    }

    public org.springframework.data.domain.Page<FileItemDto> getUserFiles(org.springframework.data.domain.Pageable pageable, User user) {
        return fileItemRepository.findAllByUserId(user.getId(), pageable).map(fileItemMapper::toDto);
    }

    public FileItem getFileForCurrentUser(Long fileId, User user) {
        return fileItemRepository.findByIdAndUserId(fileId, user.getId())
                .orElseThrow(() -> new FileItemNotFoundException(fileId));
    }

    public FileItemDto getFileDtoForCurrentUser(Long fileId, User user) {
        return fileItemMapper.toDto(getFileForCurrentUser(fileId, user));
    }

    public Resource getDownloadResource(FileItem fileItem) throws IOException {
        Path path = storagePathResolver.resolve(fileItem.getStoredObject().getStorageKey());
        Resource resource = new UrlResource(path.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            throw new FileItemNotFoundException(fileItem.getId());
        }
        return resource;
    }

    public void deleteFileForCurrentUser(Long fileId, User user) {
        FileItem file = getFileForCurrentUser(fileId, user);
        Path path = storagePathResolver.resolve(file.getStoredObject().getStorageKey());
        StoredObject storedObject = file.getStoredObject();

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            fileItemRepository.delete(file);
            fileItemRepository.flush();
            storedObjectRepository.delete(storedObject);
        });

        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.error("Не удалось удалить физический файл {} после удаления записи из БД", path, ex);
        }
    }

    public List<FileChecksumDto> getChecksumsForUser(Long userId) {
        return fileItemRepository.findAllChecksumsAndOriginalFilenamesByUserId(userId);
    }

    private FileItem saveFileItem(User user,
                                  Folder folder,
                                  String originalFilename,
                                  String mimeType,
                                  long size,
                                  String checksum,
                                  FileType fileType,
                                  LocalDateTime capturedAt,
                                  LocalDateTime uploadedAt,
                                  String storageKey,
                                  ExtractedFileMetadata extractedMetadata) {
        StoredObject storedObject = storedObjectRepository.save(StoredObject.builder()
                .user(user)
                .storageKey(storageKey)
                .build());

        FileItem fileItem = FileItem.builder()
                .user(user)
                .folder(folder)
                .storedObject(storedObject)
                .originalFilename(originalFilename)
                .mimeType(mimeType)
                .size(size)
                .checksum(checksum)
                .fileType(fileType)
                .capturedAt(capturedAt)
                .uploadedAt(uploadedAt)
                .build();

        if (extractedMetadata.hasMetadataFields()) {
            FileMetadata metadata = toEntity(extractedMetadata);
            metadata.setFileItem(fileItem);
            fileItem.setMetadata(metadata);
        }

        return fileItemRepository.save(fileItem);
    }

    private FileMetadata toEntity(ExtractedFileMetadata extracted) {
        return FileMetadata.builder()
                .width(extracted.getWidth())
                .height(extracted.getHeight())
                .durationSec(extracted.getDurationSec())
                .cameraMake(extracted.getCameraMake())
                .cameraModel(extracted.getCameraModel())
                .lensModel(extracted.getLensModel())
                .exposureTime(extracted.getExposureTime())
                .fNumber(scale(extracted.getFNumber()))
                .iso(extracted.getIso())
                .focalLength(scale(extracted.getFocalLength()))
                .latitude(scale(extracted.getLatitude()))
                .longitude(scale(extracted.getLongitude()))
                .build();
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private String truncateOriginalFilename(String originalFilename) {
        return originalFilename.length() <= 255 ? originalFilename : originalFilename.substring(0, 255);
    }
}
