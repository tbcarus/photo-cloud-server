package ru.tbcarus.photocloudserver.model.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import ru.tbcarus.photocloudserver.model.MediaFile;
import ru.tbcarus.photocloudserver.model.Role;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.MediaFileDto;
import ru.tbcarus.photocloudserver.model.dto.MediaFileResponse;
import ru.tbcarus.photocloudserver.model.dto.RegisterRequest;

import java.time.ZoneOffset;
import java.util.Set;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, imports = {ZoneOffset.class})
public interface MediaFileMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "uploadedAt", ignore = true)
    MediaFile toEntity(MediaFileDto dto);

    MediaFileDto toDto(MediaFile mediaFile);

    @Mapping(target = "createdAt", expression = "java(mediaFile.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli())")
    @Mapping(target = "uploadedAt", expression = "java(mediaFile.getUploadedAt().toInstant(ZoneOffset.UTC).toEpochMilli())")
    @Mapping(target = "url", ignore = true)
    @Mapping(target = "thumbnailUrl", ignore = true)
    MediaFileResponse toResponse(MediaFile mediaFile);
}