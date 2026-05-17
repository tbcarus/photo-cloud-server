package ru.tbcarus.photocloudserver.model.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import ru.tbcarus.photocloudserver.model.MediaFile;
import ru.tbcarus.photocloudserver.model.dto.MediaFileDto;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MediaFileMapper {

    MediaFileDto toDto(MediaFile mediaFile);
}
