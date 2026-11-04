package ru.tbcarus.photocloudserver.service.thumbnail;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Изображения для тестов миниатюр. WebP-файлы в test resources созданы кодировщиком Chrome (canvas.toDataURL):
 * верхняя левая четверть красная, верхняя правая зелёная, низ — синий или прозрачный; ImageIO WebP не пишет.
 */
public final class ThumbnailFixtures {

    public static final Color RED = new Color(255, 0, 0);
    public static final Color GREEN = new Color(0, 255, 0);
    public static final Color BLUE = new Color(0, 0, 255);
    public static final Color YELLOW = new Color(255, 255, 0);

    private ThumbnailFixtures() {
    }

    /** Четверти: левая верхняя красная, правая верхняя зелёная, левая нижняя синяя, правая нижняя жёлтая. */
    public static BufferedImage quadrants(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(RED);
        graphics.fillRect(0, 0, width / 2, height / 2);
        graphics.setColor(GREEN);
        graphics.fillRect(width / 2, 0, width - width / 2, height / 2);
        graphics.setColor(BLUE);
        graphics.fillRect(0, height / 2, width / 2, height - height / 2);
        graphics.setColor(YELLOW);
        graphics.fillRect(width / 2, height / 2, width - width / 2, height - height / 2);
        graphics.dispose();
        return image;
    }

    public static byte[] jpeg(int width, int height) {
        return write(quadrants(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    public static byte[] png(int width, int height) {
        return write(quadrants(width, height, BufferedImage.TYPE_INT_RGB), "png");
    }

    /** PNG: прозрачный фон, красный квадрат в центре. */
    public static byte[] transparentPng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(RED);
        graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        return write(image, "png");
    }

    public static byte[] gif() {
        return write(quadrants(16, 16, BufferedImage.TYPE_INT_RGB), "gif");
    }

    // Tika распознаёт HEIC по ftyp-боксу; пикселей нет, декодировать нечего.
    public static byte[] heic() {
        ByteBuffer buffer = ByteBuffer.allocate(32);
        buffer.putInt(24).put("ftypheic".getBytes(StandardCharsets.US_ASCII)).putInt(0)
                .put("mif1heic".getBytes(StandardCharsets.US_ASCII));
        return buffer.array();
    }

    public static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }

    /** Заголовок JPEG и мусор вместо данных: Tika определяет JPEG, декодер отказывает. */
    public static byte[] corruptedJpeg() {
        byte[] valid = jpeg(64, 48);
        byte[] corrupted = valid.clone();
        for (int i = 200; i < corrupted.length - 2; i++) {
            corrupted[i] = (byte) 0x5A;
        }
        return corrupted;
    }

    /** PNG только с заголовком IHDR: ImageIO читает размеры без пикселей. */
    public static byte[] pngHeader(int width, int height) {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        writePngChunk(png, "IHDR", ihdr.array());
        writePngChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    /** JPEG с APP1 Exif, где IFD0 содержит только Orientation и GPS-указатель на пустой GPS IFD. */
    public static byte[] withOrientation(byte[] jpeg, int orientation) {
        ByteBuffer tiff = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 2);
        // Orientation: SHORT, значение хранится в самой записи.
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        // GPS IFD с широтой: проверка, что геоданные не попадают в миниатюру.
        int gpsOffset = 8 + 2 + 12 * 2 + 4;
        tiff.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsOffset);
        tiff.putInt(0);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0001).putShort((short) 2).putInt(2).put(new byte[]{'N', 0, 0, 0});
        tiff.putInt(0);
        byte[] tiffBytes = new byte[tiff.position()];
        tiff.flip().get(tiffBytes);

        // Сегмент вставляется после JFIF APP0, который пишет ImageIO.
        int app0Length = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
        int insertAt = 4 + app0Length;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(jpeg, 0, insertAt);
        result.write(0xFF);
        result.write(0xE1);
        int length = 2 + 6 + tiffBytes.length;
        result.write(length >> 8);
        result.write(length & 0xFF);
        result.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        result.writeBytes(tiffBytes);
        result.write(jpeg, insertAt, jpeg.length - insertAt);
        return result.toByteArray();
    }

    /** WebP с флагом анимации в VP8X: до декодирования не доходит. */
    public static byte[] animatedWebpHeader() {
        ByteBuffer buffer = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(22).put("WEBP".getBytes(StandardCharsets.US_ASCII));
        buffer.put("VP8X".getBytes(StandardCharsets.US_ASCII)).putInt(10).put((byte) 0x02).put(new byte[3]);
        // Размеры кадра минус один, по 3 байта.
        buffer.put(new byte[]{63, 0, 0, 47, 0, 0});
        return buffer.array();
    }

    public static byte[] resource(String name) {
        try (InputStream input = ThumbnailFixtures.class.getResourceAsStream("/thumbnail/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("No test resource " + name);
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static BufferedImage read(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IllegalArgumentException("Not an image");
            }
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Цвет близок к ожидаемому с допуском JPEG-сжатия. */
    public static boolean near(int rgb, Color expected) {
        Color actual = new Color(rgb);
        return Math.abs(actual.getRed() - expected.getRed()) < 60
                && Math.abs(actual.getGreen() - expected.getGreen()) < 60
                && Math.abs(actual.getBlue() - expected.getBlue()) < 60;
    }

    private static byte[] write(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("No writer for " + format);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writePngChunk(ByteArrayOutputStream png, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        png.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        png.writeBytes(typeBytes);
        png.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        png.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
