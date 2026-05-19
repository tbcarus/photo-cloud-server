package ru.tbcarus.photocloudserver.model.dto;

import ru.tbcarus.photocloudserver.model.Role;

import java.time.LocalDateTime;
import java.util.Set;

public record UserDto(
        Long id,
        String email,
        String displayName,
        boolean enabled,
        boolean banned,
        Set<Role> roles,
        LocalDateTime createdAt,
        LocalDateTime lastUpdate,
        LocalDateTime lastLoginAt
) {
}
