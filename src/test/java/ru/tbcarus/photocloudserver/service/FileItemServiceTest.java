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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import ru.tbcarus.photocloudserver.model.*;
import ru.tbcarus.photocloudserver.model.dto.mapper.FileItemMapper;
import ru.tbcarus.photocloudserver.model.dto.mapper.FileItemMapperImpl;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.StoredObjectRepository;
import ru.tbcarus.photocloudserver.service.metadata.ExtractedFileMetadata;
import ru.tbcarus.photocloudserver.service.metadata.FileMetadataExtractor;
import ru.tbcarus.photocloudserver.service.storage.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    FileItemService fileItemService;

    @BeforeEach
    void setUp() {
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setRoot(storageRoot.toString());
        storageProperties.setTempDir(storageRoot.resolve("tmp").toString());
        storageProperties.setMaxFileSizeBytes(1024);

        FilenameSanitizer filenameSanitizer = new FilenameSanitizer();
        StorageKeyGenerator storageKeyGenerator = new StorageKeyGenerator(filenameSanitizer, storageProperties);
        StoragePathResolver storagePathResolver = new StoragePathResolver(storageProperties);
        FileContentDetector fileContentDetector = new FileContentDetector();
        FileItemMapper fileItemMapper = new FileItemMapperImpl();

        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(fileMetadataExtractor.extract(any(Path.class), any())).thenReturn(ExtractedFileMetadata.builder().build());

        fileItemService = new FileItemService(
                fileItemRepository,
                storedObjectRepository,
                folderService,
                fileItemMapper,
                storageKeyGenerator,
                storagePathResolver,
                filenameSanitizer,
                fileContentDetector,
                storageProperties,
                fileMetadataExtractor,
                transactionManager
        );
    }

    @Test
    void finalFileIsRemovedWhenDatabaseSaveFailsAfterMove() throws Exception {
        User user = User.builder().id(1L).email("user@test.local").build();
        Folder folder = Folder.builder().id(1L).user(user).name("Files").folderType(FolderType.FILES).build();
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "content".getBytes());

        when(folderService.getDefaultFolder(user, FileType.DOCUMENT)).thenReturn(folder);
        when(storedObjectRepository.findByUserIdAndChecksum(any(), any())).thenReturn(Optional.empty());
        when(storedObjectRepository.save(any())).thenThrow(new RuntimeException("db failed"));

        assertThatThrownBy(() -> fileItemService.uploadFile(file, user))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db failed");

        assertThat(regularFileCount()).isZero();
    }

    @Test
    void raceConditionRemovesCurrentFinalFileAndUsesExistingStoredObject() throws Exception {
        User user = User.builder().id(1L).email("user@test.local").build();
        Folder folder = Folder.builder().id(1L).user(user).name("Files").folderType(FolderType.FILES).build();
        StoredObject existingStoredObject = StoredObject.builder()
                .id(10L)
                .user(user)
                .filePath("users/1/objects/existing")
                .filename("existing.txt")
                .fileExtension("txt")
                .checksum("checksum")
                .size(7L)
                .detectedMimeType("text/plain")
                .fileType(FileType.DOCUMENT)
                .build();
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "content".getBytes());

        when(folderService.getDefaultFolder(user, FileType.DOCUMENT)).thenReturn(folder);
        when(storedObjectRepository.findByUserIdAndChecksum(any(), any()))
                .thenReturn(Optional.empty(), Optional.of(existingStoredObject));
        when(storedObjectRepository.save(any())).thenThrow(new DataIntegrityViolationException("race"));
        when(fileItemRepository.save(any())).thenAnswer(invocation -> {
            FileItem fileItem = invocation.getArgument(0);
            fileItem.setId(42L);
            return fileItem;
        });

        var response = fileItemService.uploadFile(file, user);

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getOriginalFilename()).isEqualTo("note.txt");
        assertThat(regularFileCount()).isZero();
    }

    private long regularFileCount() throws Exception {
        try (var stream = Files.walk(storageRoot)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }
}
