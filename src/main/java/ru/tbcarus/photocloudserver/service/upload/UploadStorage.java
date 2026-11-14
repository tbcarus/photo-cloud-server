package ru.tbcarus.photocloudserver.service.upload;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.tbcarus.photocloudserver.exception.FileSizeLimitExceededException;
import ru.tbcarus.photocloudserver.model.UploadSession;
import ru.tbcarus.photocloudserver.service.storage.LibraryStorage;
import ru.tbcarus.photocloudserver.service.storage.StoragePathResolver;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UploadStorage {
    private final StoragePathResolver paths;
    private final LibraryStorage library;

    public Path data(UUID id) { return directory().resolve(id + ".data"); }
    public Path chunk(UUID id) { return directory().resolve(id + ".chunk"); }
    private Path directory() { return paths.rootPath().resolve(".upload-sessions"); }

    public void prepare() throws IOException {
        Files.createDirectories(directory());
        library.ensureInsideStorage(directory());
        if (Files.isSymbolicLink(directory())) throw new IOException("Invalid upload directory");
    }

    private void check(Path path) throws IOException {
        library.ensureInsideStorage(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid upload file");
        }
    }

    // Лишний хвост не подтверждён в БД. Недостающие подтверждённые байты восстановить нельзя.
    public boolean reconcile(UploadSession session) throws IOException {
        prepare();
        Path data = data(session.getId());
        check(data);
        if (!Files.exists(data) && session.getConfirmedOffset() != 0) return false;
        try (FileChannel channel = FileChannel.open(data, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            if (channel.size() < session.getConfirmedOffset()) return false;
            if (channel.size() > session.getConfirmedOffset()) {
                channel.truncate(session.getConfirmedOffset());
                channel.force(true);
            }
        }
        return true;
    }

    public long receive(UUID id, InputStream input, long contentLength, long maximum) throws IOException {
        prepare();
        check(chunk(id));
        Files.deleteIfExists(chunk(id));
        if (Files.getFileStore(directory()).getUsableSpace() < maximum * 2) {
            throw new IOException("Insufficient space for chunk and assembly");
        }
        try (FileChannel output = FileChannel.open(chunk(id), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                total += read;
                if (total > maximum) throw new FileSizeLimitExceededException(maximum);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                while (bytes.hasRemaining()) output.write(bytes);
            }
            if (total == 0 || (contentLength >= 0 && total != contentLength)) {
                throw new IOException("Incomplete or empty chunk");
            }
            output.force(true);
            return total;
        }
    }

    public void append(UploadSession session) throws IOException {
        check(data(session.getId()));
        check(chunk(session.getId()));
        try (FileChannel output = FileChannel.open(data(session.getId()), StandardOpenOption.WRITE);
             FileChannel input = FileChannel.open(chunk(session.getId()), StandardOpenOption.READ)) {
            output.position(session.getConfirmedOffset());
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (input.read(buffer) != -1) {
                buffer.flip();
                while (buffer.hasRemaining()) output.write(buffer);
                buffer.clear();
            }
            output.force(true);
        }
    }

    public void discardChunk(UUID id) throws IOException {
        check(chunk(id));
        Files.deleteIfExists(chunk(id));
    }

    public void clean(UUID id) throws IOException {
        prepare();
        discardChunk(id);
        check(data(id));
        Files.deleteIfExists(data(id));
    }

    public void publish(UUID id, Path destination) throws IOException {
        library.ensureInsideStorage(destination);
        check(data(id));
        Files.createLink(destination, data(id));
    }

    public void undoPublication(UploadSession session) throws IOException {
        Path destination = Path.of(session.getPublicationPath());
        if (!destination.normalize().startsWith(paths.rootPath())) throw new IOException("Invalid publication path");
        library.ensureInsideStorage(destination);
        check(data(session.getId()));
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.exists(data(session.getId()))) throw new IOException("Missing publication source");
            // Чужое занятое имя могло отклонить createLink: оно не принадлежит этой операции.
            if (Files.isSameFile(destination, data(session.getId()))) Files.delete(destination);
        }
    }
}
