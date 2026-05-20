package ru.tbcarus.photocloudserver.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileChecksumDto {
    private Long id;
    private String originalFilename;
    private String checksum;
}
