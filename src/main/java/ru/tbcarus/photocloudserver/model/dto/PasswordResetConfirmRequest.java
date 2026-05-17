package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(
        @NotBlank(message = "Password must not be blank")
        @Size(min = 4, max = 20, message = "Password length must be from 4 to 20")
        String password,
        @NotBlank(message = "Code must not be blank")
        String code
) {
}
