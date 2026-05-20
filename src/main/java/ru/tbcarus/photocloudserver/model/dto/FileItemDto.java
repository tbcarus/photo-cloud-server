package ru.tbcarus.photocloudserver.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.tbcarus.photocloudserver.model.FileType;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileItemDto {
    private Long id;
    private Long folderId;
    private String originalFilename;
    private String mimeType;
    private Long size;
    private String checksum;
    private FileType fileType;
    private LocalDateTime capturedAt;
    private LocalDateTime uploadedAt;
    private LocalDateTime deletedAt;
    private FileMetadataDto metadata;
}
