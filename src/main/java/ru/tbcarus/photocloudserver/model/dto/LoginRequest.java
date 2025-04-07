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
public class LoginRequest {

    @Email(message = "Must be e-mail")
    private String email;
    @Size(min = 4, max = 20, message = "Password length must be from 4 to 20")
    private String password;

}
