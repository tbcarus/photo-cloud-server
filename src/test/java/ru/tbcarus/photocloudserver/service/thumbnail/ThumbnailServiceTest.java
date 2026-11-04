package ru.tbcarus.photocloudserver.service.thumbnail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import ru.tbcarus.photocloudserver.exception.FileItemNotFoundException;
import ru.tbcarus.photocloudserver.exception.ThumbnailBusyException;
import ru.tbcarus.photocloudserver.exception.ThumbnailUnreadableException;
import ru.tbcarus.photocloudserver.exception.ThumbnailUnsupportedException;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.StoredObject;
import ru.tbcarus.photocloudserver.service.FileItemService;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThumbnailServiceTest {

    private static final String CHECKSUM = "a".repeat(64);

    @TempDir
    Path dir;

    private final FileItemService fileItemService = mock(FileItemService.class);
    private final ThumbnailGenerator generator = mock(ThumbnailGenerator.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private ThumbnailProperties properties;
    private Path source;

    @BeforeEach
    void setUp() throws Exception {
        properties = ThumbnailGeneratorTest.properties();
        properties.setMaxConcurrent(2);
        source = Files.write(dir.resolve("photo.jpg"), new byte[]{1});
        when(fileItemService.getDownloadResource(any())).thenReturn(new FileSystemResource(source));
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void etagIsStableAndOpaque() {
        ThumbnailService service = service();
        String etag = service.etag(file(1L, CHECKSUM, "image/jpeg"));

        assertThat(etag).isEqualTo(service.etag(file(1L, CHECKSUM, "image/jpeg")));
        // Одинаковое содержимое разных файлов даёт одну миниатюру.
        assertThat(etag).isEqualTo(service.etag(file(2L, CHECKSUM, "image/jpeg")));
        assertThat(etag).matches("\"[0-9a-f]{32}\"");
        assertThat(etag).doesNotContain(CHECKSUM.substring(0, 16));
    }

    @Test
    void etagChangesWithContentAndThumbnailSize() {
        String etag = service().etag(file(1L, CHECKSUM, "image/jpeg"));

        assertThat(service().etag(file(1L, "b".repeat(64), "image/jpeg"))).isNotEqualTo(etag);
        properties.setMaxSide(256);
        assertThat(service().etag(file(1L, CHECKSUM, "image/jpeg"))).isNotEqualTo(etag);
    }

    @Test
    void cacheControlIsPrivateWithConfiguredMaxAge() {
        assertThat(service().cacheControl().getHeaderValue()).isEqualTo("max-age=86400, private");
    }

    @Test
    void unsupportedTypeIsRejectedWithoutResolvingFile() throws Exception {
        ThumbnailService service = service();

        assertThatThrownBy(() -> service.generate(file(1L, CHECKSUM, "image/heic")))
                .isInstanceOf(ThumbnailUnsupportedException.class);
        verify(fileItemService, never()).getDownloadResource(any());
        verify(generator, never()).generate(any(), anyString());
    }

    @Test
    void generationsWithinLimitRunConcurrentlyAndExtraRequestIsRejectedImmediately() throws Exception {
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(generator.generate(any(), anyString())).thenAnswer(invocation -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new byte[]{1};
        });
        ThumbnailService service = service();

        List<Future<byte[]>> running = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            running.add(executor.submit(() -> service.generate(file(1L, CHECKSUM, "image/jpeg"))));
        }
        assertThat(inside.await(5, TimeUnit.SECONDS)).as("обе генерации внутри лимита идут одновременно").isTrue();

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> service.generate(file(1L, CHECKSUM, "image/jpeg")))
                .isInstanceOf(ThumbnailBusyException.class);
        // Отказ без ожидания освобождения слота: очереди нет.
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(1000);

        release.countDown();
        for (Future<byte[]> future : running) {
            assertThat(future.get(5, TimeUnit.SECONDS)).containsExactly(1);
        }
        // Слоты освобождены: следующие запросы снова выполняются.
        assertThat(service.generate(file(1L, CHECKSUM, "image/jpeg"))).containsExactly(1);
    }

    @Test
    void slotIsReleasedAfterGenerationError() throws Exception {
        properties.setMaxConcurrent(1);
        when(generator.generate(any(), anyString()))
                .thenThrow(new ThumbnailUnreadableException("broken"))
                .thenThrow(new RuntimeException("unexpected"))
                .thenThrow(new NoSuchFileException("photo.jpg"))
                .thenReturn(new byte[]{7});
        ThumbnailService service = service();
        FileItem file = file(5L, CHECKSUM, "image/jpeg");

        assertThatThrownBy(() -> service.generate(file)).isInstanceOf(ThumbnailUnreadableException.class);
        assertThatThrownBy(() -> service.generate(file)).isInstanceOf(RuntimeException.class);
        // Файл исчез после вычисления пути — как у download, 404.
        assertThatThrownBy(() -> service.generate(file)).isInstanceOf(FileItemNotFoundException.class);
        assertThat(service.generate(file)).containsExactly(7);
    }

    @Test
    void missingFileDoesNotTakeSlot() throws Exception {
        properties.setMaxConcurrent(1);
        when(fileItemService.getDownloadResource(any()))
                .thenThrow(new FileItemNotFoundException(9L))
                .thenReturn(new FileSystemResource(source));
        when(generator.generate(any(), anyString())).thenReturn(new byte[]{3});
        ThumbnailService service = service();
        FileItem file = file(9L, CHECKSUM, "image/jpeg");

        assertThatThrownBy(() -> service.generate(file)).isInstanceOf(FileItemNotFoundException.class);
        assertThat(service.generate(file)).containsExactly(3);
    }

    private ThumbnailService service() {
        return new ThumbnailService(fileItemService, generator, properties);
    }

    private static FileItem file(Long id, String checksum, String mimeType) {
        StoredObject storedObject = StoredObject.builder().id(id).checksum(checksum).detectedMimeType(mimeType).build();
        FileItem file = new FileItem();
        file.setId(id);
        file.setStoredObject(storedObject);
        return file;
    }
}
