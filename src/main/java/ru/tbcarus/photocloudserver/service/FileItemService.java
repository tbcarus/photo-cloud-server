package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import ru.tbcarus.photocloudserver.exception.FileConflictException;
import ru.tbcarus.photocloudserver.exception.FileItemNotFoundException;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.FileMetadata;
import ru.tbcarus.photocloudserver.model.FileType;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.StoredObject;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.CopyFileRequest;
import ru.tbcarus.photocloudserver.model.dto.FileChecksumDto;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;
import ru.tbcarus.photocloudserver.model.dto.MoveFileRequest;
import ru.tbcarus.photocloudserver.model.dto.RenameFileRequest;
import ru.tbcarus.photocloudserver.model.dto.mapper.FileItemMapper;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.StoredObjectRepository;
import ru.tbcarus.photocloudserver.service.metadata.ExtractedFileMetadata;
import ru.tbcarus.photocloudserver.service.metadata.FileMetadataExtractor;
import ru.tbcarus.photocloudserver.service.storage.FileContentDetector;
import ru.tbcarus.photocloudserver.service.storage.FilenameSanitizer;
import ru.tbcarus.photocloudserver.service.storage.StorageKeyGenerator;
import ru.tbcarus.photocloudserver.service.storage.StoragePathResolver;
import ru.tbcarus.photocloudserver.service.storage.StorageProperties;
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
        return uploadFile(file, user, null);
    }

    public FileItemDto uploadFile(MultipartFile file, User user, Long folderId) throws IOException {
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
            Folder folder = resolveTargetFolder(user, folderId, fileType);
            String originalName = filenameSanitizer.limitOriginalNameWithExtension(file.getOriginalFilename(), 255);
            // Сначала проверяем логическое имя: dedup не должен создавать FileItem, который нарушает правила папки.
            ensureFileNameAvailable(user.getId(), folder, originalName, null);

            Optional<StoredObject> existingStoredObject = storedObjectRepository.findFirstByUserIdAndChecksumOrderByIdAsc(user.getId(), checksum);
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
                // TODO: После разрешения независимых copy StoredObject больше не уникален по checksum.
                // Для строгой защиты upload от race потребуется отдельный механизм блокировок или ключ дедупликации.
                deleteIfExists(finalFile);
                StoredObject racedStoredObject = storedObjectRepository.findFirstByUserIdAndChecksumOrderByIdAsc(user.getId(), checksum)
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

    public Page<FileItemDto> getUserFiles(Pageable pageable, User user) {
        return fileItemRepository.findAllByUserId(user.getId(), pageable).map(fileItemMapper::toDto);
    }

    public Page<FileItemDto> getUserFiles(Pageable pageable, User user, Long folderId) {
        if (folderId == null) {
            return getUserFiles(pageable, user);
        }
        folderService.getFolderForUser(folderId, user);
        return fileItemRepository.findAllByUserIdAndFolderId(user.getId(), folderId, pageable).map(fileItemMapper::toDto);
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

    public FileItemDto renameFileForCurrentUser(Long fileId, RenameFileRequest request, User user) {
        FileItem file = getFileForCurrentUser(fileId, user);
        String originalName = normalizeOriginalName(request.originalName());
        // Переименование меняет только логическую запись: физическое имя стабильно и принадлежит object storage.
        ensureFileNameAvailable(user.getId(), file.getFolder(), originalName, file.getId());
        file.setOriginalName(originalName);
        return fileItemMapper.toDto(fileItemRepository.save(file));
    }

    public FileItemDto moveFileForCurrentUser(Long fileId, MoveFileRequest request, User user) {
        FileItem file = getFileForCurrentUser(fileId, user);
        Folder targetFolder = folderService.getFolderForUser(request.targetFolderId(), user);
        // Физический файл не перемещаем: дерево папок живёт в БД, а storage работает как object storage.
        ensureFileNameAvailable(user.getId(), targetFolder, file.getOriginalName(), file.getId());
        file.setFolder(targetFolder);
        return fileItemMapper.toDto(fileItemRepository.save(file));
    }

    public FileItemDto copyFileForCurrentUser(Long fileId, CopyFileRequest request, User user) throws IOException {
        FileItem sourceFile = getFileForCurrentUser(fileId, user);
        StoredObject sourceStoredObject = sourceFile.getStoredObject();
        Folder targetFolder = request.targetFolderId() == null
                ? sourceFile.getFolder()
                : folderService.getFolderForUser(request.targetFolderId(), user);
        String originalName = request.originalName() == null || request.originalName().isBlank()
                ? sourceFile.getOriginalName()
                : normalizeOriginalName(request.originalName());

        ensureFileNameAvailable(user.getId(), targetFolder, originalName, null);

        Path sourcePath = storagePathResolver.resolve(sourceStoredObject.getFilePath(), sourceStoredObject.getFilename());
        String filePath = storageKeyGenerator.generateFilePath(user.getId(), sourceStoredObject.getChecksum());
        String filename = storageKeyGenerator.generateFilename(originalName, sourceStoredObject.getDetectedMimeType());
        Path copiedPath = storagePathResolver.resolve(filePath, filename);
        boolean copied = false;

        try {
            Files.createDirectories(copiedPath.getParent());
            Files.copy(sourcePath, copiedPath);
            copied = true;

            // Copy намеренно не использует dedup: пользователь получает независимый физический объект.
            FileItem saved = createCopiedStoredObjectAndFileItem(
                    user,
                    targetFolder,
                    sourceFile,
                    sourceStoredObject,
                    originalName,
                    filePath,
                    filename
            );
            return fileItemMapper.toDto(saved);
        } catch (RuntimeException | IOException ex) {
            if (copied) {
                // Если БД упала после физического копирования, удаляем orphan-файл.
                deleteIfExists(copiedPath);
            }
            throw ex;
        }
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
            // TODO: При появлении sharing нужно пересмотреть права доступа и удаление чужих FileItem.
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

    private Folder resolveTargetFolder(User user, Long folderId, FileType fileType) {
        if (folderId == null) {
            return folderService.getDefaultFolder(user, fileType);
        }
        return folderService.getFolderForUser(folderId, user);
    }

    private void ensureFileNameAvailable(Long userId, Folder folder, String originalName, Long currentFileId) {
        if (folder.getFolderType() == FolderType.CAMERA) {
            return;
        }
        boolean exists = currentFileId == null
                ? fileItemRepository.existsByUserIdAndFolderIdAndOriginalNameIgnoreCase(userId, folder.getId(), originalName)
                : fileItemRepository.existsByUserIdAndFolderIdAndOriginalNameIgnoreCaseAndIdNot(userId, folder.getId(), originalName, currentFileId);
        if (exists) {
            // TODO: Позже можно добавить overwrite/replace или auto-rename вида "file (1).jpg".
            // TODO: Строгая DB-уникальность с исключением CAMERA потребует денормализации или trigger.
            throw new FileConflictException("File with this name already exists in folder");
        }
    }

    private String normalizeOriginalName(String originalName) {
        String normalized = originalName == null ? "" : originalName.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("File name must not be blank");
        }
        return filenameSanitizer.limitOriginalNameWithExtension(normalized, 255);
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

    private FileItem createCopiedStoredObjectAndFileItem(User user,
                                                         Folder folder,
                                                         FileItem sourceFile,
                                                         StoredObject sourceStoredObject,
                                                         String originalName,
                                                         String filePath,
                                                         String filename) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> {
            StoredObject copiedStoredObject = storedObjectRepository.save(StoredObject.builder()
                    .user(user)
                    .filePath(filePath)
                    .filename(filename)
                    .fileExtension(sourceStoredObject.getFileExtension())
                    .checksum(sourceStoredObject.getChecksum())
                    .size(sourceStoredObject.getSize())
                    .detectedMimeType(sourceStoredObject.getDetectedMimeType())
                    .fileType(sourceStoredObject.getFileType())
                    .build());
            FileItem copiedFileItem = FileItem.builder()
                    .user(user)
                    .folder(folder)
                    .storedObject(copiedStoredObject)
                    .originalName(originalName)
                    .capturedAt(sourceFile.getCapturedAt())
                    .uploadedAt(LocalDateTime.now())
                    .build();
            copyMetadata(sourceFile, copiedFileItem);
            return fileItemRepository.save(copiedFileItem);
        });
    }

    private void copyMetadata(FileItem sourceFile, FileItem copiedFileItem) {
        if (sourceFile.getMetadata() == null) {
            return;
        }
        FileMetadata sourceMetadata = sourceFile.getMetadata();
        FileMetadata copiedMetadata = FileMetadata.builder()
                .fileItem(copiedFileItem)
                .width(sourceMetadata.getWidth())
                .height(sourceMetadata.getHeight())
                .durationSec(sourceMetadata.getDurationSec())
                .cameraMake(sourceMetadata.getCameraMake())
                .cameraModel(sourceMetadata.getCameraModel())
                .lensModel(sourceMetadata.getLensModel())
                .exposureTime(sourceMetadata.getExposureTime())
                .fNumber(sourceMetadata.getFNumber())
                .iso(sourceMetadata.getIso())
                .focalLength(sourceMetadata.getFocalLength())
                .latitude(sourceMetadata.getLatitude())
                .longitude(sourceMetadata.getLongitude())
                .build();
        copiedFileItem.setMetadata(copiedMetadata);
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
