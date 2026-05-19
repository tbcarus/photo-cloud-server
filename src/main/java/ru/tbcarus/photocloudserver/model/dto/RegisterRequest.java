package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be valid")
        String email,
        @NotBlank(message = "Password must not be blank")
        @Size(min = 4, max = 20, message = "Password length must be from 4 to 20")
//        @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[@#$%^&+=!]).*$")
        String password
//        ,
//        @Size(max = 128, message = "Display name length must be up to 128")
//        String displayName
) {

}
