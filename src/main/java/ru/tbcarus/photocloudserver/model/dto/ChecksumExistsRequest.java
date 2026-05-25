package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record ChecksumExistsRequest(
        @NotEmpty
        List<@NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String> checksums
) {
}
