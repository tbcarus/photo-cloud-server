package ru.tbcarus.photocloudserver.config.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import ru.tbcarus.photocloudserver.exception.dto.ErrorCode;
import ru.tbcarus.photocloudserver.exception.dto.ErrorResponse;

import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse body = ErrorResponse.builder()
                .id(UUID.randomUUID())
                .code(ErrorCode.UNAUTHORIZED)
                .message("Unauthorized: access token expired or invalid")
                .build();
        objectMapper.writeValue(response.getWriter(), body);
    }
}
