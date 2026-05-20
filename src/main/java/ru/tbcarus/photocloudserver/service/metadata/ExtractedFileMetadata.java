package ru.tbcarus.photocloudserver.service.metadata;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class ExtractedFileMetadata {
    LocalDateTime capturedAt;
    Integer width;
    Integer height;
    Integer durationSec;
    String cameraMake;
    String cameraModel;
    String lensModel;
    String exposureTime;
    BigDecimal fNumber;
    Integer iso;
    BigDecimal focalLength;
    BigDecimal latitude;
    BigDecimal longitude;

    public boolean hasMetadataFields() {
        return width != null
                || height != null
                || durationSec != null
                || cameraMake != null
                || cameraModel != null
                || lensModel != null
                || exposureTime != null
                || fNumber != null
                || iso != null
                || focalLength != null
                || latitude != null
                || longitude != null;
    }
}
