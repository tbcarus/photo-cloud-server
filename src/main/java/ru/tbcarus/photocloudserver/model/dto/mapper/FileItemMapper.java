package ru.tbcarus.photocloudserver.model.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.FileMetadata;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;
import ru.tbcarus.photocloudserver.model.dto.FileMetadataDto;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface FileItemMapper {

    @Mapping(source = "folder.id", target = "folderId")
    @Mapping(source = "originalName", target = "originalFilename")
    @Mapping(source = "storedObject.detectedMimeType", target = "mimeType")
    @Mapping(source = "storedObject.size", target = "size")
    @Mapping(source = "storedObject.checksum", target = "checksum")
    @Mapping(source = "storedObject.fileType", target = "fileType")
    FileItemDto toDto(FileItem fileItem);

    @Mapping(source = "FNumber", target = "fNumber")
    FileMetadataDto toDto(FileMetadata metadata);
}
