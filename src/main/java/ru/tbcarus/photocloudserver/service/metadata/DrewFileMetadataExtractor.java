package ru.tbcarus.photocloudserver.service.metadata;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.lang.Rational;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DrewFileMetadataExtractor implements FileMetadataExtractor {

    // Стандарт EXIF — "yyyy:MM:dd HH:mm:ss"; как и metadata-extractor, принимаем разделители даты "-"/".", "T"
    // и время без секунд. Дробная часть и пояс в конце строки допускаются, пояс игнорируется.
    private static final Pattern EXIF_DATE_TIME = Pattern.compile(
            "(\\d{4})[:.-](\\d{2})[:.-](\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.(\\d+))?(?:\\s*(?:Z|[+-]\\d{2}:?\\d{2}))?");
    private static final Pattern SUBSECONDS = Pattern.compile("\\d+");

    @Override
    public ExtractedFileMetadata extract(Path file, String mimeType) {
        if (mimeType == null || !mimeType.toLowerCase().startsWith("image/")) {
            return ExtractedFileMetadata.builder().build();
        }
        try {
            // Читаем metadata из файла на диске, чтобы upload больше не держал весь файл в byte[] в памяти.
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
            return ExtractedFileMetadata.builder()
                    .capturedAt(capturedAt(metadata))
                    .width(width(metadata))
                    .height(height(metadata))
                    .cameraMake(tag(metadata, ExifIFD0Directory.class, ExifIFD0Directory.TAG_MAKE))
                    .cameraModel(tag(metadata, ExifIFD0Directory.class, ExifIFD0Directory.TAG_MODEL))
                    .lensModel(tag(metadata, ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_LENS_MODEL))
                    .exposureTime(tag(metadata, ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_EXPOSURE_TIME))
                    .fNumber(rational(metadata, ExifSubIFDDirectory.TAG_FNUMBER))
                    .iso(integer(metadata, ExifSubIFDDirectory.TAG_ISO_EQUIVALENT))
                    .focalLength(rational(metadata, ExifSubIFDDirectory.TAG_FOCAL_LENGTH))
                    .latitude(latitude(metadata))
                    .longitude(longitude(metadata))
                    .build();
        } catch (Exception ex) {
            log.warn("Не удалось извлечь metadata файла: {}", ex.getMessage());
            return ExtractedFileMetadata.builder().build();
        }
    }

    private LocalDateTime capturedAt(Metadata metadata) {
        ExifSubIFDDirectory directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (directory == null) {
            return null;
        }
        LocalDateTime original = exifLocalDateTime(directory,
                ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, ExifSubIFDDirectory.TAG_SUBSECOND_TIME_ORIGINAL);
        return original != null ? original : exifLocalDateTime(directory,
                ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED, ExifSubIFDDirectory.TAG_SUBSECOND_TIME_DIGITIZED);
    }

    // EXIF хранит местное время камеры строкой без пояса. Берём календарные дату и время как записаны:
    // getDateOriginal() читает строку как GMT (или по OffsetTime*), и перевод в пояс сервера сдвигал дату вплоть до года.
    // Свой строгий разбор вместо lenient SimpleDateFormat библиотеки, чтобы заглушка "0000:00:00 00:00:00" не стала датой.
    private LocalDateTime exifLocalDateTime(ExifSubIFDDirectory directory, int dateTimeTag, int subsecondTag) {
        String value = directory.getString(dateTimeTag);
        Matcher matcher = value == null ? null : EXIF_DATE_TIME.matcher(value.trim());
        if (matcher == null || !matcher.matches()) {
            return null;
        }
        try {
            LocalDateTime dateTime = LocalDateTime.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)),
                    Integer.parseInt(matcher.group(5)),
                    matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6)));
            String fraction = matcher.group(7) != null ? matcher.group(7) : directory.getString(subsecondTag);
            if (fraction == null || !SUBSECONDS.matcher(fraction.trim()).matches()) {
                return dateTime;
            }
            // Дробная часть секунды до микросекунд — точности TIMESTAMP в PostgreSQL.
            String micros = (fraction.trim() + "00000").substring(0, 6);
            return dateTime.withNano(Integer.parseInt(micros) * 1000);
        } catch (DateTimeException ex) {
            // Несуществующая календарная дата (нулевой месяц, 31 февраля и т. п.) означает отсутствие пригодной даты.
            return null;
        }
    }

    private Integer width(Metadata metadata) {
        JpegDirectory jpegDirectory = metadata.getFirstDirectoryOfType(JpegDirectory.class);
        if (jpegDirectory != null) {
            return jpegDirectory.getInteger(JpegDirectory.TAG_IMAGE_WIDTH);
        }
        PngDirectory pngDirectory = metadata.getFirstDirectoryOfType(PngDirectory.class);
        return pngDirectory == null ? null : pngDirectory.getInteger(PngDirectory.TAG_IMAGE_WIDTH);
    }

    private Integer height(Metadata metadata) {
        JpegDirectory jpegDirectory = metadata.getFirstDirectoryOfType(JpegDirectory.class);
        if (jpegDirectory != null) {
            return jpegDirectory.getInteger(JpegDirectory.TAG_IMAGE_HEIGHT);
        }
        PngDirectory pngDirectory = metadata.getFirstDirectoryOfType(PngDirectory.class);
        return pngDirectory == null ? null : pngDirectory.getInteger(PngDirectory.TAG_IMAGE_HEIGHT);
    }

    private <T extends com.drew.metadata.Directory> String tag(Metadata metadata, Class<T> type, int tag) {
        T directory = metadata.getFirstDirectoryOfType(type);
        return directory == null ? null : directory.getString(tag);
    }

    private Integer integer(Metadata metadata, int tag) {
        ExifSubIFDDirectory directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        return directory == null ? null : directory.getInteger(tag);
    }

    private BigDecimal rational(Metadata metadata, int tag) {
        ExifSubIFDDirectory directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (directory == null) {
            return null;
        }
        Rational rational = directory.getRational(tag);
        return rational == null ? null : BigDecimal.valueOf(rational.doubleValue());
    }

    private BigDecimal latitude(Metadata metadata) {
        GeoLocation geoLocation = geoLocation(metadata);
        return geoLocation == null ? null : BigDecimal.valueOf(geoLocation.getLatitude());
    }

    private BigDecimal longitude(Metadata metadata) {
        GeoLocation geoLocation = geoLocation(metadata);
        return geoLocation == null ? null : BigDecimal.valueOf(geoLocation.getLongitude());
    }

    private GeoLocation geoLocation(Metadata metadata) {
        GpsDirectory directory = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        return directory == null ? null : directory.getGeoLocation();
    }
}
