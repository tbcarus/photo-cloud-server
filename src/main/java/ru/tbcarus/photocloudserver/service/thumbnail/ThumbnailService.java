package ru.tbcarus.photocloudserver.service.thumbnail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.exception.FileItemNotFoundException;
import ru.tbcarus.photocloudserver.exception.ThumbnailBusyException;
import ru.tbcarus.photocloudserver.exception.ThumbnailUnreadableException;
import ru.tbcarus.photocloudserver.exception.ThumbnailUnsupportedException;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.StoredObject;
import ru.tbcarus.photocloudserver.service.FileItemService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Миниатюры создаются при запросе и не хранятся: результат кеширует клиент по ETag.
 * Одновременных генераций не больше thumbnail.max-concurrent; лишний запрос получает отказ без ожидания.
 */
@Slf4j
@Service
public class ThumbnailService {

    private final FileItemService fileItemService;
    private final ThumbnailGenerator generator;
    private final ThumbnailProperties properties;
    private final Semaphore slots;

    public ThumbnailService(FileItemService fileItemService, ThumbnailGenerator generator, ThumbnailProperties properties) {
        this.fileItemService = fileItemService;
        this.generator = generator;
        this.properties = properties;
        this.slots = new Semaphore(properties.getMaxConcurrent());
    }

    // Формат определяется по MIME, найденному Tika по байтам при загрузке; имя файла не учитывается.
    public void requireSupported(FileItem file) {
        if (!ThumbnailGenerator.isSupported(file.getStoredObject().getDetectedMimeType())) {
            throw new ThumbnailUnsupportedException("Thumbnail is not supported for this file type");
        }
    }

    /**
     * Байты StoredObject не меняются, поэтому миниатюра зависит только от содержимого, версии алгоритма и размера.
     * Хеш скрывает checksum исходника.
     */
    public String etag(FileItem file) {
        StoredObject storedObject = file.getStoredObject();
        String source = storedObject.getChecksum() + ":" + ThumbnailGenerator.ALGORITHM_VERSION + ":" + properties.getMaxSide();
        return "\"" + sha256(source).substring(0, 32) + "\"";
    }

    public CacheControl cacheControl() {
        return CacheControl.maxAge(properties.getCacheMaxAge()).cachePrivate();
    }

    public byte[] generate(FileItem file) throws IOException {
        requireSupported(file);
        // Путь вычисляется до занятия слота: отсутствующий файл не расходует лимит.
        Path source = fileItemService.getDownloadResource(file).getFile().toPath();
        if (!slots.tryAcquire()) {
            throw new ThumbnailBusyException();
        }
        long startedAt = System.nanoTime();
        try {
            byte[] jpeg = generator.generate(source, file.getStoredObject().getDetectedMimeType());
            log.debug("Миниатюра файла {}: {} байт за {} мс",
                    file.getId(), jpeg.length, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            return jpeg;
        } catch (NoSuchFileException e) {
            // Файл удалили или переместили после вычисления пути: как у download — 404.
            throw new FileItemNotFoundException(file.getId());
        } catch (ThumbnailUnreadableException e) {
            // Только тип ошибки: без пути и содержимого файла.
            log.warn("Миниатюра файла {} не создана: {}", file.getId(),
                    e.getCause() == null ? e.getMessage() : e.getCause().getClass().getSimpleName());
            throw e;
        } finally {
            slots.release();
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
