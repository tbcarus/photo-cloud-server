package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.dao.DataIntegrityViolationException;
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
import ru.tbcarus.photocloudserver.service.storage.*;
import ru.tbcarus.photocloudserver.util.FileUtils;
import ru.tbcarus.photocloudserver.util.FileUtils.StreamedFileInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
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
    private final FileContentDetector fileContentDetector;
    private final StorageProperties storageProperties;
    private final FileMetadataExtractor fileMetadataExtractor;
    private final PlatformTransactionManager transactionManager;

    public FileItemDto uploadFile(MultipartFile file, User user) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой");
        }

        Path tempFile = null;
        Path finalFile = null;
        boolean movedToFinal = false;

        try {
            // Сначала пишем во временный файл: так checksum, размер, MIME и metadata считаются без загрузки всего файла в память.
            tempFile = createTempFile();
            StreamedFileInfo streamedFile = writeToTempFile(file, tempFile);
            String checksum = streamedFile.checksum();
            long size = streamedFile.size();

            String detectedMimeType = fileContentDetector.detectMimeType(tempFile);
            FileType fileType = FileType.fromMimeType(detectedMimeType);
            ExtractedFileMetadata extractedMetadata = fileMetadataExtractor.extract(tempFile, detectedMimeType);
            LocalDateTime uploadedAt = LocalDateTime.now();
            LocalDateTime capturedAt = extractedMetadata.getCapturedAt() == null ? uploadedAt : extractedMetadata.getCapturedAt();
            Folder folder = folderService.getDefaultFolder(user, fileType);
            String originalName = filenameSanitizer.limitOriginalNameWithExtension(file.getOriginalFilename(), 255);

            Optional<StoredObject> existingStoredObject = storedObjectRepository.findByUserIdAndChecksum(user.getId(), checksum);
            if (existingStoredObject.isPresent()) {
                // При дедупликации физический объект уже есть, поэтому временный файл больше не нужен.
                deleteIfExists(tempFile);
                tempFile = null;
                FileItem saved = saveFileItem(user, folder, existingStoredObject.get(), originalName, capturedAt, uploadedAt, extractedMetadata);
                return fileItemMapper.toDto(saved);
            }

            String filePath = storageKeyGenerator.generateFilePath(user.getId(), checksum);
            String filename = storageKeyGenerator.generateFilename(originalName, detectedMimeType);
            String fileExtension = filenameSanitizer.extension(originalName, detectedMimeType);
            finalFile = storagePathResolver.resolve(filePath, filename);

            // Move делаем до БД: если файловая операция не удалась, в базе не появится ссылка на отсутствующий файл.
            moveTempToFinal(tempFile, finalFile);
            movedToFinal = true;
            tempFile = null;

            try {
                FileItem saved = createStoredObjectAndFileItem(
                        user,
                        folder,
                        originalName,
                        capturedAt,
                        uploadedAt,
                        extractedMetadata,
                        filePath,
                        filename,
                        fileExtension,
                        checksum,
                        size,
                        detectedMimeType,
                        fileType
                );
                return fileItemMapper.toDto(saved);
            } catch (DataIntegrityViolationException ex) {
                // Параллельные загрузки одного файла могут одновременно не найти StoredObject.
                // Unique (user_id, checksum) оставляет один объект в БД, а файл текущего запроса нужно удалить.
                deleteIfExists(finalFile);
                StoredObject racedStoredObject = storedObjectRepository.findByUserIdAndChecksum(user.getId(), checksum)
                        .orElseThrow(() -> ex);
                FileItem saved = saveFileItem(user, folder, racedStoredObject, originalName, capturedAt, uploadedAt, extractedMetadata);
                return fileItemMapper.toDto(saved);
            } catch (RuntimeException ex) {
                // Если БД упала после move, удаляем уже перенесённый final-файл, чтобы не оставить orphan на диске.
                deleteIfExists(finalFile);
                throw ex;
            }
        } catch (RuntimeException | IOException ex) {
            // Общий cleanup закрывает ошибки streaming, лимита размера и move; после move final удаляется отдельно.
            if (tempFile != null) {
                deleteIfExists(tempFile);
            }
            if (movedToFinal && finalFile != null) {
                deleteIfExists(finalFile);
            }
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
        StoredObject storedObject = fileItem.getStoredObject();
        Path path = storagePathResolver.resolve(storedObject.getFilePath(), storedObject.getFilename());
        Resource resource = new UrlResource(path.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            throw new FileItemNotFoundException(fileItem.getId());
        }
        return resource;
    }

    public void deleteFileForCurrentUser(Long fileId, User user) {
        FileItem file = getFileForCurrentUser(fileId, user);
        StoredObject storedObject = file.getStoredObject();
        Path path = storagePathResolver.resolve(storedObject.getFilePath(), storedObject.getFilename());

        if (!file.getUser().getId().equals(storedObject.getUser().getId())) {
            fileItemRepository.delete(file);
            return;
        }

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            // Владелец физического объекта удаляет все логические записи, которые на него ссылаются.
            fileItemRepository.deleteAll(fileItemRepository.findAllByStoredObjectId(storedObject.getId()));
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

    private Path createTempFile() throws IOException {
        Path tempDir = Path.of(storageProperties.getTempDir()).toAbsolutePath().normalize();
        Files.createDirectories(tempDir);
        // TODO: temp-dir должен оставаться на той же файловой системе, что и storage.root, чтобы move был дешёвым и по возможности atomic.
        return Files.createTempFile(tempDir, "upload-", ".tmp");
    }

    private StreamedFileInfo writeToTempFile(MultipartFile file, Path tempFile) throws IOException {
        try (InputStream inputStream = file.getInputStream();
             OutputStream outputStream = Files.newOutputStream(tempFile)) {
            return FileUtils.writeAndCalculateSHA256(inputStream, outputStream, storageProperties.getMaxFileSizeBytes());
        }
    }

    private void moveTempToFinal(Path tempFile, Path finalFile) throws IOException {
        Files.createDirectories(finalFile.getParent());
        try {
            Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            // Atomic move доступен не всегда; cleanup вокруг вызова всё равно удалит temp/final при ошибках.
            Files.move(tempFile, finalFile);
        }
    }

    private FileItem createStoredObjectAndFileItem(User user,
                                                   Folder folder,
                                                   String originalName,
                                                   LocalDateTime capturedAt,
                                                   LocalDateTime uploadedAt,
                                                   ExtractedFileMetadata extractedMetadata,
                                                   String filePath,
                                                   String filename,
                                                   String fileExtension,
                                                   String checksum,
                                                   long size,
                                                   String detectedMimeType,
                                                   FileType fileType) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> {
            StoredObject storedObject = storedObjectRepository.save(StoredObject.builder()
                    .user(user)
                    .filePath(filePath)
                    .filename(filename)
                    .fileExtension(fileExtension)
                    .checksum(checksum)
                    .size(size)
                    .detectedMimeType(detectedMimeType)
                    .fileType(fileType)
                    .build());
            return saveFileItem(user, folder, storedObject, originalName, capturedAt, uploadedAt, extractedMetadata);
        });
    }

    private FileItem saveFileItem(User user,
                                  Folder folder,
                                  StoredObject storedObject,
                                  String originalName,
                                  LocalDateTime capturedAt,
                                  LocalDateTime uploadedAt,
                                  ExtractedFileMetadata extractedMetadata) {
        FileItem fileItem = FileItem.builder()
                .user(user)
                .folder(folder)
                .storedObject(storedObject)
                .originalName(originalName)
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

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupEx) {
            log.error("Не удалось удалить файл при cleanup: {}", path, cleanupEx);
        }
    }
}
