package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.tbcarus.photocloudserver.model.FileType;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;
import ru.tbcarus.photocloudserver.service.storage.FileContentDetector;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Маршрут существующего Android: upload без folderId, тип определяется только по содержимому.
class LibraryAcceptanceCameraMimeIntegrationTest extends LibraryAcceptanceSupport {

    private static final byte[] WEBP = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

    @Autowired
    FileContentDetector fileContentDetector;

    private byte[] image(String format, int size) throws Exception {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, new Random().nextInt(0xFFFFFF));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    // Контейнер HEIF: ftyp и начало meta; энкодера HEVC в окружении нет, поэтому пикселей здесь нет.
    private static byte[] isoBox(String brand, String... compatible) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int size = 16 + compatible.length * 4;
        output.writeBytes(new byte[]{0, 0, 0, (byte) size});
        output.writeBytes("ftyp".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(brand.getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(new byte[]{0, 0, 0, 0});
        for (String value : compatible) {
            output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
        }
        output.writeBytes(new byte[]{0, 0, 0, 12});
        output.writeBytes("meta".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(new byte[]{0, 0, 0, 0});
        return output.toByteArray();
    }

    private Map<String, byte[]> realImages() throws Exception {
        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put("jpeg", image("jpeg", 1));
        images.put("png", image("png", 4));
        images.put("gif", image("gif", 2));
        images.put("bmp", image("bmp", 1));
        images.put("webp", WEBP);
        images.put("heic", isoBox("heic", "mif1", "heic"));
        images.put("heif", isoBox("mif1", "mif1", "heic"));
        return images;
    }

    @Test
    void firstUploadOfEachRealFormatCreatesCameraAndRoutesThere() throws Exception {
        for (Map.Entry<String, byte[]> image : realImages().entrySet()) {
            cleanupDbAndStorage();
            userId = createUser("accept@test.local", PASSWORD).getId();
            token = loginAndGetAccessToken("accept@test.local", PASSWORD);
            assertThat(folderRepository.findAll()).isEmpty();

            MockHttpServletResponse response = uploadCall("photo." + image.getKey(), image.getValue(), null).call();

            assertThat(response.getStatus()).as(image.getKey()).isEqualTo(200);
            assertThat(findFileItem(id(response)).getFolder().getFolderType()).as(image.getKey()).isEqualTo(FolderType.CAMERA);
            System.out.println("MIME-ROUTE " + image.getKey() + " -> " + findFileItem(id(response)).getStoredObject().getDetectedMimeType());
        }
    }

    @Test
    void uploadIntoExistingCameraReusesFolderForEachFormat() throws Exception {
        Long cameraId = null;
        for (Map.Entry<String, byte[]> image : realImages().entrySet()) {
            MockHttpServletResponse response = uploadCall("photo." + image.getKey(), image.getValue(), null).call();

            assertThat(response.getStatus()).as(image.getKey()).isEqualTo(200);
            Long folder = findFileItem(id(response)).getFolder().getId();
            cameraId = cameraId == null ? folder : cameraId;
            assertThat(folder).as(image.getKey()).isEqualTo(cameraId);
        }
        assertThat(count("SELECT count(*) FROM folder WHERE folder_type = 'CAMERA'")).isEqualTo(1);
        assertLibraryConsistent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"photo.txt", "photo.png", "no-extension", "archive.zip", "имя.pdf"})
    void wrongExtensionDoesNotAffectRouting(String name) throws Exception {
        MockHttpServletResponse response = uploadCall(name, image("jpeg", 1), null).call();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(findFileItem(id(response)).getFolder().getFolderType()).isEqualTo(FolderType.CAMERA);
        assertThat(findFileItem(id(response)).getStoredObject().getDetectedMimeType()).isEqualTo("image/jpeg");
        assertThat(findFileItem(id(response)).getOriginalName()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/octet-stream", "text/plain", "image/unknown", "application/pdf", "video/mp4"})
    void clientMimeDoesNotAffectRouting(String clientMime) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(multipart("/api/v1/files")
                        .file(filePart("photo.bin", clientMime, image("png", 2)))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(findFileItem(id(response)).getFolder().getFolderType()).isEqualTo(FolderType.CAMERA);
        assertThat(findFileItem(id(response)).getStoredObject().getDetectedMimeType()).isEqualTo("image/png");
    }

    @Test
    void undetectedContentWithoutFolderIsRejectedAndChangesNothing() throws Exception {
        byte[] noise = new byte[256];
        new Random(1).nextBytes(noise);
        noise[0] = 1;

        for (String name : List.of("photo.jpg", "photo.heic", "broken")) {
            MockHttpServletResponse response = uploadCall(name, noise, null).call();

            assertThat(response.getStatus()).as(name).isEqualTo(400);
            assertThat(response.getContentAsString()).contains("Target folder is required");
        }
        assertThat(folderRepository.findAll()).isEmpty();
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertLibraryConsistent();
    }

    // Тот же файл проходит, как только клиент знает Camera: так Android работает во всех прогонах, кроме самого первого.
    @Test
    void undetectedContentIsAcceptedByExplicitCameraFolder() throws Exception {
        Long cameraId = upload(token, "seed.jpg", "image/jpeg", image("jpeg", 1)).getFolderId();
        byte[] noise = new byte[256];
        new Random(2).nextBytes(noise);
        noise[0] = 1;

        assertThat(uploadCall("photo.jpg", noise, null).call().getStatus()).isEqualTo(400);

        MockHttpServletResponse explicit = uploadCall("photo.jpg", noise, cameraId).call();
        assertThat(explicit.getStatus()).isEqualTo(200);
        assertThat(findFileItem(id(explicit)).getFolder().getId()).isEqualTo(cameraId);
        assertThat(findFileItem(id(explicit)).getStoredObject().getFileType()).isEqualTo(FileType.OTHER);
        assertLibraryConsistent();
    }

    @Test
    void cameraEndpointCreatesRootAndCameraOnEmptyAccountAndIsIdempotent() throws Exception {
        assertThat(folderRepository.findAll()).isEmpty();

        MockHttpServletResponse first = jsonCall(post("/api/v1/folders/camera"), null).call();
        MockHttpServletResponse second = jsonCall(post("/api/v1/folders/camera"), null).call();

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(id(second)).isEqualTo(id(first));
        var camera = objectMapper.readTree(first.getContentAsString());
        assertThat(camera.get("folderType").asText()).isEqualTo("CAMERA");
        assertThat(camera.get("name").asText()).isEqualTo("Camera");
        assertThat(camera.get("parentId").asLong()).isEqualTo(getRootId(token));
        assertThat(folderRepository.findAll()).hasSize(2);
    }

    @Test
    void cameraEndpointReturnsFolderCreatedByUploadAndKeepsItsFiles() throws Exception {
        FileItemDto photo = upload(token, "seed.jpg", "image/jpeg", image("jpeg", 1));

        MockHttpServletResponse camera = jsonCall(post("/api/v1/folders/camera"), null).call();

        assertThat(id(camera)).isEqualTo(photo.getFolderId());
        assertThat(fileItemRepository.findAll()).hasSize(1);
    }

    @Test
    void cameraEndpointBelongsToCurrentUserAndRequiresAuthentication() throws Exception {
        Long mine = id(jsonCall(post("/api/v1/folders/camera"), null).call());
        createUser("second@test.local", PASSWORD);
        String second = loginAndGetAccessToken("second@test.local", PASSWORD);

        MockHttpServletResponse foreign = mockMvc.perform(post("/api/v1/folders/camera")
                .header(HttpHeaders.AUTHORIZATION, authHeader(second))).andReturn().getResponse();

        assertThat(id(foreign)).isNotEqualTo(mine);
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM folder WHERE id = ?", Long.class, id(foreign))).isNotEqualTo(userId);
        assertThat(mockMvc.perform(post("/api/v1/folders/camera")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // Новый маршрут Android: ID Camera известен до первого файла, поэтому нераспознанный файл не получает 400.
    @Test
    void undetectedContentIsAcceptedOnFirstUploadAfterCameraRequest() throws Exception {
        Long cameraId = id(jsonCall(post("/api/v1/folders/camera"), null).call());
        byte[] noise = new byte[256];
        new Random(3).nextBytes(noise);
        noise[0] = 1;

        MockHttpServletResponse response = uploadCall("broken.jpg", noise, cameraId).call();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(findFileItem(id(response)).getFolder().getId()).isEqualTo(cameraId);
        assertThat(id(uploadCall("again.jpg", noise, cameraId).call())).isEqualTo(id(response));
        assertLibraryConsistent();
    }

    @Test
    void repeatedContentReturnsSameFileThroughBothRoutes() throws Exception {
        byte[] photo = image("png", 3);
        FileItemDto first = upload(token, "a.png", "image/png", photo);

        FileItemDto sameDefault = upload(token, "b.png", "image/png", photo);
        FileItemDto sameExplicit = upload(token, "c.png", "image/png", photo, first.getFolderId());

        assertThat(sameDefault.getId()).isEqualTo(first.getId());
        assertThat(sameExplicit.getId()).isEqualTo(first.getId());
        assertThat(physicalFiles()).hasSize(1);
        assertLibraryConsistent();
    }

    @Test
    void checksumPrecheckBeforeAndAfterUpload() throws Exception {
        byte[] seed = image("jpeg", 1);
        byte[] photo = image("png", 5);
        Long cameraId = upload(token, "seed.jpg", "image/jpeg", seed).getFolderId();

        assertThat(precheck(cameraId, photo)).isEqualTo(Map.of("existing", List.of(), "missing", List.of(sha256(photo))));
        Long fileId = upload(token, "photo.png", "image/png", photo).getId();
        assertThat(precheck(cameraId, photo)).isEqualTo(Map.of("existing", List.of(sha256(photo)), "missing", List.of()));

        // Rename не должен менять решение pre-check: дубль определяется checksum, а не именем.
        perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/files/{id}", fileId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalName\":\"renamed.png\"}")).andExpect(status().isOk());
        assertThat(precheck(cameraId, photo)).isEqualTo(Map.of("existing", List.of(sha256(photo)), "missing", List.of()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> precheck(Long folderId, byte[] bytes) throws Exception {
        String body = perform(post("/api/v1/files/checksums/exists")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("folderId", folderId, "checksums", List.of(sha256(bytes))))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, Map.class);
    }

    // Что обнаруживает production-детектор на реальных сигнатурах медиаформатов Android: таблица для отчёта.
    @Test
    void detectionTableForAndroidMediaSignatures() throws Exception {
        Map<String, byte[]> samples = new LinkedHashMap<>();
        for (String brand : List.of("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1", "avif", "avis",
                "MiHE", "MiHA", "MiAn", "MiHB", "HEVC", "jpeg", "isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ",
                "3gp4", "3gp5", "3g2a", "qt  ", "dash", "mmp4", "MSNV")) {
            samples.put("ftyp:" + brand, isoBox(brand, "mif1"));
        }
        samples.put("mp4-no-compat-heic", isoBox("heic"));
        samples.put("cr3", isoBox("crx ", "crx "));
        samples.put("raw-orf", new byte[]{'I', 'I', 'R', 'O', 8, 0, 0, 0, 0, 0});
        samples.put("raw-orf-s", new byte[]{'I', 'I', 'R', 'S', 8, 0, 0, 0, 0, 0});
        samples.put("raw-rw2", new byte[]{'I', 'I', 'U', 0, 8, 0, 0, 0, 0, 0});
        samples.put("raw-raf", "FUJIFILMCCD-RAW 0201FF129502".getBytes(StandardCharsets.US_ASCII));
        samples.put("raw-x3f", new byte[]{'F', 'O', 'V', 'b', 0, 0, 3, 0, 0, 0});
        samples.put("jxl-raw", new byte[]{(byte) 0xFF, 0x0A, 0x7F, 0, 0x10, 0x10});
        samples.put("jxl-box", new byte[]{0, 0, 0, 12, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A});
        samples.put("all-zero", new byte[512]);
        samples.put("tiff-dng-II", new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0});
        samples.put("tiff-MM", new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0});
        samples.put("jpeg-min", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        samples.put("jpeg-exif", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 8, 'E', 'x', 'i', 'f', 0, 0});
        samples.put("jpeg-raw-db", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDB, 0, 0x43, 0, 8, 6, 6});
        samples.put("jpeg-trunc-2", new byte[]{(byte) 0xFF, (byte) 0xD8});
        samples.put("mpo-jpeg", image("jpeg", 1));
        samples.put("png-header", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        samples.put("gif89a", "GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1));
        samples.put("bmp", new byte[]{'B', 'M', 0x46, 0, 0, 0, 0, 0, 0, 0, 0x36, 0, 0, 0, 0x28, 0, 0, 0});
        samples.put("webp", WEBP);
        samples.put("svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes());
        samples.put("ico", new byte[]{0, 0, 1, 0, 1, 0, 16, 16, 0, 0, 1, 0, 32, 0});
        samples.put("mkv-webm", new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x9F, 0x42, (byte) 0x82, (byte) 0x84, 'w', 'e', 'b', 'm'});
        samples.put("mkv", new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0xA3, 0x42, (byte) 0x82, (byte) 0x88, 'm', 'a', 't', 'r', 'o', 's', 'k', 'a'});
        samples.put("avi", new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'A', 'V', 'I', ' ', 'L', 'I', 'S', 'T'});
        samples.put("mpeg-ts", tsPackets());
        samples.put("zero-prefixed-jpeg", new byte[]{0, 0, 0, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});

        Path dir = Files.createTempDirectory("mime-table");
        Path tmp = Files.createTempFile(dir, "upload-", ".tmp");
        StringBuilder table = new StringBuilder();
        Tika tikaForName = new Tika();
        for (Map.Entry<String, byte[]> sample : samples.entrySet()) {
            Files.write(tmp, sample.getValue());
            String mime = fileContentDetector.detectMimeType(tmp);
            table.append("MIME-TABLE ").append(sample.getKey()).append(" -> ").append(mime)
                    .append(" -> ").append(FileType.fromMimeType(mime)).append(" | ").append(tikaForName.detect(sample.getValue())).append('\n');
        }
        System.out.println(table);
        Files.deleteIfExists(tmp);
        Files.deleteIfExists(dir);
    }

    private byte[] tsPackets() {
        byte[] bytes = new byte[188 * 3];
        for (int i = 0; i < 3; i++) {
            bytes[i * 188] = 0x47;
        }
        return bytes;
    }
}
