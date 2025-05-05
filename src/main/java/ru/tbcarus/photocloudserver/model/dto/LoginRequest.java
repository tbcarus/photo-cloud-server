package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Email(message = "Must be e-mail")
        String email,
        @Size(min = 4, max = 20, message = "Password length must be from 4 to 20")
        String password
) {
}
