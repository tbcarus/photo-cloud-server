package ru.tbcarus.photocloudserver.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.tbcarus.photocloudserver.model.MediaType;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaFileResponse {
    private Long id;
    private String originalFilename;
    private String mimeType;
    private Long size;
    private MediaType type;
    private String checksum;
    private Long createdAt;
    private Long uploadedAt;
    private String url;
    private String thumbnailUrl;
}
