package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Email(message = "Must be e-mail")
        String email,
        @NotBlank(message = "Password can not be blank")
        @Size(min = 4, max = 20, message = "Password length must be from 4 to 20")
//        @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[@#$%^&+=!]).*$")
        String password
//        ,
//        @NotBlank(message = "Firstname can not be blank")
//        @Size(min = 1, max = 20, message = "FirstName length must be from 1 to 20")
//        String firstName,
//        @NotBlank(message = "Lastname can not be blank")
//        @Size(min = 1, max = 20, message = "LastName length must be from 1 to 20")
//        String lastName
) {

}
