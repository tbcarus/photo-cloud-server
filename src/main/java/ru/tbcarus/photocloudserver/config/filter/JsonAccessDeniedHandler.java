package ru.tbcarus.photocloudserver.config.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import ru.tbcarus.photocloudserver.exception.dto.ErrorCode;
import ru.tbcarus.photocloudserver.exception.dto.ErrorResponse;

import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JsonAccessDeniedHandler implements AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse body = ErrorResponse.builder()
                .id(UUID.randomUUID())
                .code(ErrorCode.FORBIDDEN)
                .message("Forbidden")
                .build();
        objectMapper.writeValue(response.getWriter(), body);
    }
}
