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
import ru.tbcarus.photocloudserver.exception.FileAccessDeniedException;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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

        // temp принадлежит операции до публикации; дальше судьбу байтов решают publishExclusively и saveForPublishedFile.
        Path tempFile = null;

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

            // Дубль определяется как user + folder + checksum: если такой файл уже есть в этой папке,
            // upload идемпотентен — не создаём ни новый StoredObject, ни новый FileItem.
            // Тот же checksum в другой папке дублем не считается и приведёт к новой загрузке.
            Optional<FileItem> existingInFolder = fileItemRepository
                    .findFirstByUserIdAndFolderIdAndChecksumOrderByIdAsc(user.getId(), folder.getId(), checksum);
            if (existingInFolder.isPresent()) {
                deleteIfExists(tempFile);
                tempFile = null;
                return fileItemMapper.toDto(existingInFolder.get());
            }

            // Имя проверяем только для действительно новой загрузки в папку.
            ensureFileNameAvailable(user.getId(), folder, originalName, null);

            // Физический каталог: CAMERA → год съёмки, остальные папки → files.
            String filePath = storageKeyGenerator.generateFilePath(user.getId(), folder.getFolderType(), capturedAt);
            String filename = storageKeyGenerator.generateFilename(originalName, detectedMimeType);
            String fileExtension = filenameSanitizer.extension(originalName, detectedMimeType);
            Path finalFile = storagePathResolver.resolve(filePath, filename);

            // Публикуем до БД: если файловая операция не удалась, в базе не появится ссылка на отсутствующий файл.
            publishExclusively(tempFile, finalFile);
            tempFile = null;

            FileItem saved;
            try {
                saved = createStoredObjectAndFileItem(
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
                        fileType,
                        finalFile
                );
            } catch (DataIntegrityViolationException ex) {
                // Гонка: параллельный upload уже создал FileItem с таким checksum в этой папке (уникальность user+folder+checksum).
                // Свой файл saveForPublishedFile уже удалил при откате (или оставил при неизвестном исходе фиксации).
                FileItem racedFileItem = fileItemRepository
                        .findFirstByUserIdAndFolderIdAndChecksumOrderByIdAsc(user.getId(), folder.getId(), checksum)
                        .orElseThrow(() -> ex);
                return fileItemMapper.toDto(racedFileItem);
            }
            // DTO строим после фиксации: ошибка маппинга не должна удалять байты уже сохранённой записи.
            return fileItemMapper.toDto(saved);
        } catch (RuntimeException | IOException ex) {
            // До публикации удаляем только собственный temp; опубликованный файл здесь не трогаем.
            if (tempFile != null) {
                deleteIfExists(tempFile);
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
        String originalName = normalizeOriginalName(request.originalName());
        // Изменяем managed-сущность в одной транзакции: save() detached-объекта через merge вернул бы копию
        // с неинициализированным proxy StoredObject, и маппинг после закрытия сессии упал бы с LazyInitializationException.
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        FileItem renamed = transactionTemplate.execute(status -> {
            FileItem file = getFileForCurrentUser(fileId, user);
            // Переименование меняет только логическую запись: физическое имя стабильно и принадлежит object storage.
            ensureFileNameAvailable(user.getId(), file.getFolder(), originalName, file.getId());
            file.setOriginalName(originalName);
            return fileItemRepository.save(file);
        });
        return fileItemMapper.toDto(renamed);
    }

    public FileItemDto moveFileForCurrentUser(Long fileId, MoveFileRequest request, User user) {
        // Как и rename: работаем с managed-сущностью, загруженной вместе со StoredObject и metadata.
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        FileItem moved = transactionTemplate.execute(status -> {
            FileItem file = getFileForCurrentUser(fileId, user);
            Folder targetFolder = folderService.getFolderForUser(request.targetFolderId(), user);
            // Физический файл не перемещаем: дерево папок живёт в БД, а storage работает как object storage.
            ensureFileNameAvailable(user.getId(), targetFolder, file.getOriginalName(), file.getId());
            file.setFolder(targetFolder);
            return fileItemRepository.save(file);
        });
        return fileItemMapper.toDto(moved);
    }

    public FileItemDto copyFileForCurrentUser(Long fileId, CopyFileRequest request, User user) throws IOException {
        FileItem sourceFile = getFileForCurrentUser(fileId, user);
        StoredObject sourceStoredObject = sourceFile.getStoredObject();
        // Копия кладётся в каталог источника, а он лежит под userId владельца байтов: чужой StoredObject не копируем.
        // TODO: копирование расшаренных объектов между пользователями — вместе с sharing.
        if (!sourceStoredObject.getUser().getId().equals(user.getId())) {
            throw new FileAccessDeniedException("Copying a file stored by another user is not allowed");
        }
        Folder targetFolder = request.targetFolderId() == null
                ? sourceFile.getFolder()
                : folderService.getFolderForUser(request.targetFolderId(), user);
        String originalName = request.originalName() == null || request.originalName().isBlank()
                ? sourceFile.getOriginalName()
                : normalizeOriginalName(request.originalName());

        ensureFileNameAvailable(user.getId(), targetFolder, originalName, null);
        // Дубль логического файла в папке запрещён (user + folder + checksum); copy не создаёт дубль в той же папке.
        ensureChecksumAvailableInFolder(user.getId(), targetFolder, sourceStoredObject.getChecksum());

        Path sourcePath = storagePathResolver.resolve(sourceStoredObject.getFilePath(), sourceStoredObject.getFilename());
        // Копия остаётся в физическом каталоге источника (camera/YYYY или files) независимо от целевой папки:
        // категория и год байтов не зависят от логических перемещений.
        String filePath = sourceStoredObject.getFilePath();
        String filename = storageKeyGenerator.generateFilename(originalName, sourceStoredObject.getDetectedMimeType());
        Path copiedPath = storagePathResolver.resolve(filePath, filename);
        Path tempFile = null;

        try {
            // Копируем в собственный temp: в конечном каталоге появляется только полностью записанная копия.
            tempFile = createTempFile();
            try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
                Files.copy(sourcePath, outputStream);
            }
            publishExclusively(tempFile, copiedPath);
            tempFile = null;

            // Copy намеренно не использует dedup: пользователь получает независимый физический объект.
            FileItem saved = createCopiedStoredObjectAndFileItem(
                    user,
                    targetFolder,
                    sourceFile,
                    sourceStoredObject,
                    originalName,
                    filePath,
                    filename,
                    copiedPath
            );
            // DTO строим после фиксации: ошибка маппинга не должна удалять байты уже сохранённой записи.
            return fileItemMapper.toDto(saved);
        } catch (RuntimeException | IOException ex) {
            // До публикации удаляем только собственный temp; опубликованный файл здесь не трогаем.
            if (tempFile != null) {
                deleteIfExists(tempFile);
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

    private void ensureChecksumAvailableInFolder(Long userId, Folder folder, String checksum) {
        if (fileItemRepository.findFirstByUserIdAndFolderIdAndChecksumOrderByIdAsc(userId, folder.getId(), checksum).isPresent()) {
            throw new FileConflictException("File with this checksum already exists in folder");
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
        // temp-dir обязан быть на той же файловой системе, что и storage.root: publishExclusively публикует только через ATOMIC_MOVE.
        return Files.createTempFile(tempDir, "upload-", ".tmp");
    }

    private StreamedFileInfo writeToTempFile(MultipartFile file, Path tempFile) throws IOException {
        try (InputStream inputStream = file.getInputStream();
             OutputStream outputStream = Files.newOutputStream(tempFile)) {
            return FileUtils.writeAndCalculateSHA256(inputStream, outputStream, storageProperties.getMaxFileSizeBytes());
        }
    }

    // Публикует полностью записанный temp под конечным именем и никогда не заменяет существующий файл.
    // При ошибке temp остаётся у вызывающего кода, а под конечным именем ничего своего не остаётся.
    private void publishExclusively(Path tempFile, Path finalFile) throws IOException {
        Files.createDirectories(finalFile.getParent());
        // CREATE_NEW атомарно резервирует имя: занятый путь даёт FileAlreadyExistsException, чужой файл не трогаем.
        Files.createFile(finalFile);
        try {
            // ATOMIC_MOVE заменяет только собственную пустую заглушку. Fallback для переноса между ФС не используем:
            // он записывал бы байты прямо в конечный файл, поэтому AtomicMoveNotSupportedException — ошибка операции.
            Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ex) {
            // Перенос не состоялся: под конечным именем всё ещё наша заглушка.
            deleteIfExists(finalFile);
            throw ex;
        }
    }

    // Сохраняет записи для уже опубликованного файла и решает судьбу его байтов при ошибке.
    private FileItem saveForPublishedFile(Path publishedFile, Supplier<FileItem> save) {
        AtomicBoolean callbackCompleted = new AtomicBoolean();
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                FileItem saved = save.get();
                callbackCompleted.set(true);
                return saved;
            });
        } catch (RuntimeException ex) {
            if (callbackCompleted.get()) {
                // Ошибка при фиксации: транзакция могла быть зафиксирована. Лучше orphan-файл, чем запись без байтов.
                log.error("Неизвестен исход фиксации транзакции, физический файл {} оставлен на диске", publishedFile, ex);
            } else {
                // Callback упал до фиксации: TransactionTemplate откатил новую транзакцию (upload/copy идут вне внешней
                // транзакции), а файл создан этой операцией через publishExclusively.
                deleteIfExists(publishedFile);
            }
            throw ex;
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
                                                   FileType fileType,
                                                   Path publishedFile) {
        return saveForPublishedFile(publishedFile, () -> {
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
                // TODO: при изменении модели хранения следить, чтобы FileItem.checksum оставался синхронизирован со StoredObject.checksum.
                .checksum(storedObject.getChecksum())
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
                                                         String filename,
                                                         Path publishedFile) {
        return saveForPublishedFile(publishedFile, () -> {
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
                    .checksum(copiedStoredObject.getChecksum())
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
