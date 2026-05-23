package ru.tbcarus.photocloudserver.model.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.dto.FolderDto;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface FolderMapper {

    @Mapping(source = "parent.id", target = "parentId")
    FolderDto toDto(Folder folder);
}
