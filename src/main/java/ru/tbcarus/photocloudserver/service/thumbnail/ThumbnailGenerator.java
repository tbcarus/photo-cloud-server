package ru.tbcarus.photocloudserver.service.thumbnail;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.png.PngMetadataReader;
import com.drew.lang.ByteArrayReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.tbcarus.photocloudserver.exception.ThumbnailTooLargeException;
import ru.tbcarus.photocloudserver.exception.ThumbnailUnreadableException;
import ru.tbcarus.photocloudserver.exception.ThumbnailUnsupportedException;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * JPEG-миниатюра изображения: декодирование с прореживанием, белый фон вместо прозрачности, уменьшение
 * до длинной стороны thumbnail.max-side, поворот по EXIF Orientation. Метаданные исходника в результат не попадают.
 */
@Slf4j
@Component
public class ThumbnailGenerator {

    // Меняется при любом изменении результата для тех же байтов и настроек: входит в ETag.
    static final int ALGORITHM_VERSION = 1;

    static final String JPEG = "image/jpeg";
    static final String PNG = "image/png";
    static final String WEBP = "image/webp";
    private static final Set<String> SUPPORTED_TYPES = Set.of(JPEG, PNG, WEBP);

    private static final float JPEG_QUALITY = 0.85f;
    private static final byte[] EXIF_PREFIX = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);

    private final ThumbnailProperties properties;

    public ThumbnailGenerator(ThumbnailProperties properties) {
        this.properties = properties;
        // Плагин WebP регистрируется загрузчиком классов приложения, а не потока первого запроса.
        ImageIO.scanForPlugins();
    }

    public static boolean isSupported(String mimeType) {
        return SUPPORTED_TYPES.contains(mimeType);
    }

    public byte[] generate(Path source, String mimeType) throws IOException {
        WebpLayout webp = WEBP.equals(mimeType) ? WebpLayout.read(source) : null;
        Decoded decoded = decode(source, mimeType, webp);
        int targetWidth = decoded.width();
        int targetHeight = decoded.height();
        int longSide = Math.max(decoded.width(), decoded.height());
        if (longSide > properties.getMaxSide()) {
            double scale = (double) properties.getMaxSide() / longSide;
            targetWidth = Math.max(1, (int) Math.round(decoded.width() * scale));
            targetHeight = Math.max(1, (int) Math.round(decoded.height() * scale));
        }
        BufferedImage thumbnail = scale(onWhite(decoded.image()), targetWidth, targetHeight);
        return encode(orient(thumbnail, orientation(source, mimeType, webp)));
    }

    /** Исходник, прочитанный с прореживанием; width и height — размеры оригинала. */
    record Decoded(BufferedImage image, int width, int height) {
    }

    Decoded decode(Path source, String mimeType, WebpLayout webp) throws IOException {
        if (!isSupported(mimeType)) {
            throw new ThumbnailUnsupportedException("Thumbnail is not supported for this file type");
        }
        ImageReader reader = reader(mimeType);
        ImageInputStream input = open(source);
        try (input) {
            // ignoreMetadata: метаданные исходника не нужны и не накапливаются в памяти.
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            checkLimits(width, height, webp != null && webp.fullDecode());
            int step = subsampling(width, height);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(step, step, 0, 0);
            return new Decoded(reader.read(0, param), width, height);
        } catch (ThumbnailTooLargeException | ThumbnailUnreadableException | ThumbnailUnsupportedException e) {
            throw e;
        } catch (IIOException | EOFException | RuntimeException e) {
            // Повреждённые или обрезанные данные декодеры сообщают и IIOException/EOFException, и непроверяемыми исключениями.
            throw new ThumbnailUnreadableException("Image cannot be decoded", e);
        } finally {
            reader.dispose();
        }
    }

    // Шаг прореживания оставляет не меньше двух длин миниатюры: качество даёт последующее сглаженное уменьшение.
    int subsampling(int width, int height) {
        return Math.max(1, Math.max(width, height) / (2 * properties.getMaxSide()));
    }

    private void checkLimits(int width, int height, boolean fullDecode) {
        if (width <= 0 || height <= 0) {
            throw new ThumbnailUnreadableException("Invalid image dimensions");
        }
        long pixels = (long) width * height;
        if (pixels > properties.getMaxSourcePixels() || fullDecode && pixels > properties.getMaxFullDecodePixels()) {
            throw new ThumbnailTooLargeException("Image dimensions exceed thumbnail limits");
        }
    }

    private static ImageReader reader(String mimeType) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(mimeType);
        if (!readers.hasNext()) {
            throw new ThumbnailUnsupportedException("Thumbnail is not supported for this file type");
        }
        return readers.next();
    }

    // ImageIO выдаёт любую ошибку открытия как IIOException: отсутствие файла проверяется отдельно.
    private static ImageInputStream open(Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new NoSuchFileException(source.getFileName().toString());
        }
        ImageInputStream input = ImageIO.createImageInputStream(source.toFile());
        if (input == null) {
            throw new IOException("Image input stream is not available");
        }
        return input;
    }

    private static BufferedImage onWhite(BufferedImage image) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    // Уменьшение вдвое за шаг: билинейная интерполяция за один большой шаг пропускает пиксели и даёт рябь.
    private static BufferedImage scale(BufferedImage image, int width, int height) {
        BufferedImage current = image;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        if (current.getWidth() != width || current.getHeight() != height) {
            current = resize(current, width, height);
        }
        return current;
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /** Поворот и отражение по EXIF Orientation 1–8; 5–8 меняют местами ширину и высоту. */
    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean transposed = orientation >= 5;
        BufferedImage result = new BufferedImage(transposed ? h : w, transposed ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                switch (orientation) {
                    case 2 -> result.setRGB(w - 1 - x, y, rgb);
                    case 3 -> result.setRGB(w - 1 - x, h - 1 - y, rgb);
                    case 4 -> result.setRGB(x, h - 1 - y, rgb);
                    case 5 -> result.setRGB(y, x, rgb);
                    case 6 -> result.setRGB(h - 1 - y, x, rgb);
                    case 7 -> result.setRGB(h - 1 - y, w - 1 - x, rgb);
                    default -> result.setRGB(y, w - 1 - x, rgb);
                }
            }
        }
        return result;
    }

    // Нечитаемый EXIF не мешает миниатюре: изображение остаётся без поворота.
    private static int orientation(Path source, String mimeType, WebpLayout webp) {
        try {
            Metadata metadata = switch (mimeType) {
                case JPEG -> JpegMetadataReader.readMetadata(source.toFile(), List.of(new ExifReader()));
                case PNG -> {
                    try (InputStream input = Files.newInputStream(source)) {
                        yield PngMetadataReader.readMetadata(input);
                    }
                }
                default -> webpExif(webp);
            };
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            log.debug("EXIF-ориентация не прочитана: {}", e.getClass().getSimpleName());
        }
        return 1;
    }

    private static Metadata webpExif(WebpLayout webp) {
        Metadata metadata = new Metadata();
        byte[] exif = webp == null ? null : webp.exif();
        if (exif != null) {
            // Часть кодировщиков пишет в чанк EXIF префикс JPEG APP1 перед TIFF-заголовком.
            boolean prefixed = exif.length >= EXIF_PREFIX.length
                    && Arrays.equals(exif, 0, EXIF_PREFIX.length, EXIF_PREFIX, 0, EXIF_PREFIX.length);
            new ExifReader().extract(new ByteArrayReader(exif), metadata, prefixed ? EXIF_PREFIX.length : 0);
        }
        return metadata;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByMIMEType(JPEG).next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            // Поток в памяти: без временных файлов ImageIO.
            try (ImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output);
                // Метаданные не передаются: в результате только JFIF без EXIF и GPS.
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
