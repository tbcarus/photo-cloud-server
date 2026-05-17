package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be valid")
        String email,
        @NotBlank(message = "Password must not be blank")
        @Size(min = 4, max = 20, message = "Password length must be from 4 to 20")
        String password
) {
}
