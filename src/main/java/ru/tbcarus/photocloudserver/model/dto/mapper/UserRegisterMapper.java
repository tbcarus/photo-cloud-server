package ru.tbcarus.photocloudserver.model.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import ru.tbcarus.photocloudserver.model.Role;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.RegisterRequest;

import java.util.Set;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, imports = {Set.class, Role.class})
public interface UserRegisterMapper {

    User toUser(RegisterRequest dto);

    RegisterRequest toUserRegisterDto(User user);
}