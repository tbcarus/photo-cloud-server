package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;
import ru.tbcarus.photocloudserver.service.storage.StorageProperties;
import ru.tbcarus.photocloudserver.service.thumbnail.ThumbnailFixtures;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Настоящий Tomcat: заголовки кеширования проверяются в том виде, в каком их получает клиент, вместе со Spring Security.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ThumbnailIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private String otherToken;

    @Autowired
    StorageProperties storageProperties;

    private long previousMaxFileSize;

    @BeforeEach
    void createUsers() throws Exception {
        // Базовый предел upload в тестах 1 КБ; изображениям нужно больше. Контекст класса отдельный и закрывается после него.
        previousMaxFileSize = storageProperties.getMaxFileSizeBytes();
        storageProperties.setMaxFileSizeBytes(1024 * 1024);
        createUser("thumb@test.local", PASSWORD);
        createUser("other@test.local", PASSWORD);
        token = loginAndGetAccessToken("thumb@test.local", PASSWORD);
        otherToken = loginAndGetAccessToken("other@test.local", PASSWORD);
    }

    @AfterEach
    void restoreUploadLimit() {
        storageProperties.setMaxFileSizeBytes(previousMaxFileSize);
    }

    @Test
    void ownerGetsJpegThumbnailWithCacheHeaders() throws Exception {
        Map<String, byte[]> sources = Map.of(
                "photo.jpg", ThumbnailFixtures.jpeg(800, 400),
                "picture.png", ThumbnailFixtures.png(400, 800),
                "image.webp", ThumbnailFixtures.resource("lossy.webp"));

        for (Map.Entry<String, byte[]> source : sources.entrySet()) {
            FileItemDto file = upload(token, source.getKey(), "application/octet-stream", source.getValue());

            HttpResponse<byte[]> response = thumbnail(file.getId(), token, Map.of());

            assertThat(response.statusCode()).as(source.getKey()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValue("image/jpeg");
            assertThat(response.headers().firstValue("ETag").orElseThrow()).matches("\"[0-9a-f]{32}\"");
            assertThat(response.headers().allValues("Cache-Control")).containsExactly("max-age=86400, private");
            assertThat(response.headers().firstValue("Pragma")).isEmpty();
            assertThat(response.headers().firstValue("Expires")).isEmpty();
            BufferedImage image = ThumbnailFixtures.read(response.body());
            assertThat(Math.max(image.getWidth(), image.getHeight())).isLessThanOrEqualTo(320);
        }
    }

    @Test
    void matchingIfNoneMatchReturnsNotModifiedWithoutBody() throws Exception {
        FileItemDto file = upload(token, "photo.jpg", "image/jpeg", ThumbnailFixtures.jpeg(800, 400));
        HttpResponse<byte[]> first = thumbnail(file.getId(), token, Map.of());
        String etag = first.headers().firstValue("ETag").orElseThrow();

        for (String ifNoneMatch : new String[]{etag, "W/" + etag, "\"other\", " + etag}) {
            HttpResponse<byte[]> repeated = thumbnail(file.getId(), token, Map.of("If-None-Match", ifNoneMatch));

            assertThat(repeated.statusCode()).as(ifNoneMatch).isEqualTo(304);
            assertThat(repeated.body()).isEmpty();
            assertThat(repeated.headers().firstValue("ETag")).hasValue(etag);
            assertThat(repeated.headers().allValues("Cache-Control")).containsExactly("max-age=86400, private");
        }

        // Устаревший ETag и «*» (у GET без смысла) дают полный ответ.
        for (String ifNoneMatch : new String[]{"\"stale\"", "*"}) {
            HttpResponse<byte[]> full = thumbnail(file.getId(), token, Map.of("If-None-Match", ifNoneMatch));
            assertThat(full.statusCode()).as(ifNoneMatch).isEqualTo(200);
            assertThat(full.headers().firstValue("ETag")).hasValue(etag);
            assertThat(full.body()).isEqualTo(first.body());
        }
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        FileItemDto file = upload(token, "photo.jpg", "image/jpeg", ThumbnailFixtures.jpeg(64, 48));

        assertThat(thumbnail(file.getId(), null, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void foreignOrMissingFileIsNotFoundEvenWithMatchingEtag() throws Exception {
        FileItemDto file = upload(token, "photo.jpg", "image/jpeg", ThumbnailFixtures.jpeg(64, 48));
        String etag = thumbnail(file.getId(), token, Map.of()).headers().firstValue("ETag").orElseThrow();

        HttpResponse<byte[]> foreign = thumbnail(file.getId(), otherToken, Map.of());
        HttpResponse<byte[]> foreignWithEtag = thumbnail(file.getId(), otherToken, Map.of("If-None-Match", etag));
        HttpResponse<byte[]> foreignWithStar = thumbnail(file.getId(), otherToken, Map.of("If-None-Match", "*"));
        HttpResponse<byte[]> missing = thumbnail(file.getId() + 1000, token, Map.of());

        for (HttpResponse<byte[]> response : java.util.List.of(foreign, foreignWithEtag, foreignWithStar, missing)) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(errorCode(response)).isEqualTo("FILE_ITEM_NOT_FOUND");
            assertThat(response.headers().firstValue("ETag")).isEmpty();
        }
    }

    @Test
    void missingPhysicalBytesGiveNotFound() throws Exception {
        FileItemDto file = upload(token, "photo.jpg", "image/jpeg", ThumbnailFixtures.jpeg(64, 48));
        deletePhysicalFile(file.getId());

        HttpResponse<byte[]> response = thumbnail(file.getId(), token, Map.of());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(errorCode(response)).isEqualTo("FILE_ITEM_NOT_FOUND");
    }

    @Test
    void unsupportedTypesGiveUnsupportedMediaType() throws Exception {
        Map<String, byte[]> sources = Map.of(
                "photo.heic", ThumbnailFixtures.heic(),
                "anim.gif", ThumbnailFixtures.gif(),
                "doc.pdf", ThumbnailFixtures.pdf(),
                "anim.webp", ThumbnailFixtures.animatedWebpHeader());

        for (Map.Entry<String, byte[]> source : sources.entrySet()) {
            FileItemDto file = upload(token, source.getKey(), "application/octet-stream", source.getValue());

            HttpResponse<byte[]> response = thumbnail(file.getId(), token, Map.of());

            assertThat(response.statusCode()).as(source.getKey()).isEqualTo(415);
            assertThat(errorCode(response)).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).doesNotContain("max-age=86400");
            // Формат проверяется и до сравнения ETag: «*» не превращает неподдерживаемый файл в 304.
            assertThat(thumbnail(file.getId(), token, Map.of("If-None-Match", "*")).statusCode()).isEqualTo(415);
        }
    }

    // Тип определяет содержимое, а не имя: JPEG под именем .png даёт миниатюру, PDF под именем .jpg — нет.
    @Test
    void typeIsDetectedByContentNotByName() throws Exception {
        FileItemDto jpegNamedPng = upload(token, "photo.png", "image/png", ThumbnailFixtures.jpeg(64, 48));
        FileItemDto pdfNamedJpg = upload(token, "scan.jpg", "image/jpeg", ThumbnailFixtures.pdf(), getRootId(token));

        assertThat(thumbnail(jpegNamedPng.getId(), token, Map.of()).statusCode()).isEqualTo(200);
        assertThat(thumbnail(pdfNamedJpg.getId(), token, Map.of()).statusCode()).isEqualTo(415);
    }

    @Test
    void corruptedImageGivesUnprocessableEntity() throws Exception {
        FileItemDto file = upload(token, "broken.jpg", "image/jpeg", ThumbnailFixtures.corruptedJpeg());

        HttpResponse<byte[]> response = thumbnail(file.getId(), token, Map.of());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(errorCode(response)).isEqualTo("IMAGE_UNREADABLE");
    }

    @Test
    void oversizedImageGivesControlledError() throws Exception {
        FileItemDto file = upload(token, "huge.png", "image/png", ThumbnailFixtures.pngHeader(100_000, 100_000));

        HttpResponse<byte[]> response = thumbnail(file.getId(), token, Map.of());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(errorCode(response)).isEqualTo("IMAGE_TOO_LARGE");
    }

    // Загрузчики изображений присылают Accept: image/*; JSON-ошибка всё равно должна дойти с исходным статусом.
    @Test
    void errorsKeepStatusWhenClientAcceptsOnlyImages() throws Exception {
        FileItemDto heic = upload(token, "photo.heic", "image/heic", ThumbnailFixtures.heic());
        FileItemDto broken = upload(token, "broken.jpg", "image/jpeg", ThumbnailFixtures.corruptedJpeg());
        Map<String, String> accept = Map.of("Accept", "image/*");

        assertThat(thumbnail(heic.getId(), token, accept).statusCode()).isEqualTo(415);
        assertThat(thumbnail(broken.getId(), token, accept).statusCode()).isEqualTo(422);
        assertThat(thumbnail(heic.getId() + 1000, token, accept).statusCode()).isEqualTo(404);
        assertThat(thumbnail(heic.getId(), otherToken, accept).statusCode()).isEqualTo(404);
        // Тот же обработчик у download: раньше 404 при таком Accept превращался в 401.
        assertThat(send(heic.getId() + 1000, "/download", token, accept).statusCode()).isEqualTo(404);
    }

    @Test
    void thumbnailDoesNotChangeOriginalOrDownload() throws Exception {
        byte[] original = ThumbnailFixtures.withOrientation(ThumbnailFixtures.jpeg(800, 400), 6);
        FileItemDto file = upload(token, "photo.jpg", "image/jpeg", original);
        long filesBefore = storageFileCount();

        assertThat(thumbnail(file.getId(), token, Map.of()).statusCode()).isEqualTo(200);

        assertThat(Files.readAllBytes(physicalPath(findFileItem(file.getId())))).isEqualTo(original);
        // Миниатюры не сохраняются: файлов в хранилище не прибавилось, временных не осталось.
        assertThat(storageFileCount()).isEqualTo(filesBefore);
        assertThat(tempFileCount()).isZero();
        HttpResponse<byte[]> download = send(file.getId(), "/download", token, Map.of());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(original);
        // Политика кеширования прочих ответов прежняя: Spring Security запрещает хранение.
        assertThat(download.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(download.headers().firstValue("ETag")).isEmpty();
    }

    private HttpResponse<byte[]> thumbnail(Long fileId, String accessToken, Map<String, String> headers) throws Exception {
        return send(fileId, "/thumbnail", accessToken, headers);
    }

    private HttpResponse<byte[]> send(Long fileId, String suffix, String accessToken, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files/" + fileId + suffix));
        if (accessToken != null) {
            request.header("Authorization", authHeader(accessToken));
        }
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String errorCode(HttpResponse<byte[]> response) throws Exception {
        JsonNode body = objectMapper.readTree(response.body());
        return body.path("code").asText();
    }
}
