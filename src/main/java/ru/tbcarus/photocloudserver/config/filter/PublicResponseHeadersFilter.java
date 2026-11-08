package ru.tbcarus.photocloudserver.config.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.web.util.OnCommittedResponseWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Заголовки ответов публичных ссылок, включая ошибки: не кешировать, не передавать адрес со ссылкой дальше
 * (Referer), не угадывать тип содержимого, не индексировать. Ставятся до обработки: Spring Security не
 * перезаписывает уже заданный Cache-Control.
 * <p>
 * CSP выбирается по готовому ответу: JSON, скачивания (в том числе HTML и SVG) и ошибки получают строгую политику без
 * скриптов и в sandbox. Изображение JPEG/PNG/WebP, которое браузер показывает сам (inline), исполнить нельзя — тип
 * определён Server по содержимому, nosniff запрещает его переопределять, — а строгая политика ломает встроенный
 * просмотр изображений браузера (стили и масштабирование). Поэтому у такого ответа CSP нет.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class PublicResponseHeadersFilter extends OncePerRequestFilter {

    public static final String DIRECT_PREFIX = "/s/";
    public static final String API_PREFIX = "/api/v1/public/";
    static final String STRICT_CSP = "default-src 'none'; sandbox";
    private static final String CSP = "Content-Security-Policy";
    private static final Set<String> VIEWABLE_IMAGES = Set.of("image/jpeg", "image/png", "image/webp");

    public static boolean isPublicPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith(DIRECT_PREFIX) || path.startsWith(API_PREFIX);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !isPublicPath(request);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        CspResponse wrapped = new CspResponse(response);
        try {
            filterChain.doFilter(request, wrapped);
        } finally {
            // Ответ без тела (например, 304) мог не зафиксироваться внутри цепочки.
            if (!response.isCommitted()) {
                wrapped.applyCsp();
            }
        }
    }

    /** Ставит CSP непосредственно перед фиксацией ответа, когда известны тип и Content-Disposition. */
    private static final class CspResponse extends OnCommittedResponseWrapper {
        private boolean applied;

        CspResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        protected void onResponseCommitted() {
            applyCsp();
        }

        void applyCsp() {
            if (applied) {
                return;
            }
            applied = true;
            if (!isViewableImage()) {
                setHeader(CSP, STRICT_CSP);
            }
        }

        private boolean isViewableImage() {
            String type = getContentType();
            String disposition = getHeader(HttpHeaders.CONTENT_DISPOSITION);
            if (type == null || disposition == null) {
                return false;
            }
            String mime = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            return VIEWABLE_IMAGES.contains(mime) && disposition.toLowerCase(Locale.ROOT).startsWith("inline");
        }
    }
}
