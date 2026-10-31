package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Закрывает то, чего нет в LibraryNamesIntegrationTest: сервисные символы, API-уровень и поведение lower() на PostgreSQL.
class LibraryAcceptanceNamesIntegrationTest extends LibraryAcceptanceSupport {

    private static final byte[] JPEG = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
    };

    private Long rootId;
    private Long folderId;

    @BeforeEach
    void createFolderTree() throws Exception {
        rootId = getRootId(token);
        folderId = createFolder(token, rootId, "Docs").get("id").asLong();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\u007Fb", "a\u0085b", "a\nb", "a\rb", "\uD800", "x\uDC00.txt", "COM0", "lpt0.txt", "COM¹",
            "LPT².txt", "CONIN$", "conout$.log", "CON .txt", "  ", "\t"})
    void serviceCharactersAndDeviceNamesAreRejectedForAllOperations(String name) throws Exception {
        FileItemDto file = upload(token, "file.txt", "text/plain", "one".getBytes(), folderId);
        Long child = createFolder(token, folderId, "Child").get("id").asLong();

        assertThat(status(jsonCall(post("/api/v1/folders"), Map.of("parentId", folderId, "name", name)))).isEqualTo(400);
        assertThat(status(jsonCall(patch("/api/v1/folders/{id}", child), Map.of("name", name)))).isEqualTo(400);
        assertThat(status(jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", name)))).isEqualTo(400);
        assertThat(status(uploadCall(name, "two".getBytes(), folderId))).isEqualTo(400);

        assertThat(findFileItem(file.getId()).getOriginalName()).isEqualTo("file.txt");
        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertLibraryConsistent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000.txt", "a\uD800.txt", "a\uDC00"})
    void copyRejectsServiceCharacters(String name) throws Exception {
        FileItemDto file = upload(token, "file.txt", "text/plain", "one".getBytes(), folderId);

        assertThat(status(jsonCall(post("/api/v1/files/{id}/copy", file.getId()), copyBody(folderId, name)))).isEqualTo(400);
        assertLibraryConsistent();
    }

    @Test
    void emptyUploadNameIsRejectedWithoutPublication() throws Exception {
        assertThat(status(uploadCall("", "two".getBytes(), folderId))).isEqualTo(400);
        assertLibraryConsistent();
    }

    @Test
    void nfdIsRejectedForFolderCopyAndNfcCounterpartStillConflictsByExactForm() throws Exception {
        String nfc = "Ёлка.txt";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        FileItemDto file = upload(token, "file.txt", "text/plain", "one".getBytes(), folderId);
        Long child = createFolder(token, folderId, "Child").get("id").asLong();

        assertThat(status(jsonCall(patch("/api/v1/folders/{id}", child), Map.of("name", nfd)))).isEqualTo(400);
        assertThat(status(jsonCall(post("/api/v1/files/{id}/copy", file.getId()), copyBody(folderId, nfd)))).isEqualTo(400);

        // После отказа NFD имя в БД может быть только NFC: два представления одного имени не сосуществуют.
        upload(token, nfc, "text/plain", "two".getBytes(), folderId);
        assertThat(count("SELECT count(*) FROM file_item WHERE original_name <> normalize(original_name, NFC)")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {" leading.txt", "  two  spaces.txt", "MiXeD.TxT", "dots..in..name.txt", "tail nbsp ", "a~b#c&d(1)[2]{3}.txt",
            "Ёж Ёлка.txt", "ß.txt", "İstanbul.txt", "ﬁ-ligature.txt", "mixedЖ.txt"})
    void validNameKeepsExactBytesAcrossOperations(String name) throws Exception {
        FileItemDto uploaded = upload(token, name, "text/plain", "one".getBytes(), folderId);
        assertThat(uploaded.getOriginalFilename()).isEqualTo(name);
        assertThat(findFileItem(uploaded.getId()).getOriginalName().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(name.getBytes(StandardCharsets.UTF_8));

        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        MockHttpServletResponse copied = jsonCall(post("/api/v1/files/{id}/copy", uploaded.getId()), copyBody(target, null)).call();
        assertThat(objectMapper.readTree(copied.getContentAsString()).get("originalFilename").asText()).isEqualTo(name);

        Long moved = createFolder(token, rootId, name).get("id").asLong();
        assertThat(folderRepository.findById(moved).orElseThrow().getName()).isEqualTo(name);
    }

    @Test
    void duplicateNamesAreIndependentAcrossFoldersAndUsers() throws Exception {
        Long other = createFolder(token, rootId, "Other").get("id").asLong();
        upload(token, "same.txt", "text/plain", "one".getBytes(), folderId);

        upload(token, "same.txt", "text/plain", "two".getBytes(), other);
        upload(token, "SAME.TXT", "text/plain", "three".getBytes(), rootId);
        createFolder(token, createFolder(token, rootId, "Third").get("id").asLong(), "same.txt");

        createUser("second@test.local", PASSWORD);
        String second = loginAndGetAccessToken("second@test.local", PASSWORD);
        upload(second, "same.txt", "text/plain", "one".getBytes(), getRootId(second));

        assertThat(fileItemRepository.findAll()).hasSize(4);
        assertLibraryConsistent();
    }

    @Test
    void copyIntoSameFolderUnderNewNameIsAllowedAndIndependent() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "one".getBytes(), folderId);

        MockHttpServletResponse copy = jsonCall(post("/api/v1/files/{id}/copy", file.getId()), copyBody(folderId, "b.txt")).call();

        assertThat(copy.getStatus()).isEqualTo(200);
        assertThat(findFileItem(id(copy)).getStoredObject().getId()).isNotEqualTo(findFileItem(file.getId()).getStoredObject().getId());
        assertThat(findFileItem(id(copy)).getChecksum()).isEqualTo(findFileItem(file.getId()).getChecksum());
        assertThat(physicalFiles()).hasSize(2);
        assertLibraryConsistent();
    }

    @Test
    void blankCopyNameMeansSourceName() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "one".getBytes(), folderId);
        Long target = createFolder(token, rootId, "Target").get("id").asLong();

        MockHttpServletResponse copy = jsonCall(post("/api/v1/files/{id}/copy", file.getId()), copyBody(target, "   ")).call();

        assertThat(copy.getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(copy.getContentAsString()).get("originalFilename").asText()).isEqualTo("a.txt");
    }

    @Test
    void cameraAllowsSameNamesAndDifferentChecksumsCreateDifferentObjects() throws Exception {
        FileItemDto first = upload(token, "IMG.jpg", "image/jpeg", JPEG);
        byte[] other = JPEG.clone();
        other[other.length - 3] = 7;

        FileItemDto second = upload(token, "IMG.jpg", "image/jpeg", other);
        FileItemDto repeated = upload(token, "IMG.jpg", "image/jpeg", other, first.getFolderId());

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(repeated.getId()).isEqualTo(second.getId());
        assertThat(fileItemRepository.findAll()).hasSize(2);
        assertThat(physicalFiles()).hasSize(2);
        assertLibraryConsistent();
    }

    @Test
    void moveOfCameraFileIntoCameraIsNoOp() throws Exception {
        FileItemDto photo = upload(token, "IMG.jpg", "image/jpeg", JPEG);

        MockHttpServletResponse moved = jsonCall(post("/api/v1/files/{id}/move", photo.getId()), Map.of("targetFolderId", photo.getFolderId())).call();

        assertThat(moved.getStatus()).isEqualTo(200);
        assertThat(findFileItem(photo.getId()).getFolder().getId()).isEqualTo(photo.getFolderId());
    }

    @Test
    void cameraRenameCleansControlCharactersAsBefore() throws Exception {
        FileItemDto photo = upload(token, "IMG.jpg", "image/jpeg", JPEG);

        assertThat(status(jsonCall(patch("/api/v1/files/{id}", photo.getId()), Map.of("originalName", "bad\u0000name.jpg")))).isEqualTo(200);

        assertThat(findFileItem(photo.getId()).getOriginalName()).isEqualTo("bad_name.jpg");
    }

    // Одиночный суррогат доходит до сервера только как JSON-escape: в байтах UTF-8 его не передать.
    @Test
    void loneSurrogateFromJsonEscapeIsRejectedInLibrary() throws Exception {
        FileItemDto file = upload(token, "file.txt", "text/plain", "one".getBytes(), folderId);
        Long child = createFolder(token, folderId, "Child").get("id").asLong();

        assertThat(rawJson(post("/api/v1/folders"), "{\"parentId\":" + folderId + ",\"name\":\"bad\\ud800name\"}")).isEqualTo(400);
        assertThat(rawJson(patch("/api/v1/folders/{id}", child), "{\"name\":\"bad\\ud800name\"}")).isEqualTo(400);
        assertThat(rawJson(patch("/api/v1/files/{id}", file.getId()), "{\"originalName\":\"bad\\ud800name\"}")).isEqualTo(400);
        assertThat(rawJson(post("/api/v1/files/{id}/copy", file.getId()), "{\"originalName\":\"bad\\ud800name\"}")).isEqualTo(400);
    }

    @Test
    void loneSurrogateFromJsonEscapeInCameraRenameAnswersWithoutServerError() throws Exception {
        FileItemDto photo = upload(token, "IMG.jpg", "image/jpeg", JPEG);

        assertThat(rawJson(patch("/api/v1/files/{id}", photo.getId()), "{\"originalName\":\"bad\\ud800name.jpg\"}")).isEqualTo(200);

        // Правила библиотеки на Camera не распространяются: драйвер заменяет символ, ошибки БД нет.
        assertThat(findFileItem(photo.getId()).getOriginalName()).isEqualTo("bad?name.jpg");
    }

    @Test
    void longEmojiNameInCameraUploadIsCutWithoutBreakingCodePoints() throws Exception {
        String name = "😀".repeat(200) + ".jpg";

        assertThat(status(uploadCall(name, JPEG, null))).isEqualTo(200);

        assertThat(fileItemRepository.findAll()).singleElement()
                .satisfies(file -> assertThat(file.getOriginalName().codePoints().noneMatch(code -> code >= 0xD800 && code <= 0xDFFF)).isTrue());
        assertLibraryConsistent();
    }

    // Фиксирует требование к БД: строка прежней системной папки ломает children ROOT, а значит и lookup Camera в Android.
    @Test
    void legacyFilesFolderRowBreaksChildrenOfRoot() throws Exception {
        jdbcTemplate.update("INSERT INTO folder (user_id, parent_id, name, folder_type) VALUES (?, ?, 'Files', 'FILES')", userId, rootId);

        assertThat(status(jsonCall(get("/api/v1/folders/{id}/children", rootId), null))).isEqualTo(400);
    }

    private int rawJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String json) throws Exception {
        builder.header("Authorization", authHeader(token)).contentType("application/json").content(json);
        return status(() -> mockMvc.perform(builder).andReturn().getResponse());
    }

    @Test
    void lowerBehaviourOfTestEnvironmentIsRecorded() {
        Map<String, Object> locale = jdbcTemplate.queryForMap("""
                SELECT version() AS version, datcollate, datctype, datlocprovider
                FROM pg_database WHERE datname = current_database()
                """);
        System.out.println("LOWER-ENV " + locale);
        for (String value : List.of("ОТЧЁТ", "ÄPFEL", "ÉTÉ", "ΣΑΣ", "ẞ", "İ", "I", "ǅ", "K", "Ω", "Å", "Ａ", "𝐀", "Ⅷ", "Ⓐ", "Ꭰ")) {
            System.out.println("LOWER " + value + " -> "
                    + jdbcTemplate.queryForObject("SELECT lower(?)", String.class, value) + " | C: "
                    + jdbcTemplate.queryForObject("SELECT lower(? COLLATE \"C\")", String.class, value));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT lower('ОТЧЁТ')", String.class)).isEqualTo("отчёт");
        assertThat(jdbcTemplate.queryForObject("SELECT lower('ÄPFEL')", String.class)).isEqualTo("äpfel");
        // Зависимость от правил регистра: с collation C кириллица не сворачивается, и два имени стали бы разными.
        assertThat(jdbcTemplate.queryForObject("SELECT lower('ОТЧЁТ' COLLATE \"C\")", String.class)).isEqualTo("ОТЧЁТ");
    }

    @Test
    void namesAndIndexesUseOneComparisonFunction() throws Exception {
        assertThat(jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname IN ('uk_folder_user_parent_lower_name', 'idx_file_item_user_folder_lower_original_name')",
                String.class)).hasSize(2).allMatch(index -> index.contains("lower("));
        assertThat(perform(get("/api/v1/folders/{id}/children", rootId)
                .header("Authorization", authHeader(token))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private int status(java.util.concurrent.Callable<MockHttpServletResponse> call) throws Exception {
        try {
            return call.call().getStatus();
        } catch (Exception ex) {
            // Исключение без обработчика в MockMvc соответствует 500 на настоящем сервере.
            System.out.println("UNHANDLED: " + ex);
            return 599;
        }
    }
}
