package ru.tbcarus.photocloudserver.model.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import ru.tbcarus.photocloudserver.model.MediaFile;
import ru.tbcarus.photocloudserver.model.Role;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.MediaFileDto;
import ru.tbcarus.photocloudserver.model.dto.RegisterRequest;

import java.util.Set;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MediaFileMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    MediaFile toEntity(MediaFileDto dto);

    MediaFileDto toDto(MediaFile mediaFile);
}