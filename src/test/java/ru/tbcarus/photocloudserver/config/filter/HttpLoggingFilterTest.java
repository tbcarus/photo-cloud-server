package ru.tbcarus.photocloudserver.config.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Фильтр пишет только метод, шаблон маршрута, статус и длительность; запрос и ответ идут дальше без обёрток.
 * Сопоставление маршрута через MVC проверяет HttpLoggingIntegrationTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class HttpLoggingFilterTest {

    private static final String SECRET = "s3cr3t-value-7f2a";
    private static final byte[] FILE_BYTES = allByteValues(64 * 1024);

    @SuppressWarnings("unchecked")
    private final HttpLoggingFilter filter = new HttpLoggingFilter(mock(ObjectProvider.class));
    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(HttpLoggingFilter.class);
    private Level previousLevel;

    // Интеграционные тесты в той же JVM понижают уровень пакета до WARN через application-test.yml.
    @BeforeEach
    void enableFilterLogging() {
        previousLevel = filterLogger.getLevel();
        filterLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void restoreFilterLogging() {
        filterLogger.setLevel(previousLevel);
    }

    @Test
    void anyRequestReachesChainWithOriginalRequestAndResponse() throws Exception {
        for (String uri : List.of("/api/v1/files/upload", "/api/v1/files/42/download", "/api/v1/auth/login",
                "/api/v1/files/checksums/exists", "/s/" + SECRET)) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent(FILE_BYTES);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<ServletRequest> seenRequest = new AtomicReference<>();
            AtomicReference<ServletResponse> seenResponse = new AtomicReference<>();

            filter.doFilter(request, response, (req, res) -> {
                seenRequest.set(req);
                seenResponse.set(res);
                assertThat(req.getInputStream().readAllBytes()).isEqualTo(FILE_BYTES);
                res.getOutputStream().write(FILE_BYTES);
                // Байты уже у контейнера, пока обработчик не вернул управление: фильтр их не держит.
                assertThat(response.getContentAsByteArray()).hasSize(FILE_BYTES.length);
            });

            // Без ContentCaching*Wrapper: ни тело запроса, ни тело ответа не накапливаются в памяти фильтра.
            assertThat(seenRequest.get()).isSameAs(request);
            assertThat(seenResponse.get()).isSameAs(response);
            assertThat(response.getContentAsByteArray()).isEqualTo(FILE_BYTES);
        }
    }

    @Test
    void logsRoutePatternStatusAndDurationOnly(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login/" + SECRET);
        request.setQueryString("code=" + SECRET);
        request.addHeader("Authorization", "Bearer " + SECRET);
        request.addHeader("Cookie", "SESSION=" + SECRET);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(("{\"password\":\"" + SECRET + "\"}").getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/auth/login/{code}");
            req.getInputStream().readAllBytes();
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getOutputStream().write(("{\"accessToken\":\"" + SECRET + "\"}").getBytes(StandardCharsets.UTF_8));
        });

        assertThat(output).containsPattern("POST /api/v1/auth/login/\\{code} -> 200 \\| \\d+ ms");
        assertThat(output).doesNotContain(SECRET);
    }

    @Test
    void unknownRouteIsNotPrintedAsActualPath(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/s/" + SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(404));

        assertThat(output).containsPattern("GET \\[unmapped] -> 404 \\| \\d+ ms");
        assertThat(output).doesNotContain(SECRET);
    }

    @Test
    void errorStatusAndBodyArePassedUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/404/download");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse http = (HttpServletResponse) res;
            http.setStatus(404);
            http.setContentType(MediaType.APPLICATION_JSON_VALUE);
            http.getWriter().write("{\"code\":\"FILE_NOT_FOUND\"}");
        });

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"FILE_NOT_FOUND\"}");
    }

    @Test
    void chainExceptionIsPropagatedAndLoggedWithoutMessage(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/1/download");
        FilterChain failing = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/files/{id}/download");
            throw new IllegalStateException("storage failure " + SECRET);
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage failure " + SECRET);
        assertThat(output).containsPattern("GET /api/v1/files/\\{id}/download -> IllegalStateException \\| \\d+ ms");
        assertThat(output).doesNotContain(SECRET);
    }

    @Test
    void failingRouteResolutionDoesNotBreakRequest(CapturedOutput output) throws Exception {
        RequestMappingHandlerMapping broken = mock(RequestMappingHandlerMapping.class, invocation -> {
            throw new IllegalStateException("mapping failure");
        });
        @SuppressWarnings("unchecked")
        ObjectProvider<RequestMappingHandlerMapping> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(broken);
        HttpLoggingFilter withBrokenMapping = new HttpLoggingFilter(provider);
        MockHttpServletResponse response = new MockHttpServletResponse();

        withBrokenMapping.doFilter(new MockHttpServletRequest("GET", "/api/v1/files/1"), response,
                (req, res) -> ((HttpServletResponse) res).setStatus(401));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(output).containsPattern("GET \\[unmapped] -> 401 \\| \\d+ ms");
    }

    private static byte[] allByteValues(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }
}
