package ru.tbcarus.photocloudserver.service.thumbnail;

import javax.imageio.stream.ImageInputStreamImpl;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * ImageInputStream по уже открытому каналу: произвольный доступ, как у файлового потока ImageIO, без кеша во временных
 * файлах. Чтение позиционное и не сдвигает позицию канала; закрытие потока канал не закрывает.
 */
final class ChannelImageInputStream extends ImageInputStreamImpl {

    private final FileChannel channel;

    ChannelImageInputStream(FileChannel channel) {
        this.channel = channel;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        checkClosed();
        bitOffset = 0;
        if (length == 0) {
            return 0;
        }
        int read = channel.read(ByteBuffer.wrap(buffer, offset, length), streamPos);
        if (read > 0) {
            streamPos += read;
        }
        return read;
    }

    @Override
    public long length() {
        try {
            return channel.size();
        } catch (IOException e) {
            return -1;
        }
    }
}
