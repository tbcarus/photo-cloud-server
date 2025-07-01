package ru.tbcarus.photocloudserver.model.dto;

import ru.tbcarus.photocloudserver.model.Role;

import java.time.LocalDateTime;
import java.util.Set;

public record UserDto(
        Long id,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        boolean banned,
        Set<Role> roles,
        LocalDateTime createAt,
        LocalDateTime lastUpdate
) {
}
