package ru.tbcarus.photocloudserver.model.dto;

public record LoginResponse(
        String accessToken,
        String refreshToken
) {
}
