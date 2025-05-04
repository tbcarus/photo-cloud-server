package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Email(message = "Must be e-mail")
        String email,
        @NotBlank(message = "Password can not be blank")
        @Size(min = 4, max = 20, message = "Password length must be from 4 to 20")
//        @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[@#$%^&+=!]).*$")
        String password,
        @NotBlank String firstName,
        @NotBlank String lastName
) {

}
