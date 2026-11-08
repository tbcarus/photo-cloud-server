package ru.tbcarus.photocloudserver.model.dto;

// Публичные сведения о владельце или получателе доступа.
public record ShareUserDto(
        Long id,
        String email,
        String displayName
) {
}
