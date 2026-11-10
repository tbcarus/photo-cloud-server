package ru.tbcarus.photocloudserver.service.thumbnail;

import ru.tbcarus.photocloudserver.exception.ThumbnailUnreadableException;
import ru.tbcarus.photocloudserver.exception.ThumbnailUnsupportedException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Разновидность WebP по RIFF-заголовкам, без декодирования. Нужна до чтения пикселей: декодер TwelveMonkeys
 * уменьшает при чтении только lossy VP8 без прозрачности, а для VP8L и сжатого ALPH выделяет кадр полного размера.
 * Заодно находит чанк EXIF, чтобы прочитать ориентацию без загрузки в память сжатого кадра.
 */
record WebpLayout(boolean fullDecode, byte[] exif) {

    private static final int VP8X_ANIMATION = 0x02;
    private static final int VP8X_ALPHA = 0x10;
    private static final int MAX_CHUNKS = 64;
    // EXIF больше этого не читается: ориентация тогда не учитывается.
    private static final int MAX_EXIF_LENGTH = 1024 * 1024;

    static WebpLayout read(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            return read(channel);
        }
    }

    // Канал не закрывается: им владеет вызывающий код.
    static WebpLayout read(SeekableByteChannel channel) throws IOException {
        long size = channel.size();
        ByteBuffer header = readAt(channel, 0, 16);
        if (header == null || !"RIFF".equals(fourCc(header, 0)) || !"WEBP".equals(fourCc(header, 8))) {
            throw new ThumbnailUnreadableException("Invalid WebP header");
        }
        // Простой формат содержит только кадр: ни прозрачности, ни EXIF.
        switch (fourCc(header, 12)) {
            case "VP8 ":
                return new WebpLayout(false, null);
            case "VP8L":
                return new WebpLayout(true, null);
            case "VP8X":
                break;
            default:
                throw new ThumbnailUnreadableException("Invalid WebP header");
        }
        ByteBuffer flags = readAt(channel, 20, 1);
        if (flags == null) {
            throw new ThumbnailUnreadableException("Invalid WebP header");
        }
        if ((flags.get(0) & VP8X_ANIMATION) != 0) {
            throw new ThumbnailUnsupportedException("Animated WebP is not supported");
        }
        Boolean fullDecode = (flags.get(0) & VP8X_ALPHA) != 0 ? Boolean.TRUE : null;
        byte[] exif = null;
        long position = 12;
        for (int i = 0; i < MAX_CHUNKS && position + 8 <= size; i++) {
            ByteBuffer chunk = readAt(channel, position, 8);
            if (chunk == null) {
                break;
            }
            long length = Integer.toUnsignedLong(chunk.getInt(4));
            switch (fourCc(chunk, 0)) {
                case "VP8 " -> fullDecode = fullDecode != null ? fullDecode : Boolean.FALSE;
                case "VP8L", "ALPH" -> fullDecode = Boolean.TRUE;
                case "EXIF" -> {
                    if (length <= MAX_EXIF_LENGTH) {
                        ByteBuffer data = readAt(channel, position + 8, (int) length);
                        exif = data == null ? null : data.array();
                    }
                }
                default -> {
                }
            }
            position += 8 + length + (length & 1);
        }
        if (fullDecode == null) {
            throw new ThumbnailUnreadableException("WebP image data not found");
        }
        return new WebpLayout(fullDecode, exif);
    }

    private static ByteBuffer readAt(SeekableByteChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(position);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                return null;
            }
        }
        return buffer.flip();
    }

    private static String fourCc(ByteBuffer buffer, int offset) {
        byte[] bytes = new byte[4];
        buffer.get(offset, bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
