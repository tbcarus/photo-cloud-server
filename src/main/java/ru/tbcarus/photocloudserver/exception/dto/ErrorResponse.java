package ru.tbcarus.photocloudserver.exception.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ErrorResponse {
    private UUID id;
    private ErrorCode code;
    private String message;
    private Map<String, String> fieldErrors;
}
