package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserRegisterDto {

    @NotBlank
    @Email(message = "Must be e-mail")
    private String email;
    @NotBlank(message = "Password can not be blank")
    @Size(min = 4, max = 20, message = "Password length must be from 4 to 20")
    private String password;
    @Size(min = 1, max = 20, message = "FirstName length must be from 1 to 20")
    private String firstName;
    @Size(min = 1, max = 20, message = "LastName length must be from 1 to 20")
    private String lastName;

}
