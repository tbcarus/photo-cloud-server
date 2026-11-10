package ru.tbcarus.photocloudserver.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Поток по уже открытому каналу с собственной позицией: несколько потоков читают один канал независимо,
 * skip не читает данные. Закрытие потока канал не закрывает — им владеет {@link OpenedFile}.
 */
public final class ChannelInputStream extends InputStream {

    private final FileChannel channel;
    private final long size;
    private long position;

    public ChannelInputStream(FileChannel channel, long size) {
        this.channel = channel;
        this.size = size;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        long remaining = size - position;
        if (remaining <= 0) {
            return -1;
        }
        int read = channel.read(ByteBuffer.wrap(buffer, offset, (int) Math.min(length, remaining)), position);
        if (read > 0) {
            position += read;
        }
        return read;
    }

    @Override
    public long skip(long count) {
        long skipped = Math.max(0, Math.min(count, size - position));
        position += skipped;
        return skipped;
    }

    @Override
    public int available() {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, size - position));
    }
}
