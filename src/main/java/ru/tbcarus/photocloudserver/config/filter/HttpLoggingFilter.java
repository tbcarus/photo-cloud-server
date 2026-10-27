package ru.tbcarus.photocloudserver.config.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import ru.tbcarus.photocloudserver.controller.FileController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_LENGTH = 1000;
    private static final String DOWNLOAD_PATTERN = FileController.BASE_URL + FileController.DOWNLOAD_URL;
    private static final PathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final String RESET  = "\033[0m";
    private static final String GREEN  = "\033[1;32m";
    private static final String BLUE   = "\033[1;34m";
    private static final String RED    = "\033[1;31m";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (isBinaryExchange(request)) {
            // Тела upload/download не оборачиваются: ContentCaching*Wrapper накопил бы в памяти весь файл.
            long startedAt = System.nanoTime();
            filterChain.doFilter(request, response);
            logBinaryExchange(request, response, startedAt);
            return;
        }

        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        filterChain.doFilter(requestWrapper, responseWrapper);

        logRequest(requestWrapper);
        logResponse(responseWrapper);

        responseWrapper.copyBodyToResponse();
    }

    // Решается до контроллера: Content-Type ответа известен только после записи тела, когда оно уже накоплено.
    private boolean isBinaryExchange(HttpServletRequest request) {
        return isBinaryContentType(request.getContentType()) || isDownload(request);
    }

    private boolean isDownload(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return HttpMethod.GET.matches(request.getMethod()) && PATH_MATCHER.match(DOWNLOAD_PATTERN, path);
    }

    // Query string не пишется: в бинарных операциях она не нужна для диагностики.
    private void logBinaryExchange(HttpServletRequest request, HttpServletResponse response, long startedAt) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        int status = response.getStatus();
        String color = (status >= 400) ? RED : BLUE;
        log.info("{}>>> {} {} | body: [binary]{}", GREEN, request.getMethod(), request.getRequestURI(), RESET);
        log.info("{}<<< {} | {} ms | body: [binary]{}", color, status, durationMs, RESET);
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
        if (isBinaryContentType(contentType)) {
            return "[binary]";
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        return body.length() > MAX_BODY_LENGTH
                ? body.substring(0, MAX_BODY_LENGTH) + "...[truncated]"
                : body;
    }

    private static boolean isBinaryContentType(String contentType) {
        return contentType != null && (
                contentType.contains("multipart/") ||
                contentType.contains("application/octet-stream") ||
                contentType.contains("image/") ||
                contentType.contains("video/"));
    }
}
