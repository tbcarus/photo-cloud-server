package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @NotBlank(message = "Refresh token must not be blank")
        String refreshToken
) {
}
