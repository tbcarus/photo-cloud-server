package ru.tbcarus.photocloudserver.config.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

// Одна строка на запрос: метод, шаблон маршрута, статус, длительность. Тела, заголовки, query и фактические
// значения параметров пути не пишутся: в них бывают пароли, JWT, коды из писем и будущие токены ссылок.
// Запрос и ответ передаются дальше без обёрток, поэтому фильтр не накапливает тела и не мешает потоковой
// передаче и Range.
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpLoggingFilter extends OncePerRequestFilter {

    // Маршрут не определён (нет обработчика, 405): фактический путь может содержать секрет, поэтому не выводится.
    static final String UNKNOWN_ROUTE = "[unmapped]";

    private static final String RESET = "\033[0m";
    private static final String BLUE = "\033[1;34m";
    private static final String RED = "\033[1;31m";

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;

    public HttpLoggingFilter(@Qualifier("requestMappingHandlerMapping")
                             ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            log(request, response, startedAt, failure);
        }
    }

    private void log(HttpServletRequest request, HttpServletResponse response, long startedAt, Throwable failure) {
        try {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            String route = route(request);
            if (failure != null) {
                // Сообщение исключения не пишется: оно может содержать входные данные. Подробности — в логе обработчика.
                log.info("{}{} {} -> {} | {} ms{}", RED, request.getMethod(), route,
                        failure.getClass().getSimpleName(), durationMs, RESET);
                return;
            }
            int status = response.getStatus();
            log.info("{}{} {} -> {} | {} ms{}", status >= 400 ? RED : BLUE, request.getMethod(), route,
                    status, durationMs, RESET);
        } catch (RuntimeException ex) {
            // Ошибка журнала не меняет результат запроса.
            log.warn("HTTP-запрос не записан в журнал: {}", ex.getClass().getSimpleName());
        }
    }

    private String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            // Spring Security мог отказать до DispatcherServlet: шаблон находится тем же сопоставлением, что у MVC.
            pattern = resolvePattern(request);
        }
        return pattern != null ? pattern.toString() : UNKNOWN_ROUTE;
    }

    private Object resolvePattern(HttpServletRequest request) {
        RequestMappingHandlerMapping mapping = handlerMapping.getIfAvailable();
        if (mapping == null) {
            return null;
        }
        boolean parsedHere = false;
        try {
            if (mapping.usesPathPatterns() && !ServletRequestPathUtils.hasParsedRequestPath(request)) {
                ServletRequestPathUtils.parseAndCache(request);
                parsedHere = true;
            }
            return mapping.getHandler(request) != null
                    ? request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
                    : null;
        } catch (Exception ex) {
            // Нет подходящего метода или типа содержимого: маршрут неизвестен.
            return null;
        } finally {
            if (parsedHere) {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        }
    }
}
