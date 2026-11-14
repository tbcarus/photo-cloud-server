package ru.tbcarus.photocloudserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
import ru.tbcarus.photocloudserver.service.storage.LibraryStorage;
import ru.tbcarus.photocloudserver.service.storage.OpenedFile;
import ru.tbcarus.photocloudserver.service.storage.OwnerMarker;
import ru.tbcarus.photocloudserver.service.storage.StorageKeyGenerator;
import ru.tbcarus.photocloudserver.service.storage.StoragePathResolver;
import ru.tbcarus.photocloudserver.service.storage.StorageProperties;
import ru.tbcarus.photocloudserver.util.FileUtils;
import ru.tbcarus.photocloudserver.util.FileUtils.StreamedFileInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
    private final LibraryStorage libraryStorage;
    private final FilenameSanitizer filenameSanitizer;
    private final FileContentDetector fileContentDetector;
    private final StorageProperties storageProperties;
    private final FileMetadataExtractor fileMetadataExtractor;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;
    private final ReadAccess readAccess;

    public FileItemDto uploadFile(MultipartFile file, User user) throws IOException {
        return uploadFile(file, user, null);
    }

    public FileItemDto uploadFile(MultipartFile file, User user, Long folderId) throws IOException {
        return uploadFile(file, user, folderId, null);
    }

    // backupSource отличает загрузку механизма резервирования: только она пишет в дерево активного источника.
    public FileItemDto uploadFile(MultipartFile file, User user, Long folderId, UUID backupSource) throws IOException {
        // Загрузка резервирования всегда адресна: default routing создал бы Camera до отказа.
        if (backupSource != null && folderId == null) {
            throw new IllegalArgumentException("folderId is required with backupSource");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой");
        }

        // temp принадлежит операции до публикации; после неё Files.deleteIfExists в finally ничего не найдёт.
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
            Folder folder = resolveTargetFolder(user, folderId, fileType, backupSource);
            String originalName = uploadName(folder, file.getOriginalFilename());

            // Повтор той же загрузки идемпотентен: не создаём ни новый StoredObject, ни новый FileItem.
            // Занятое имя даёт конфликт ещё до блокировки; строгая проверка повторяется под ней при сохранении.
            Optional<FileItem> existingFile = findUploadedFile(user, folder, originalName, checksum);
            if (existingFile.isPresent()) {
                return fileItemMapper.toDto(existingFile.get());
            }

            // Физический ключ только у Camera: путь файла библиотеки вычисляется под блокировкой из дерева папок.
            boolean camera = isCamera(folder);
            String filePath = camera ? storageKeyGenerator.generateCameraPath(user.getId(), capturedAt) : null;
            String filename = camera ? storageKeyGenerator.generateFilename(originalName, detectedMimeType) : null;
            String fileExtension = camera ? filenameSanitizer.extension(originalName, detectedMimeType) : null;

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
                        tempFile,
                        backupSource
                );
            } catch (UploadRaceLostException ex) {
                // Параллельный upload того же файла зафиксирован первым: свои байты ещё не опубликованы.
                return fileItemMapper.toDto(ex.existingFile);
            }
            ensureOwnerMarker(user);
            // DTO строим после фиксации: ошибка маппинга не должна затрагивать байты уже сохранённой записи.
            return fileItemMapper.toDto(saved);
        } finally {
            if (tempFile != null) {
                deleteIfExists(tempFile);
            }
        }
    }

    public Page<FileItemDto> getUserFiles(Pageable pageable, User user) {
        return fileItemRepository.findAllByUserId(user.getId(), pageable).map(fileItemMapper::toDto);
    }

    public void validateUploadTarget(User user, Long folderId, String name, UUID backupSource) {
        Folder folder = folderService.getFolderForUser(folderId, user);
        folderService.ensureCanHoldFiles(folder);
        folderService.ensureWritable(folder, backupSource);
        uploadName(folder, name);
    }

    // Проверенный файл сборки не перемещается: публикация и результат сессии принадлежат одной транзакции.
    public void registerResumable(ru.tbcarus.photocloudserver.model.UploadSession session, User user, Path source,
                                  java.util.function.BiConsumer<Path, FileItemDto> publishAndComplete) throws IOException {
        String mime = fileContentDetector.detectMimeType(source);
        FileType type = FileType.fromMimeType(mime);
        ExtractedFileMetadata metadata = fileMetadataExtractor.extract(source, mime);
        LocalDateTime uploaded = LocalDateTime.now();
        LocalDateTime captured = metadata.getCapturedAt() == null ? uploaded : metadata.getCapturedAt();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            folderService.lockUserTree(user);
            Folder folder = folderService.getFolderForUser(session.getFolderId(), user);
            folderService.ensureCanHoldFiles(folder);
            folderService.ensureWritable(folder, session.getBackupSource());
            String name = uploadName(folder, session.getOriginalFilename());
            Optional<FileItem> existing = findUploadedFile(user, folder, name, session.getChecksum());
            if (existing.isPresent()) {
                publishAndComplete.accept(null, fileItemMapper.toDto(existing.get()));
                return;
            }
            boolean camera = isCamera(folder);
            StoredObject object = storedObjectRepository.save(StoredObject.builder()
                    .user(user).checksum(session.getChecksum()).size(session.getSize())
                    .detectedMimeType(mime).fileType(type)
                    .filePath(camera ? storageKeyGenerator.generateCameraPath(user.getId(), captured) : null)
                    .filename(camera ? storageKeyGenerator.generateFilename(name, mime) : null)
                    .fileExtension(camera ? filenameSanitizer.extension(name, mime) : null).build());
            FileItem saved = saveFileItem(user, folder, object, name, captured, uploaded, metadata);
            fileItemRepository.flush();
            Path destination = libraryStorage.physicalPath(saved);
            try {
                if (camera) Files.createDirectories(destination.getParent());
                else libraryStorage.createLibraryRoot(user.getId());
                libraryStorage.ensureInsideStorage(destination);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            publishAndComplete.accept(destination, fileItemMapper.toDto(saved));
        });
        ensureOwnerMarker(user);
    }

    public Page<FileItemDto> getUserFiles(Pageable pageable, User user, Long folderId) {
        if (folderId == null) {
            return getUserFiles(pageable, user);
        }
        // Предоставленная папка читается файлами её владельца; без folderId список остаётся только своим.
        Folder folder = readAccess.readableFolder(folderId, user);
        return fileItemRepository.findAllByUserIdAndFolderId(folder.getUser().getId(), folderId, pageable)
                .map(fileItemMapper::toDto);
    }

    // Только владелец: основа операций изменения. Чтение предоставленных файлов — getReadableFile.
    public FileItem getFileForCurrentUser(Long fileId, User user) {
        return fileItemRepository.findByIdAndUserId(fileId, user.getId())
                .orElseThrow(() -> new FileItemNotFoundException(fileId));
    }

    // Метаданные, download и миниатюра: собственный файл или действующий USER-доступ.
    public FileItem getReadableFile(Long fileId, User user) {
        return readAccess.readableFile(fileId, user);
    }

    public FileItemDto getReadableFileDto(Long fileId, User user) {
        return fileItemMapper.toDto(getReadableFile(fileId, user));
    }

    // Путь библиотеки вычисляется из имени: проверенная вне блокировки запись могла устареть, а её прежнее имя —
    // достаться другому файлу. Поэтому запись перечитывается и файл открывается под общей блокировкой дерева владельца:
    // все изменения путей идут под той же блокировкой. После открытия канал читает тот же объект, что бы ни стало с путём;
    // транзакция и блокировка заканчиваются сразу после открытия, передача байтов идёт без них.
    // Канал NIO на Windows не мешает rename и delete самого файла; папку с открытым файлом NTFS переименовать не даёт.
    public OpenedFile openForRead(FileItem fileItem) {
        Long ownerId = fileItem.getUser().getId();
        AtomicReference<OpenedFile> opened = new AtomicReference<>();
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                folderService.lockUserTreeForRead(ownerId);
                FileItem current = fileItemRepository.findWithRelationsById(fileItem.getId())
                        .orElseThrow(() -> new FileItemNotFoundException(fileItem.getId()));
                Path path = libraryStorage.physicalPath(current);
                try {
                    libraryStorage.ensureInsideStorage(path);
                    if (!Files.isRegularFile(path)) {
                        throw new FileItemNotFoundException(fileItem.getId());
                    }
                    opened.set(OpenedFile.open(current, path));
                    return opened.get();
                } catch (NoSuchFileException | AccessDeniedException ex) {
                    throw new FileItemNotFoundException(fileItem.getId());
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (RuntimeException ex) {
            // Например, ошибка COMMIT после открытия: канал не должен остаться без владельца.
            if (opened.get() != null) {
                opened.get().close();
            }
            throw ex;
        }
    }

    public FileItemDto renameFileForCurrentUser(Long fileId, RenameFileRequest request, User user) {
        return moveInTree(fileId, user, file -> {
            // Изменяем managed-сущность в одной транзакции: save() detached-объекта через merge вернул бы копию
            // с неинициализированным proxy StoredObject, и маппинг после закрытия сессии упал бы с LazyInitializationException.
            // Механизм резервирования переименовывает файл своего источника при архивировании прежней версии.
            folderService.ensureWritable(file.getFolder(), request.backupSource());
            String originalName;
            if (isCamera(file.getFolder())) {
                originalName = normalizeOriginalName(request.originalName());
            } else {
                originalName = request.originalName();
                filenameSanitizer.validateLibraryName(originalName);
                folderService.ensureNameAvailable(user, file.getFolder(), originalName, file.getId(), null);
            }
            file.setOriginalName(originalName);
        });
    }

    public FileItemDto moveFileForCurrentUser(Long fileId, MoveFileRequest request, User user) {
        return moveInTree(fileId, user, file -> {
            Folder targetFolder = folderService.getFolderForUser(request.targetFolderId(), user);
            folderService.ensureCanHoldFiles(targetFolder);
            // Move из активного дерева, внутри него и в него запрещён и для механизма резервирования.
            folderService.ensureNotManaged(file.getFolder());
            folderService.ensureNotManaged(targetFolder);
            if (isCamera(file.getFolder()) != isCamera(targetFolder)) {
                throw new IllegalArgumentException("Cannot move file between Camera and library folders");
            }
            if (!isCamera(targetFolder)) {
                folderService.ensureNameAvailable(user, targetFolder, file.getOriginalName(), file.getId(), null);
            }
            file.setFolder(targetFolder);
        });
    }

    // Запись БД и перенос байтов под одной блокировкой. Путь Camera задан ключом и не меняется: перенос на диске не выполняется.
    private FileItemDto moveInTree(Long fileId, User user, Consumer<FileItem> change) {
        AtomicReference<Path> source = new AtomicReference<>();
        AtomicReference<Path> target = new AtomicReference<>();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        try {
            FileItem moved = transactionTemplate.execute(status -> {
                folderService.lockUserTree(user);
                FileItem file = getFileForCurrentUser(fileId, user);
                Path from = libraryStorage.physicalPath(file);
                change.accept(file);
                FileItem saved = fileItemRepository.saveAndFlush(file);
                Path to = libraryStorage.physicalPath(saved);
                libraryStorage.move(from, to);
                source.set(from);
                target.set(to);
                return saved;
            });
            return fileItemMapper.toDto(moved);
        } catch (RuntimeException ex) {
            folderService.undoMoveIfRolledBack(user, source.get(), target.get(),
                    () -> libraryStorage.physicalPath(getFileForCurrentUser(fileId, user)));
            throw ex;
        }
    }

    public FileItemDto copyFileForCurrentUser(Long fileId, CopyFileRequest request, User user) throws IOException {
        FileItem sourceFile = getFileForCurrentUser(fileId, user);
        StoredObject sourceStoredObject = sourceFile.getStoredObject();
        // Копия лежит в дереве текущего пользователя, поэтому чужой StoredObject не копируем.
        // TODO: копирование расшаренных объектов между пользователями — вместе с sharing.
        if (!sourceStoredObject.getUser().getId().equals(user.getId())) {
            throw new FileAccessDeniedException("Copying a file stored by another user is not allowed");
        }
        Folder targetFolder = request.targetFolderId() == null
                ? sourceFile.getFolder()
                : folderService.getFolderForUser(request.targetFolderId(), user);
        ensureCopyAllowed(sourceFile, targetFolder);
        // Copy из активного дерева и в него запрещён; цель повторно проверяется под блокировкой.
        folderService.ensureNotManaged(sourceFile.getFolder());
        folderService.ensureNotManaged(targetFolder);
        String originalName = copyName(request.originalName(), sourceFile.getOriginalName());

        // Конфликт виден ещё до копирования байтов; строгая проверка повторяется под блокировкой при сохранении.
        folderService.ensureNameAvailable(user, targetFolder, originalName, null, null);

        Path tempFile = null;
        try {
            // Копируем в собственный temp: в конечном каталоге появляется только полностью записанная копия.
            tempFile = createTempFile();
            copySourceBytes(sourceFile, tempFile);

            // Copy намеренно не использует dedup: пользователь получает независимый физический объект.
            FileItem saved = createCopiedStoredObjectAndFileItem(
                    user,
                    targetFolder,
                    sourceFile,
                    sourceStoredObject,
                    originalName,
                    tempFile
            );
            ensureOwnerMarker(user);
            // DTO строим после фиксации: ошибка маппинга не должна затрагивать байты уже сохранённой записи.
            return fileItemMapper.toDto(saved);
        } finally {
            if (tempFile != null) {
                deleteIfExists(tempFile);
            }
        }
    }

    // Источник открывается по актуальной записи под общей блокировкой дерева (удалённый даёт 404), байты копируются без неё.
    // Новая запись получит checksum и размер источника, поэтому скопированные байты обязаны им соответствовать:
    // расхождение означает, что байты на диске не те, что описывает запись, и копия не создаётся.
    private void copySourceBytes(FileItem sourceFile, Path tempFile) throws IOException {
        try (OpenedFile source = openForRead(sourceFile);
             InputStream inputStream = source.getInputStream();
             OutputStream outputStream = Files.newOutputStream(tempFile)) {
            StreamedFileInfo copied = FileUtils.writeAndCalculateSHA256(inputStream, outputStream, Long.MAX_VALUE);
            StoredObject stored = source.file().getStoredObject();
            if (copied.size() != stored.getSize() || !copied.checksum().equals(stored.getChecksum())) {
                log.error("Байты файла {} не совпадают с записью: размер {} вместо {}, checksum {} вместо {}",
                        sourceFile.getId(), copied.size(), stored.getSize(), copied.checksum(), stored.getChecksum());
                throw new IOException("Physical object does not match its record");
            }
        } catch (FileItemNotFoundException ex) {
            // Удалённый источник — 404; запись есть, а байтов нет — ошибка хранилища, как и прежде 500.
            if (fileItemRepository.existsById(sourceFile.getId())) {
                throw new IOException("Physical object of the source is missing", ex);
            }
            throw ex;
        }
    }

    public void deleteFileForCurrentUser(Long fileId, User user) {
        // В общей очереди: rename и move не застанут уже удалённую запись, а повтор delete получит 404.
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            folderService.lockUserTree(user);
            FileItem file = getFileForCurrentUser(fileId, user);
            folderService.ensureNotManaged(file.getFolder());
            StoredObject storedObject = file.getStoredObject();

            if (!file.getUser().getId().equals(storedObject.getUser().getId())) {
                fileItemRepository.delete(file);
                return;
            }

            Path source = libraryStorage.physicalPath(file);
            deleteRecords(file);
            // Байты покидают своё имя до COMMIT, поэтому новый объект с этим именем их не встретит.
            libraryStorage.moveToPendingDelete("file", fileId, source);
        });
    }

    // Владелец физического объекта удаляет все логические записи, которые на него ссылаются.
    // TODO: При появлении sharing нужно пересмотреть права доступа и удаление чужих FileItem.
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteRecords(FileItem file) {
        StoredObject storedObject = file.getStoredObject();
        fileItemRepository.deleteAll(fileItemRepository.findAllByStoredObjectId(storedObject.getId()));
        fileItemRepository.flush();
        storedObjectRepository.delete(storedObject);
        storedObjectRepository.flush();
    }

    public List<FileChecksumDto> getChecksumsForUser(Long userId) {
        return fileItemRepository.findAllChecksumsAndOriginalFilenamesByUserId(userId);
    }

    private Folder resolveTargetFolder(User user, Long folderId, FileType fileType, UUID backupSource) {
        Folder folder;
        if (folderId == null) {
            folder = folderService.getDefaultFolder(user, fileType);
        } else {
            folder = folderService.getFolderForUser(folderId, user);
            folderService.ensureCanHoldFiles(folder);
        }
        // Ранний отказ до работы с байтами; строгая проверка повторяется под блокировкой при сохранении.
        folderService.ensureWritable(folder, backupSource);
        return folder;
    }

    private boolean isCamera(Folder folder) {
        return folder.getFolderType() == FolderType.CAMERA;
    }

    // Camera сохраняет прежнее очищение имени; в обычной библиотеке имя принимается только как есть.
    private String uploadName(Folder folder, String requestedName) {
        if (isCamera(folder)) {
            return filenameSanitizer.limitOriginalNameWithExtension(requestedName, 255);
        }
        filenameSanitizer.validateLibraryName(requestedName);
        return requestedName;
    }

    private String copyName(String requestedName, String sourceName) {
        String name = requestedName == null || requestedName.isBlank() ? sourceName : requestedName;
        filenameSanitizer.validateLibraryName(name);
        return name;
    }

    // Файл, который уже закрывает эту загрузку: в Camera — по checksum, в библиотеке — то же имя с тем же содержимым.
    // Другое содержимое или папка под этим именем в библиотеке — конфликт.
    private Optional<FileItem> findUploadedFile(User user, Folder folder, String originalName, String checksum) {
        if (isCamera(folder)) {
            return fileItemRepository.findFirstByUserIdAndFolderIdAndChecksumOrderByIdAsc(user.getId(), folder.getId(), checksum);
        }
        Optional<FileItem> sameContent = fileItemRepository.findInFolderByName(user.getId(), folder.getId(), originalName)
                .filter(file -> file.getChecksum().equals(checksum));
        if (sameContent.isEmpty()) {
            folderService.ensureNameAvailable(user, folder, originalName, null, null);
        }
        return sameContent;
    }

    // Копирование в Camera запрещено: внутри неё копия была бы дублем checksum, из библиотеки граница закрыта.
    private void ensureCopyAllowed(FileItem sourceFile, Folder targetFolder) {
        folderService.ensureCanHoldFiles(targetFolder);
        if (!isCamera(targetFolder)) {
            return;
        }
        if (!isCamera(sourceFile.getFolder())) {
            throw new IllegalArgumentException("Cannot copy library file into Camera");
        }
        throw new FileConflictException("File with this checksum already exists in folder");
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

    // Маркер владельца в корне каталога пользователя создаётся при первой физической записи и не перезаписывается.
    // Это справка для администратора: любая ошибка только логируется и не прерывает upload/copy.
    private void ensureOwnerMarker(User user) {
        Path tempFile = null;
        try {
            Path markerFile = storagePathResolver.resolveDirectChild(
                    storageKeyGenerator.generateUserRootPath(user.getId()),
                    filenameSanitizer.ownerMarkerFilename(user.getEmail()));
            if (Files.exists(markerFile)) {
                return;
            }
            tempFile = createTempFile();
            objectMapper.writeValue(tempFile.toFile(),
                    new OwnerMarker(user.getId(), user.getEmail(), user.getCreatedAt(), LocalDateTime.now()));
            publishExclusively(tempFile, markerFile);
            tempFile = null;
        } catch (FileAlreadyExistsException ex) {
            // Параллельная операция успела создать маркер первой — повторно не пишем.
        } catch (IOException | RuntimeException ex) {
            log.warn("Не удалось создать маркер владельца для пользователя {}", user.getId(), ex);
        } finally {
            if (tempFile != null) {
                deleteIfExists(tempFile);
            }
        }
    }

    // Публикация — последнее действие транзакции: записи уже проверены flush, поэтому откат до неё байтов не оставляет.
    // Ошибка COMMIT не говорит об исходе: лучше файл без записи, чем запись без байтов.
    private void publishSaved(Path tempFile, FileItem saved, User user) {
        fileItemRepository.flush();
        Path finalFile = libraryStorage.physicalPath(saved);
        boolean camera = saved.getStoredObject().getFilePath() != null;
        try {
            if (camera) {
                Files.createDirectories(finalFile.getParent());
            } else {
                libraryStorage.createLibraryRoot(user.getId());
                libraryStorage.ensureInsideStorage(finalFile);
            }
            publishExclusively(tempFile, finalFile);
        } catch (FileAlreadyExistsException ex) {
            // Имя в БД свободно, но занято на диске. У Camera имя с UUID, такая коллизия — внутренняя ошибка хранилища.
            if (camera) {
                throw new UncheckedIOException(ex);
            }
            throw new FileConflictException("Physical name is already occupied");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
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
                                                   Path tempFile,
                                                   UUID backupSource) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            folderService.lockUserTree(user);
            // Под блокировкой: папку могли удалить или перенести, а имя или checksum занять после проверки до неё,
            // а источник — отключить: тогда загрузка резервирования получает 409 и байты не публикуются.
            folderService.ensureWritable(folderService.getFolderForUser(folder.getId(), user), backupSource);
            findUploadedFile(user, folder, originalName, checksum).ifPresent(existingFile -> {
                throw new UploadRaceLostException(existingFile);
            });
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
            FileItem saved = saveFileItem(user, folder, storedObject, originalName, capturedAt, uploadedAt, extractedMetadata);
            publishSaved(tempFile, saved, user);
            return saved;
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
                                                         Path tempFile) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            folderService.lockUserTree(user);
            folderService.ensureNotManaged(folderService.getFolderForUser(folder.getId(), user));
            folderService.ensureNameAvailable(user, folder, originalName, null, null);
            StoredObject copiedStoredObject = storedObjectRepository.save(StoredObject.builder()
                    .user(user)
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
            FileItem saved = fileItemRepository.save(copiedFileItem);
            publishSaved(tempFile, saved, user);
            return saved;
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

    // Откатывает транзакцию проигравшего параллельного upload и передаёт файл победителя как обычный ответ.
    private static final class UploadRaceLostException extends RuntimeException {
        private final transient FileItem existingFile;

        private UploadRaceLostException(FileItem existingFile) {
            this.existingFile = existingFile;
        }
    }
}
