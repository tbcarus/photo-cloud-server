package ru.tbcarus.photocloudserver.config.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_LENGTH = 1000;

    private static final String RESET  = "\033[0m";
    private static final String GREEN  = "\033[1;32m";
    private static final String BLUE   = "\033[1;34m";
    private static final String RED    = "\033[1;31m";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        filterChain.doFilter(requestWrapper, responseWrapper);

        logRequest(requestWrapper);
        logResponse(responseWrapper);

        responseWrapper.copyBodyToResponse();
    }

    private void logRequest(ContentCachingRequestWrapper request) {
        String queryString = request.getQueryString();
        String url = queryString != null
                ? request.getRequestURI() + "?" + queryString
                : request.getRequestURI();
        String body = extractBody(request.getContentAsByteArray(), request.getContentType());

        if (body.isEmpty()) {
            log.info("{}>>> {} {}{}", GREEN, request.getMethod(), url, RESET);
        } else {
            log.info("{}>>> {} {} | body: {}{}", GREEN, request.getMethod(), url, body, RESET);
        }
    }

    private void logResponse(ContentCachingResponseWrapper response) {
        String body = extractBody(response.getContentAsByteArray(), response.getContentType());
        int status = response.getStatus();
        String color = (status >= 400) ? RED : BLUE;

        if (body.isEmpty()) {
            log.info("{}<<< {}{}", color, status, RESET);
        } else {
            log.info("{}<<< {} | body: {}{}", color, status, body, RESET);
        }
    }

    private String extractBody(byte[] bytes, String contentType) {
        if (bytes.length == 0) {
            return "";
        }
        if (contentType != null && (
                contentType.contains("multipart/") ||
                contentType.contains("application/octet-stream") ||
                contentType.contains("image/") ||
                contentType.contains("video/"))) {
            return "[binary]";
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        return body.length() > MAX_BODY_LENGTH
                ? body.substring(0, MAX_BODY_LENGTH) + "...[truncated]"
                : body;
    }
}
