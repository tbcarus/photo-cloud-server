package ru.tbcarus.photocloudserver.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

// refreshToken есть только при замене refresh; без замены ответ прежний — только accessToken.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefreshResponse(
        String accessToken,
        String refreshToken
) {
}
