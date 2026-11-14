package ru.tbcarus.photocloudserver.service.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import ru.tbcarus.photocloudserver.exception.FileSizeLimitExceededException;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.CreateUploadSessionRequest;
import ru.tbcarus.photocloudserver.repository.*;
import ru.tbcarus.photocloudserver.service.*;
import ru.tbcarus.photocloudserver.service.storage.*;
import ru.tbcarus.photocloudserver.util.FileUtils;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadLimitsTest {
    @TempDir Path root;

    private Binder yaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Map<String, Object> values = new HashMap<>();
        factory.getObject().forEach((key, value) -> values.put(key.toString(), value));
        return new Binder(new MapConfigurationPropertySource(values));
    }

    @Test
    void yamlThresholdAndMaximumAreAppliedWithoutLargeFiles() {
        StorageProperties storage = yaml().bind("storage", StorageProperties.class).get();
        UploadProperties upload = yaml().bind("upload", UploadProperties.class).get();
        assertThat(storage.getMaxFileSizeBytes()).isEqualTo(50L * 1024 * 1024);
        assertThat(upload.getMaxFileSizeBytes()).isEqualTo(20L * 1024 * 1024 * 1024);
        assertThat(upload.getMaxChunkSizeBytes()).isEqualTo(16L * 1024 * 1024);
        UploadSessionRepository repository = mock(UploadSessionRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UploadSessionService service = new UploadSessionService(repository, mock(UserRepository.class), upload, storage,
                mock(UploadStorage.class), new UploadSessionLocks(), mock(FileItemService.class), mock(FolderService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class), new ObjectMapper());
        User user = User.builder().id(1L).build();
        try {
            assertThatThrownBy(() -> service.create(request(storage.getMaxFileSizeBytes()), user)).isInstanceOf(IllegalArgumentException.class);
            assertThat(service.create(request(storage.getMaxFileSizeBytes() + 1), user).size()).isEqualTo(52428801);
            assertThat(service.create(request(upload.getMaxFileSizeBytes()), user).size()).isEqualTo(21474836480L);
            assertThatThrownBy(() -> service.create(request(upload.getMaxFileSizeBytes() + 1), user))
                    .isInstanceOf(FileSizeLimitExceededException.class);
            when(repository.reservedBytes()).thenReturn(upload.getMaxReservedBytes());
            assertThatThrownBy(() -> service.create(request(storage.getMaxFileSizeBytes() + 1), user))
                    .hasMessage("UPLOAD_CAPACITY_EXCEEDED");
        } finally { service.stop(); }
    }

    @Test
    void streamedMultipartAcceptsExactly50MiBAndRejectsNextByte() throws Exception {
        long limit = yaml().bind("storage", StorageProperties.class).get().getMaxFileSizeBytes();
        assertThat(FileUtils.writeAndCalculateSHA256(zeros(limit), OutputStream.nullOutputStream(), limit).size()).isEqualTo(limit);
        assertThatThrownBy(() -> FileUtils.writeAndCalculateSHA256(zeros(limit + 1), OutputStream.nullOutputStream(), limit))
                .isInstanceOf(FileSizeLimitExceededException.class);
    }

    @Test
    void actualChunkBytesAreBoundedAt16MiBEvenWithoutOrWithFalseContentLength() throws Exception {
        long limit = yaml().bind("upload", UploadProperties.class).get().getMaxChunkSizeBytes();
        StorageProperties properties = new StorageProperties();
        properties.setRoot(root.toString());
        StoragePathResolver paths = new StoragePathResolver(properties);
        UploadStorage storage = new UploadStorage(paths, new LibraryStorage(mock(FolderRepository.class), paths,
                new StorageKeyGenerator(new FilenameSanitizer(), properties)));
        UUID id = UUID.randomUUID();
        assertThat(storage.receive(id, zeros(limit), -1, limit)).isEqualTo(limit);
        assertThat(Files.size(storage.chunk(id))).isEqualTo(limit);
        assertThatThrownBy(() -> storage.receive(id, zeros(limit + 1), -1, limit)).isInstanceOf(FileSizeLimitExceededException.class);
        assertThat(Files.size(storage.chunk(id))).isLessThanOrEqualTo(limit);
        assertThatThrownBy(() -> storage.receive(id, zeros(100), 50, limit)).isInstanceOf(IOException.class);
    }

    @Test
    void cancellationBeforeFirstChunkCanCleanANewStorageRoot() throws Exception {
        StorageProperties properties = new StorageProperties();
        properties.setRoot(root.resolve("new-storage").toString());
        StoragePathResolver paths = new StoragePathResolver(properties);
        UploadStorage storage = new UploadStorage(paths, new LibraryStorage(mock(FolderRepository.class), paths,
                new StorageKeyGenerator(new FilenameSanitizer(), properties)));
        UUID id = UUID.randomUUID();
        storage.clean(id);
        assertThat(storage.data(id)).doesNotExist();
        assertThat(storage.chunk(id)).doesNotExist();
    }

    private CreateUploadSessionRequest request(long size) {
        return new CreateUploadSessionRequest(UUID.randomUUID(), 1L, "file.bin", size, "a".repeat(64), null);
    }

    private InputStream zeros(long size) {
        return new InputStream() {
            long remaining = size;
            @Override public int read() { return remaining-- > 0 ? 0 : -1; }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (remaining <= 0) return -1;
                int count = (int) Math.min(length, remaining);
                Arrays.fill(bytes, offset, offset + count, (byte) 0);
                remaining -= count;
                return count;
            }
        };
    }
}
