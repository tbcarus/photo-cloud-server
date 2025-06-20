package ru.tbcarus.photocloudserver.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.tbcarus.photocloudserver.model.MediaType;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaFileDto {
    private Long id;
    private String originalFilename;
    private String storageFilename;
    private String mimeType;
    private Long size;
    private MediaType type;
    private LocalDateTime createdAt;
}
