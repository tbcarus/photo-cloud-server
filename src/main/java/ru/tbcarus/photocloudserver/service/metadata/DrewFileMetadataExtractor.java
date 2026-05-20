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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Slf4j
@Component
public class DrewFileMetadataExtractor implements FileMetadataExtractor {

    @Override
    public ExtractedFileMetadata extract(byte[] fileBytes, String mimeType) {
        if (mimeType == null || !mimeType.toLowerCase().startsWith("image/")) {
            return ExtractedFileMetadata.builder().build();
        }
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(fileBytes));
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
        Date date = directory.getDateOriginal();
        if (date == null) {
            date = directory.getDateDigitized();
        }
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
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
