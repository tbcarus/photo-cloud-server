package ru.tbcarus.photocloudserver.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileMetadataDto {
    private Integer width;
    private Integer height;
    private Integer durationSec;
    private String cameraMake;
    private String cameraModel;
    private String lensModel;
    private String exposureTime;
    @JsonProperty("fNumber")
    private BigDecimal fNumber;
    private Integer iso;
    private BigDecimal focalLength;
    private BigDecimal latitude;
    private BigDecimal longitude;
}
