package ru.tbcarus.photocloudserver.service.thumbnail;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.tbcarus.photocloudserver.exception.ThumbnailTooLargeException;
import ru.tbcarus.photocloudserver.exception.ThumbnailUnreadableException;
import ru.tbcarus.photocloudserver.exception.ThumbnailUnsupportedException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.tbcarus.photocloudserver.service.thumbnail.ThumbnailFixtures.BLUE;
import static ru.tbcarus.photocloudserver.service.thumbnail.ThumbnailFixtures.GREEN;
import static ru.tbcarus.photocloudserver.service.thumbnail.ThumbnailFixtures.RED;
import static ru.tbcarus.photocloudserver.service.thumbnail.ThumbnailFixtures.YELLOW;
import static ru.tbcarus.photocloudserver.service.thumbnail.ThumbnailFixtures.near;

class ThumbnailGeneratorTest {

    private static final int MAX_SIDE = 320;

    @TempDir
    Path dir;

    private final ThumbnailProperties properties = properties();
    private final ThumbnailGenerator generator = new ThumbnailGenerator(properties);

    static ThumbnailProperties properties() {
        ThumbnailProperties properties = new ThumbnailProperties();
        properties.setMaxSide(MAX_SIDE);
        properties.setMaxSourcePixels(100_000_000L);
        properties.setMaxFullDecodePixels(8_000_000L);
        properties.setMaxConcurrent(4);
        properties.setCacheMaxAge(Duration.ofDays(1));
        return properties;
    }

    @ParameterizedTest
    @CsvSource({
            // горизонтальное, вертикальное, квадратное, меньше предела, ровно предел
            "1000, 500, 320, 160",
            "500, 1000, 160, 320",
            "800, 800, 320, 320",
            "100, 50, 100, 50",
            "320, 200, 320, 200"
    })
    void keepsProportionsAndDoesNotUpscale(int width, int height, int expectedWidth, int expectedHeight) throws Exception {
        BufferedImage jpeg = thumbnail(ThumbnailFixtures.jpeg(width, height), "image/jpeg");
        BufferedImage png = thumbnail(ThumbnailFixtures.png(width, height), "image/png");

        for (BufferedImage image : new BufferedImage[]{jpeg, png}) {
            assertThat(image.getWidth()).isEqualTo(expectedWidth);
            assertThat(image.getHeight()).isEqualTo(expectedHeight);
            assertCorners(image, RED, GREEN, BLUE, YELLOW);
        }
    }

    // Углы результата: какая четверть исходника (R G / B Y) оказывается в каждом углу после поворота.
    @ParameterizedTest
    @CsvSource({
            "1, 300, 150, RGBY",
            "2, 300, 150, GRYB",
            "3, 300, 150, YBGR",
            "4, 300, 150, BYRG",
            "5, 150, 300, RBGY",
            "6, 150, 300, BRYG",
            "7, 150, 300, YGBR",
            "8, 150, 300, GYRB"
    })
    void appliesExifOrientation(int orientation, int expectedWidth, int expectedHeight, String corners) throws Exception {
        byte[] source = ThumbnailFixtures.withOrientation(ThumbnailFixtures.jpeg(300, 150), orientation);

        BufferedImage image = thumbnail(source, "image/jpeg");

        assertThat(image.getWidth()).isEqualTo(expectedWidth);
        assertThat(image.getHeight()).isEqualTo(expectedHeight);
        assertCorners(image, color(corners.charAt(0)), color(corners.charAt(1)), color(corners.charAt(2)), color(corners.charAt(3)));
    }

    @Test
    void rotatedLargeImageFitsLongSide() throws Exception {
        byte[] source = ThumbnailFixtures.withOrientation(ThumbnailFixtures.jpeg(1600, 900), 6);

        BufferedImage image = thumbnail(source, "image/jpeg");

        assertThat(image.getWidth()).isEqualTo(180);
        assertThat(image.getHeight()).isEqualTo(MAX_SIDE);
    }

