package ru.tbcarus.photocloudserver.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.AbstractResource;
import ru.tbcarus.photocloudserver.model.FileItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Байты файла, открытые под блокировкой дерева владельца: канал привязан к физическому объекту, а не к пути,
 * поэтому последующие rename/move/delete и новый файл под прежним именем на читаемые байты не влияют.
 * Resource для ответа Spring: Range и Content-Length обслуживаются по каналу, каждый поток читает независимо.
 * Канал закрывает владелец: try-with-resources или завершение HTTP-запроса.
 */
@Slf4j
public final class OpenedFile extends AbstractResource implements AutoCloseable {

    private final FileItem file;
    private final FileChannel channel;
    private final long size;

    private OpenedFile(FileItem file, FileChannel channel, long size) {
        this.file = file;
        this.channel = channel;
        this.size = size;
    }

    // Размер фиксируется при открытии: Content-Length и границы Range относятся к уже открытому объекту.
    public static OpenedFile open(FileItem file, Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            return new OpenedFile(file, channel, channel.size());
        } catch (IOException | RuntimeException ex) {
            channel.close();
            throw ex;
        }
    }

    /** Запись, по которой файл открыт под блокировкой: имя и MIME относятся к отданным байтам. */
    public FileItem file() {
        return file;
    }

    public FileChannel channel() {
        return channel;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (!channel.isOpen()) {
            throw new IOException("File " + file.getId() + " is already closed");
        }
        return new ChannelInputStream(channel, size);
    }

    @Override
    public long contentLength() {
        return size;
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public String getDescription() {
        return "file item " + file.getId();
    }

    // Ошибка закрытия только логируется: ответ уже сформирован, а канал после close() недействителен в любом случае.
    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ex) {
            log.warn("Не удалось закрыть файл {}", file.getId(), ex);
        }
    }
}
