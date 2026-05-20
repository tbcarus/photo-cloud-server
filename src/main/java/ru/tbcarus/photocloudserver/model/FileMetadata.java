package ru.tbcarus.photocloudserver.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "file_metadata")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_item_id", nullable = false, unique = true)
    private FileItem fileItem;

    private Integer width;
    private Integer height;
    private Integer durationSec;
    private String cameraMake;
    private String cameraModel;
    private String lensModel;
    private String exposureTime;
    private BigDecimal fNumber;
    private Integer iso;
    private BigDecimal focalLength;
    private BigDecimal latitude;
    private BigDecimal longitude;
}