    @Test
    void resultIsJpegWithoutSourceMetadata() throws Exception {
        byte[] source = ThumbnailFixtures.withOrientation(ThumbnailFixtures.jpeg(400, 200), 6);
        Metadata sourceMetadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(source));
        assertThat(sourceMetadata.getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();

        byte[] result = generator.generate(write(source), "image/jpeg");

        assertThat(result[0] & 0xFF).isEqualTo(0xFF);
        assertThat(result[1] & 0xFF).isEqualTo(0xD8);
        assertThat(ImageIO.getImageReaders(ImageIO.createImageInputStream(new ByteArrayInputStream(result))).next()
                .getFormatName()).isEqualToIgnoringCase("jpeg");
        Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(result));
        assertThat(metadata.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
        assertThat(metadata.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(new String(result, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    @Test
    void sourceFileIsNotChanged() throws Exception {
        byte[] source = ThumbnailFixtures.withOrientation(ThumbnailFixtures.jpeg(400, 200), 6);
        Path file = write(source);

        generator.generate(file, "image/jpeg");

        assertThat(Files.readAllBytes(file)).isEqualTo(source);
    }

    @Test
    void transparentPngGetsWhiteBackground() throws Exception {
        BufferedImage image = thumbnail(ThumbnailFixtures.transparentPng(200, 200), "image/png");

        assertThat(near(image.getRGB(2, 2), Color.WHITE)).isTrue();
        assertThat(near(image.getRGB(100, 100), RED)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lossy.webp", "lossless.webp", "simple-lossy.webp", "simple-lossless.webp"})
    void decodesStaticWebp(String name) throws Exception {
        BufferedImage image = thumbnail(ThumbnailFixtures.resource(name), "image/webp");

        assertThat(image.getWidth()).isEqualTo(64);
        assertThat(image.getHeight()).isEqualTo(48);
        assertCorners(image, RED, GREEN, BLUE, BLUE);
    }

    // В WebP с прозрачностью нижняя половина прозрачна.
    @ParameterizedTest
    @ValueSource(strings = {"lossy-alpha.webp", "lossless-alpha.webp"})
    void transparentWebpGetsWhiteBackground(String name) throws Exception {
        BufferedImage image = thumbnail(ThumbnailFixtures.resource(name), "image/webp");

        assertCorners(image, RED, GREEN, Color.WHITE, Color.WHITE);
    }

    @Test
    void largeLossyWebpIsDecodedSubsampled() throws Exception {
        Path file = write(ThumbnailFixtures.resource("large-lossy.webp"));

        ThumbnailGenerator.Decoded decoded = generator.decode(file, "image/webp", WebpLayout.read(file));

        assertThat(decoded.width()).isEqualTo(6000);
        assertThat(decoded.height()).isEqualTo(4000);
        // Шаг 6000 / 640 = 9: в памяти кадр 667×445 вместо 24 Мп.
        assertThat(decoded.image().getWidth()).isEqualTo(667);
        assertThat(decoded.image().getHeight()).isEqualTo(445);
        BufferedImage thumbnail = ThumbnailFixtures.read(generator.generate(file, "image/webp"));
        assertThat(thumbnail.getWidth()).isEqualTo(320);
        assertThat(thumbnail.getHeight()).isEqualTo(213);
    }

    @Test
    void largeJpegAndPngAreDecodedSubsampled() throws Exception {
        BufferedImage gray = new BufferedImage(4000, 3000, BufferedImage.TYPE_BYTE_GRAY);
        Path jpeg = write(encode(gray, "jpeg"));
        Path png = write(encode(gray, "png"));

        for (Object[] source : new Object[][]{{jpeg, "image/jpeg"}, {png, "image/png"}}) {
            ThumbnailGenerator.Decoded decoded = generator.decode((Path) source[0], (String) source[1], null);

            assertThat(decoded.width()).isEqualTo(4000);
            // Шаг 4000 / 640 = 6.
            assertThat(decoded.image().getWidth()).isEqualTo(667);
            assertThat(decoded.image().getHeight()).isEqualTo(500);
        }
    }

    @Test
    void subsamplingKeepsAtLeastTwiceTheThumbnailSize() {
        assertThat(generator.subsampling(100, 100)).isEqualTo(1);
        assertThat(generator.subsampling(1279, 100)).isEqualTo(1);
        assertThat(generator.subsampling(1280, 100)).isEqualTo(2);
        assertThat(generator.subsampling(8660, 5773)).isEqualTo(13);
    }

    @Test
    void sourceOverPixelLimitIsRejectedBeforeDecoding() throws Exception {
        // 100000 × 100000 = 10^10 пикселей: произведение не помещается в int.
        Path huge = write(ThumbnailFixtures.pngHeader(100_000, 100_000));

        assertThatThrownBy(() -> generator.generate(huge, "image/png")).isInstanceOf(ThumbnailTooLargeException.class);
    }

    @Test
    void configuredSourceLimitApplies() throws Exception {
        properties.setMaxSourcePixels(1000);

        assertThatThrownBy(() -> generator.generate(write(ThumbnailFixtures.jpeg(40, 30)), "image/jpeg"))
                .isInstanceOf(ThumbnailTooLargeException.class);
    }

    // Lossless и WebP с прозрачностью декодер читает целиком: для них свой предел, lossy без прозрачности под него не попадает.
    @Test
    void fullDecodeWebpHasOwnLimit() throws Exception {
        properties.setMaxFullDecodePixels(1000);

        for (String name : new String[]{"lossless.webp", "lossless-alpha.webp", "lossy-alpha.webp", "simple-lossless.webp"}) {
            Path file = write(ThumbnailFixtures.resource(name));
            assertThatThrownBy(() -> generator.generate(file, "image/webp")).isInstanceOf(ThumbnailTooLargeException.class);
        }
        assertThat(generator.generate(write(ThumbnailFixtures.resource("lossy.webp")), "image/webp")).isNotEmpty();
    }

    @Test
    void zeroDimensionsAreUnreadable() throws Exception {
        assertThatThrownBy(() -> generator.generate(write(ThumbnailFixtures.pngHeader(0, 10)), "image/png"))
                .isInstanceOf(ThumbnailUnreadableException.class);
    }

    @Test
    void corruptedImagesAreUnreadable() throws Exception {
        byte[] webp = ThumbnailFixtures.resource("simple-lossy.webp");
        for (int i = 30; i < webp.length; i++) {
            webp[i] = (byte) 0xA5;
        }
        byte[] truncatedPng = java.util.Arrays.copyOf(ThumbnailFixtures.png(100, 100), 60);

        assertThatThrownBy(() -> generator.generate(write(ThumbnailFixtures.corruptedJpeg()), "image/jpeg"))
                .isInstanceOf(ThumbnailUnreadableException.class);
        assertThatThrownBy(() -> generator.generate(write(webp), "image/webp"))
                .isInstanceOf(ThumbnailUnreadableException.class);
        assertThatThrownBy(() -> generator.generate(write(truncatedPng), "image/png"))
                .isInstanceOf(ThumbnailUnreadableException.class);
        assertThatThrownBy(() -> generator.generate(write("not an image".getBytes()), "image/webp"))
                .isInstanceOf(ThumbnailUnreadableException.class);
    }

    @Test
    void unsupportedTypesAreRejectedWithoutDecoding() throws Exception {
        assertThatThrownBy(() -> generator.generate(write(ThumbnailFixtures.heic()), "image/heic"))
                .isInstanceOf(ThumbnailUnsupportedException.class);
        assertThatThrownBy(() -> generator.generate(write(ThumbnailFixtures.gif()), "image/gif"))
                .isInstanceOf(ThumbnailUnsupportedException.class);
        assertThatThrownBy(() -> generator.generate(write(ThumbnailFixtures.pdf()), "application/pdf"))
                .isInstanceOf(ThumbnailUnsupportedException.class);
        assertThatThrownBy(() -> generator.generate(write(ThumbnailFixtures.animatedWebpHeader()), "image/webp"))
                .isInstanceOf(ThumbnailUnsupportedException.class);
    }

    @Test
    void missingSourceIsReportedAsMissingFile() {
        assertThatThrownBy(() -> generator.generate(dir.resolve("absent.jpg"), "image/jpeg"))
                .isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> generator.generate(dir.resolve("absent.webp"), "image/webp"))
                .isInstanceOf(NoSuchFileException.class);
    }

    private BufferedImage thumbnail(byte[] source, String mimeType) throws Exception {
        return ThumbnailFixtures.read(generator.generate(write(source), mimeType));
    }

    private Path write(byte[] bytes) throws Exception {
        return Files.write(Files.createTempFile(dir, "source", ".bin"), bytes);
    }

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    // Точки взяты внутри четвертей, в стороне от границ, размытых JPEG и уменьшением.
    private static void assertCorners(BufferedImage image, Color topLeft, Color topRight, Color bottomLeft, Color bottomRight) {
        int w = image.getWidth();
        int h = image.getHeight();
        int x0 = w / 8;
        int x1 = w - 1 - w / 8;
        int y0 = h / 8;
        int y1 = h - 1 - h / 8;
        assertThat(near(image.getRGB(x0, y0), topLeft)).as("левый верхний").isTrue();
        assertThat(near(image.getRGB(x1, y0), topRight)).as("правый верхний").isTrue();
        assertThat(near(image.getRGB(x0, y1), bottomLeft)).as("левый нижний").isTrue();
        assertThat(near(image.getRGB(x1, y1), bottomRight)).as("правый нижний").isTrue();
    }

    private static Color color(char code) {
        return switch (code) {
            case 'R' -> RED;
            case 'G' -> GREEN;
            case 'B' -> BLUE;
            default -> YELLOW;
        };
    }
}
