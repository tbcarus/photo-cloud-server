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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Бинарные upload/download проходят фильтр без ContentCaching*Wrapper: тело идёт напрямую, а не копится в памяти.
 * Остальные запросы логируются, как прежде, вместе с JSON-телом.
 */
@ExtendWith(OutputCaptureExtension.class)
class HttpLoggingFilterTest {

    private static final byte[] FILE_BYTES = allByteValues(64 * 1024);

    private final HttpLoggingFilter filter = new HttpLoggingFilter();
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
    void multipartUploadsReachControllerWithOriginalRequestAndResponse() throws Exception {
        for (String uri : List.of("/api/v1/files", "/api/v1/files/upload")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
            request.setContentType("multipart/form-data; boundary=photo");
            request.setContent(FILE_BYTES);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<ServletRequest> seenRequest = new AtomicReference<>();
            AtomicReference<ServletResponse> seenResponse = new AtomicReference<>();

            filter.doFilter(request, response, (req, res) -> {
                seenRequest.set(req);
                seenResponse.set(res);
                // Контроллер читает всё тело: через кеширующий wrapper оно осело бы в памяти фильтра.
                assertThat(req.getInputStream().readAllBytes()).isEqualTo(FILE_BYTES);
                ((HttpServletResponse) res).setStatus(200);
            });

            assertThat(seenRequest.get()).isSameAs(request);
            assertThat(seenResponse.get()).isSameAs(response);
        }
    }

    @Test
    void downloadWritesStraightToResponseWithoutCaching(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/42/download");
        request.setQueryString("token=secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(res).isSameAs(response).isNotInstanceOf(ContentCachingResponseWrapper.class);
            res.setContentType(MediaType.IMAGE_JPEG_VALUE);
            res.getOutputStream().write(FILE_BYTES);
            // Байты уже у контейнера, пока контроллер не вернул управление: фильтр их не держит.
            assertThat(response.getContentAsByteArray()).hasSize(FILE_BYTES.length);
        });

        assertThat(response.getContentAsByteArray()).isEqualTo(FILE_BYTES);
        assertThat(response.getContentType()).isEqualTo(MediaType.IMAGE_JPEG_VALUE);
        assertThat(output).contains(">>> GET /api/v1/files/42/download | body: [binary]");
        assertThat(output).containsPattern("<<< 200 \\| \\d+ ms \\| body: \\[binary]");
        assertThat(output).doesNotContain("token=secret");
    }

    @Test
    void downloadUnderContextPathIsAlsoNotCached() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/api/v1/files/7/download");
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletResponse> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(res));

        assertThat(seen.get()).isSameAs(response);
    }

    @Test
    void concurrentDownloadsDoNotGetPerFileBuffers() throws Exception {
        int downloads = 8;
        CountDownLatch allInside = new CountDownLatch(downloads);
        ExecutorService executor = Executors.newFixedThreadPool(downloads);
        try {
            List<Future<MockHttpServletResponse>> results = new ArrayList<>();
            for (int i = 0; i < downloads; i++) {
                String uri = "/api/v1/files/" + i + "/download";
                results.add(executor.submit(() -> {
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, (req, res) -> {
                        assertThat(res).isSameAs(response);
                        res.getOutputStream().write(FILE_BYTES);
                        // Все скачивания одновременно внутри цепочки: ни у одного тело не задержано фильтром.
                        assertThat(response.getContentAsByteArray()).hasSize(FILE_BYTES.length);
                        allInside.countDown();
                        try {
                            assertThat(allInside.await(10, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    });
                    return response;
                }));
            }
            for (Future<MockHttpServletResponse> result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS).getContentAsByteArray()).isEqualTo(FILE_BYTES);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void jsonRequestsKeepBodyLogging(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/files/checksums/exists");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{\"folderId\":2}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            seen.set(req);
            req.getInputStream().readAllBytes();
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getOutputStream().write("{\"existing\":[]}".getBytes(StandardCharsets.UTF_8));
        });

        assertThat(seen.get()).isInstanceOf(ContentCachingRequestWrapper.class);
        assertThat(response.getContentAsString()).isEqualTo("{\"existing\":[]}");
        assertThat(output).contains("body: {\"folderId\":2}");
        assertThat(output).contains("body: {\"existing\":[]}");
    }

    @Test
    void nonDownloadFileRequestsAreLoggedAsBefore() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletResponse> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(res));

        assertThat(seen.get()).isInstanceOf(ContentCachingResponseWrapper.class);
    }

    @Test
    void binaryExchangeKeepsErrorStatusAndBody() throws Exception {
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
    void binaryExchangePropagatesChainException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/1/download");
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("storage failure");
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage failure");
    }

    private static byte[] allByteValues(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }
}
