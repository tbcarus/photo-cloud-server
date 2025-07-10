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
public class MediaFileChecksumDto {
    private String originalFilename;
    private String checksum;
}
