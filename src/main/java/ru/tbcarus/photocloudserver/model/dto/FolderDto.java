package ru.tbcarus.photocloudserver.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.tbcarus.photocloudserver.model.FolderType;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FolderDto {
    private Long id;
    private Long parentId;
    private String name;
    private FolderType folderType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
