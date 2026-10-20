package ru.tbcarus.photocloudserver.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import ru.tbcarus.photocloudserver.model.*;
import ru.tbcarus.photocloudserver.model.dto.CopyFileRequest;
import ru.tbcarus.photocloudserver.model.dto.mapper.FileItemMapper;
import ru.tbcarus.photocloudserver.model.dto.mapper.FileItemMapperImpl;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.StoredObjectRepository;
import ru.tbcarus.photocloudserver.service.metadata.ExtractedFileMetadata;
import ru.tbcarus.photocloudserver.service.metadata.FileMetadataExtractor;
import ru.tbcarus.photocloudserver.service.storage.*;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileItemServiceTest {

    @TempDir
    Path storageRoot;

    @Mock
    FileItemRepository fileItemRepository;

    @Mock
    StoredObjectRepository storedObjectRepository;

    @Mock
    FolderService folderService;

    @Mock
    FileMetadataExtractor fileMetadataExtractor;

    @Mock
    PlatformTransactionManager transactionManager;

    StorageProperties storageProperties;
    FilenameSanitizer filenameSanitizer;
    FileItemService fileItemService;

    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        storageProperties.setRoot(storageRoot.toString());
        storageProperties.setTempDir(storageRoot.resolve("tmp").toString());
        storageProperties.setMaxFileSizeBytes(1024);
        filenameSanitizer = new FilenameSanitizer();

        // lenient: тестам copy транзакция нужна не всегда, а metadata не извлекается вовсе.
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        lenient().when(fileMetadataExtractor.extract(any(Path.class), any())).thenReturn(ExtractedFileMetadata.builder().build());

        fileItemService = newService(new StorageKeyGenerator(filenameSanitizer, storageProperties), new FileItemMapperImpl());
    }

    @Test
    void finalFileIsRemovedWhenDatabaseSaveFailsAfterMove() throws Exception {
        User user = user();
        Folder folder = filesFolder(user);
        Path neighbourFile = writeStorageFile("1/files/neighbour.txt", "neighbour");
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "content".getBytes());

        when(folderService.getDefaultFolder(user, FileType.DOCUMENT)).thenReturn(folder);
        when(fileItemRepository.findFirstByUserIdAndFolderIdAndChecksumOrderByIdAsc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(storedObjectRepository.save(any())).thenThrow(new RuntimeException("db failed"));

        assertThatThrownBy(() -> fileItemService.uploadFile(file, user))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db failed");

        // Откат достоверен: удалён только собственный файл, соседний чужой файл на месте.
        assertThat(Files.readString(neighbourFile)).isEqualTo("neighbour");
        assertThat(regularFileCount()).isEqualTo(1);
    }

    @Test
    void raceConditionRemovesCurrentFinalFileAndReturnsExistingFileItem() throws Exception {
        User user = user();
        Folder folder = filesFolder(user);
        StoredObject existingStoredObject = StoredObject.builder()
                .id(10L)
                .user(user)
                .filePath("1/files")
                .filename("existing.txt")
                .fileExtension("txt")
                .checksum("checksum")
                .size(7L)
                .detectedMimeType("text/plain")
                .fileType(FileType.DOCUMENT)
                .build();
        // Файл успешной параллельной операции лежит в том же каталоге.
        Path winnerFile = writeStorageFile("1/files/existing.txt", "content");
        FileItem racedFileItem = FileItem.builder()
                .id(42L)
                .user(user)
                .folder(folder)
                .storedObject(existingStoredObject)
                .checksum("checksum")
                .originalName("note.txt")
                .capturedAt(LocalDateTime.now())
                .uploadedAt(LocalDateTime.now())
                .build();
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "content".getBytes());

        when(folderService.getDefaultFolder(user, FileType.DOCUMENT)).thenReturn(folder);
        // Первый вызов (idempotency-проверка) — пусто; второй (после гонки в catch) — уже созданный FileItem.
        when(fileItemRepository.findFirstByUserIdAndFolderIdAndChecksumOrderByIdAsc(any(), any(), any()))
                .thenReturn(Optional.empty(), Optional.of(racedFileItem));
        when(storedObjectRepository.save(any())).thenThrow(new DataIntegrityViolationException("race"));

        var response = fileItemService.uploadFile(file, user);

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getOriginalFilename()).isEqualTo("note.txt");
        assertThat(Files.readString(winnerFile)).isEqualTo("content");
        assertThat(regularFileCount()).isEqualTo(1);
    }

    @Test
    void uploadDoesNotOverwriteOrDeleteOccupiedFinalFile() throws Exception {
        User user = user();
        Path foreignFile = writeStorageFile("1/files/taken.txt", "foreign");
        StorageKeyGenerator keyGenerator = mock(StorageKeyGenerator.class);
        when(keyGenerator.generateFilePath(any(), any(), any())).thenReturn("1/files");
        when(keyGenerator.generateFilename(any(), any())).thenReturn("taken.txt");
        FileItemService service = newService(keyGenerator, new FileItemMapperImpl());

        when(folderService.getDefaultFolder(user, FileType.DOCUMENT)).thenReturn(filesFolder(user));
        when(fileItemRepository.findFirstByUserIdAndFolderIdAndChecksumOrderByIdAsc(any(), any(), any()))
                .thenReturn(Optional.empty());

        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "content".getBytes());
        assertThatThrownBy(() -> service.uploadFile(file, user)).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(foreignFile)).isEqualTo("foreign");
        assertThat(tempFileCount()).isZero();
        assertThat(regularFileCount()).isEqualTo(1);
        verifyNoInteractions(storedObjectRepository);
    }

    @Test
    void uploadKeepsPublishedFileWhenMappingFailsAfterCommit() throws Exception {
        User user = user();
        FileItemMapper failingMapper = mock(FileItemMapper.class);
        when(failingMapper.toDto(any(FileItem.class))).thenThrow(new IllegalStateException("mapping failed"));
        FileItemService service = newService(new StorageKeyGenerator(filenameSanitizer, storageProperties), failingMapper);
        mockSuccessfulUploadPersistence(user);

        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "content".getBytes());
        assertThatThrownBy(() -> service.uploadFile(file, user)).hasMessage("mapping failed");

        // Транзакция зафиксирована — байты принадлежат сохранённой записи и не удаляются.
        verify(transactionManager).commit(any());
        assertThat(filesIn("1/files")).isEqualTo(1);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void uploadKeepsPublishedFileWhenCommitOutcomeIsUnknown() throws Exception {
        User user = user();
        mockSuccessfulUploadPersistence(user);
        doThrow(new TransactionSystemException("commit failed")).when(transactionManager).commit(any());

        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "content".getBytes());
        assertThatThrownBy(() -> fileItemService.uploadFile(file, user)).isInstanceOf(TransactionSystemException.class);

        // Транзакция могла зафиксироваться: лучше orphan-файл, чем запись без байтов.
        assertThat(filesIn("1/files")).isEqualTo(1);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void copyDoesNotOverwriteOrDeleteOccupiedFinalFile() throws Exception {
        User user = user();
        FileItem source = cameraSource(user);
        Path sourceFile = writeStorageFile("1/camera/2023/IMG_1.jpg_source.jpg", "source");
        Path foreignFile = writeStorageFile("1/camera/2023/taken.jpg", "foreign");
        StorageKeyGenerator keyGenerator = mock(StorageKeyGenerator.class);
        when(keyGenerator.generateFilename(any(), any())).thenReturn("taken.jpg");
        FileItemService service = newService(keyGenerator, new FileItemMapperImpl());
        mockCopyLookups(user, source);

        assertThatThrownBy(() -> service.copyFileForCurrentUser(5L, new CopyFileRequest(2L, null), user))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(foreignFile)).isEqualTo("foreign");
        assertThat(Files.readString(sourceFile)).isEqualTo("source");
        assertThat(tempFileCount()).isZero();
        assertThat(regularFileCount()).isEqualTo(2);
        verifyNoInteractions(storedObjectRepository);
    }

    @Test
    void copyRemovesOwnPublishedFileWhenDatabaseSaveFails() throws Exception {
        User user = user();
        FileItem source = cameraSource(user);
        Path sourceFile = writeStorageFile("1/camera/2023/IMG_1.jpg_source.jpg", "source");
        mockCopyLookups(user, source);
        when(storedObjectRepository.save(any())).thenThrow(new RuntimeException("db failed"));

        assertThatThrownBy(() -> fileItemService.copyFileForCurrentUser(5L, new CopyFileRequest(2L, null), user))
                .hasMessage("db failed");

        assertThat(Files.readString(sourceFile)).isEqualTo("source");
        assertThat(filesIn("1/camera/2023")).isEqualTo(1);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void failedCopyLeavesNothingInFinalDirectory() throws Exception {
        User user = user();
        FileItem source = cameraSource(user);
        // Байтов источника нет: копирование во временный файл падает до публикации.
        Files.createDirectories(storageRoot.resolve("1/camera/2023"));
        mockCopyLookups(user, source);

        assertThatThrownBy(() -> fileItemService.copyFileForCurrentUser(5L, new CopyFileRequest(2L, null), user))
                .isInstanceOf(NoSuchFileException.class);

        assertThat(filesIn("1/camera/2023")).isZero();
        assertThat(tempFileCount()).isZero();
        verifyNoInteractions(storedObjectRepository);
    }

    private FileItemService newService(StorageKeyGenerator storageKeyGenerator, FileItemMapper fileItemMapper) {
        return new FileItemService(
                fileItemRepository,
                storedObjectRepository,
                folderService,
                fileItemMapper,
                storageKeyGenerator,
                new StoragePathResolver(storageProperties),
                filenameSanitizer,
                new FileContentDetector(),
                storageProperties,
                fileMetadataExtractor,
                transactionManager
        );
    }

    private void mockSuccessfulUploadPersistence(User user) {
        when(folderService.getDefaultFolder(user, FileType.DOCUMENT)).thenReturn(filesFolder(user));
        when(fileItemRepository.findFirstByUserIdAndFolderIdAndChecksumOrderByIdAsc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(storedObjectRepository.save(any(StoredObject.class))).thenAnswer(returnsFirstArg());
        when(fileItemRepository.save(any(FileItem.class))).thenAnswer(returnsFirstArg());
    }

    private void mockCopyLookups(User user, FileItem source) {
        Folder target = Folder.builder().id(2L).user(user).name("Trips").folderType(FolderType.USER).build();
        when(fileItemRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(source));
        when(folderService.getFolderForUser(2L, user)).thenReturn(target);
    }

    private FileItem cameraSource(User user) {
        StoredObject storedObject = StoredObject.builder()
                .id(10L)
                .user(user)
                .filePath("1/camera/2023")
                .filename("IMG_1.jpg_source.jpg")
                .fileExtension("jpg")
                .checksum("checksum")
                .size(6L)
                .detectedMimeType("image/jpeg")
                .fileType(FileType.IMAGE)
                .build();
        return FileItem.builder()
                .id(5L)
                .user(user)
                .folder(Folder.builder().id(1L).user(user).name("Camera").folderType(FolderType.CAMERA).build())
                .storedObject(storedObject)
                .checksum("checksum")
                .originalName("IMG_1.jpg")
                .capturedAt(LocalDateTime.of(2023, 12, 31, 23, 30))
                .uploadedAt(LocalDateTime.now())
                .build();
    }

    private static User user() {
        return User.builder().id(1L).email("user@test.local").build();
    }

    private static Folder filesFolder(User user) {
        return Folder.builder().id(1L).user(user).name("Files").folderType(FolderType.FILES).build();
    }

    private Path writeStorageFile(String relativePath, String content) throws IOException {
        Path file = storageRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private long filesIn(String relativeDir) throws IOException {
        Path dir = storageRoot.resolve(relativeDir);
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private long tempFileCount() throws IOException {
        return filesIn("tmp");
    }

    private long regularFileCount() throws Exception {
        try (var stream = Files.walk(storageRoot)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }
}
