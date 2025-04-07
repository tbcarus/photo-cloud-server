package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.Email;
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

    @Email(message = "Must be e-mail")
    private String email;
    @Size(min = 4, max = 20, message = "Password length must be from 4 to 20")
    private String password;
    @Size(min = 1, max = 20, message = "Name length must be from 1 to 20")
    private String name;

}
