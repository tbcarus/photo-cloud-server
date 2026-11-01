package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Необязательный sort=name списка файлов: порядок задаёт БД до LIMIT/OFFSET, без sort порядок прежний. */
class FileListSortIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";
    private static final byte[] JPEG_HEADER = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00
    };
    private static final List<String> CYRILLIC_THEN_LATIN = List.of(
            "Альбом.pdf", "альбом.txt", "Библиотека.pdf", "Ёж.jpg", "ежедневник.txt",
            "Яблоко.jpg", "яблоко.txt", "Report.pdf", "report.txt");

    @Test
    void sortByNameIgnoresCase() throws Exception {
        String token = login("sort-case@test.local");
        Long folderId = createFolder(token, getRootId(token), "Docs").get("id").asLong();
        for (String name : List.of("banana.txt", "Cherry.txt", "apple.txt", "Date.txt")) {
            uploadText(token, name, folderId);
        }

        JsonNode page = listFiles(token, folderId, 0, 10, "name");

        assertThat(names(page)).containsExactly("apple.txt", "banana.txt", "Cherry.txt", "Date.txt");
    }

    @Test
    void sortByNameAppliesBeforePagination() throws Exception {
        String token = login("sort-pages@test.local");
        Long folderId = createFolder(token, getRootId(token), "Docs").get("id").asLong();
        // Загрузка в обратном порядке: страница из порядка по дате содержала бы другие файлы.
        for (String name : List.of("e.txt", "D.txt", "c.txt", "B.txt", "a.txt")) {
            uploadText(token, name, folderId);
        }

        JsonNode first = listFiles(token, folderId, 0, 2, "name");
        JsonNode second = listFiles(token, folderId, 1, 2, "name");
        JsonNode third = listFiles(token, folderId, 2, 2, "name");

        assertThat(names(first)).containsExactly("a.txt", "B.txt");
        assertThat(names(second)).containsExactly("c.txt", "D.txt");
        assertThat(names(third)).containsExactly("e.txt");
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        assertThat(third.get("hasNext").asBoolean()).isFalse();
        assertThat(third.get("hasPrevious").asBoolean()).isTrue();
        assertThat(first.get("totalElements").asLong()).isEqualTo(5);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    void equalNamesAreOrderedByIdAcrossPages() throws Exception {
        String token = login("sort-ties@test.local");
        // Одинаковые имена допустимы только в Camera; регистр не должен влиять на порядок равных ключей.
        List<Long> ids = new ArrayList<>();
        ids.add(uploadPhoto(token, "IMG.jpg", 1).getId());
        ids.add(uploadPhoto(token, "img.jpg", 2).getId());
        ids.add(uploadPhoto(token, "Img.jpg", 3).getId());
        Long cameraId = findFileItem(ids.get(0)).getFolder().getId();

        List<Long> firstRead = new ArrayList<>(ids(listFiles(token, cameraId, 0, 2, "name")));
        firstRead.addAll(ids(listFiles(token, cameraId, 1, 2, "name")));

        assertThat(firstRead).containsExactlyElementsOf(ids);
    }

    @Test
    void withoutSortFilesKeepDateOrder() throws Exception {
        String token = login("sort-default@test.local");
        Long folderId = createFolder(token, getRootId(token), "Docs").get("id").asLong();
        FileItemDto oldest = uploadText(token, "a.txt", folderId);
        FileItemDto newest = uploadText(token, "b.txt", folderId);
        FileItemDto middle = uploadText(token, "c.txt", folderId);
        FileItemDto tie = uploadText(token, "d.txt", folderId);
        setCapturedAt(oldest, LocalDateTime.of(2020, 1, 1, 12, 0));
        setCapturedAt(newest, LocalDateTime.of(2024, 1, 1, 12, 0));
        setCapturedAt(middle, LocalDateTime.of(2022, 1, 1, 12, 0));
        setCapturedAt(tie, LocalDateTime.of(2022, 1, 1, 12, 0));
        jdbcTemplate.update("UPDATE file_item SET uploaded_at = ?", LocalDateTime.of(2024, 6, 1, 12, 0));

        JsonNode page = listFiles(token, folderId, 0, 10, null);

        // Равные даты: при прежней сортировке новый id идёт первым.
        assertThat(names(page)).containsExactly("b.txt", "d.txt", "c.txt", "a.txt");
    }

    @Test
    void unknownSortIsRejected() throws Exception {
        String token = login("sort-invalid@test.local");
        Long rootId = getRootId(token);

        for (String sort : List.of("size", "NAME", "name,desc", "")) {
            perform(request(token, rootId, 0, 10, sort))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
    }

    @Test
    void sortByNameReturnsOnlyOwnFiles() throws Exception {
        String owner = login("sort-owner@test.local");
        String stranger = login("sort-stranger@test.local");
        Long ownerRoot = getRootId(owner);
        Long strangerRoot = getRootId(stranger);
        uploadText(owner, "own.txt", ownerRoot);
        uploadText(stranger, "foreign.txt", strangerRoot);

        assertThat(names(listFiles(owner, ownerRoot, 0, 10, "name"))).containsExactly("own.txt");
        perform(request(owner, strangerRoot, 0, 10, "name")).andExpect(status().isNotFound());

        // Без folderId контракт прежний: все файлы текущего пользователя и только его.
        perform(get("/api/v1/files")
                        .param("sort", "name")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].originalFilename").value("own.txt"));
    }

    @Test
    void cyrillicNamesFollowAlphabetIgnoringCase() throws Exception {
        String token = login("sort-cyrillic@test.local");
        Long folderId = createFolder(token, getRootId(token), "Документы").get("id").asLong();
        // Порядок загрузки не совпадает с ожидаемым и с порядком по коду символа.
        for (String name : List.of("яблоко.txt", "Report.pdf", "Ёж.jpg", "альбом.txt", "Яблоко.jpg",
                "ежедневник.txt", "report.txt", "Библиотека.pdf", "Альбом.pdf")) {
            uploadText(token, name, folderId);
        }

        JsonNode page = listFiles(token, folderId, 0, 20, "name");

        assertThat(names(page)).containsExactlyElementsOf(CYRILLIC_THEN_LATIN);
    }

    @Test
    void yoIsSortedAsVariantOfYeNotAfterYa() throws Exception {
        String token = login("sort-yo@test.local");
        Long folderId = createFolder(token, getRootId(token), "Ё").get("id").asLong();
        for (String name : List.of("Яма.txt", "жук.txt", "ёлка.txt", "ЕЛЬ.txt", "Ёж.txt", "еда.txt", "ель2.txt")) {
            uploadText(token, name, folderId);
        }

        JsonNode page = listFiles(token, folderId, 0, 20, "name");

        // Ё отличается от Е только при равенстве остальных букв: «ёлка» стоит между «еж» и «ель» как «елка».
        assertThat(names(page)).containsExactly(
                "еда.txt", "Ёж.txt", "ёлка.txt", "ЕЛЬ.txt", "ель2.txt", "жук.txt", "Яма.txt");
    }

    @Test
    void cyrillicCaseVariantsAreOrderedByIdAcrossPages() throws Exception {
        String token = login("sort-cyrillic-ties@test.local");
        List<Long> ids = new ArrayList<>();
        ids.add(uploadPhoto(token, "ЁЖИК.jpg", 11).getId());
        ids.add(uploadPhoto(token, "ёжик.jpg", 12).getId());
        ids.add(uploadPhoto(token, "Ёжик.jpg", 13).getId());
        Long cameraId = findFileItem(ids.get(0)).getFolder().getId();

        List<Long> ordered = new ArrayList<>(ids(listFiles(token, cameraId, 0, 2, "name")));
        ordered.addAll(ids(listFiles(token, cameraId, 1, 2, "name")));

        assertThat(ordered).containsExactlyElementsOf(ids);
    }

    @Test
    void mixedAlphabetPagesHaveNoGapsOrDuplicates() throws Exception {
        String token = login("sort-mixed-pages@test.local");
        Long folderId = createFolder(token, getRootId(token), "Смешанная").get("id").asLong();
        List<String> shuffled = new ArrayList<>(CYRILLIC_THEN_LATIN);
        Collections.reverse(shuffled);
        for (String name : shuffled) {
            uploadText(token, name, folderId);
        }

        // Граница страниц проходит внутри групп «Альбом/альбом», «Яблоко/яблоко» и «Report/report».
        for (int size : List.of(1, 2, 4)) {
            List<String> collected = new ArrayList<>();
            List<Long> collectedIds = new ArrayList<>();
            int pages = (CYRILLIC_THEN_LATIN.size() + size - 1) / size;
            for (int page = 0; page < pages; page++) {
                JsonNode current = listFiles(token, folderId, page, size, "name");
                collected.addAll(names(current));
                collectedIds.addAll(ids(current));
                assertThat(current.get("totalElements").asLong()).isEqualTo(CYRILLIC_THEN_LATIN.size());
                assertThat(current.get("hasNext").asBoolean()).isEqualTo(page < pages - 1);
            }
            assertThat(collected).containsExactlyElementsOf(CYRILLIC_THEN_LATIN);
            assertThat(collectedIds).doesNotHaveDuplicates();
        }
    }

    @Test
    void cameraWithoutSortKeepsDateOrderForCyrillicNames() throws Exception {
        String token = login("sort-camera-date@test.local");
        FileItemDto older = uploadPhoto(token, "Альбом.jpg", 21);
        FileItemDto newer = uploadPhoto(token, "Яблоко.jpg", 22);
        FileItemDto newest = uploadPhoto(token, "Ёж.jpg", 23);
        Long cameraId = findFileItem(older.getId()).getFolder().getId();
        setCapturedAt(older, LocalDateTime.of(2020, 1, 1, 12, 0));
        setCapturedAt(newer, LocalDateTime.of(2021, 1, 1, 12, 0));
        setCapturedAt(newest, LocalDateTime.of(2022, 1, 1, 12, 0));

        JsonNode page = listFiles(token, cameraId, 0, 10, null);

        assertThat(names(page)).containsExactly("Ёж.jpg", "Яблоко.jpg", "Альбом.jpg");
    }

    @Test
    void folderChildrenUseSameAlphabetAsFiles() throws Exception {
        String token = login("sort-folders@test.local");
        Long parentId = createFolder(token, getRootId(token), "Родитель").get("id").asLong();
        // «Альбом» и «альбом» в одной папке запрещены уникальным индексом, поэтому регистр проверяет «альбомы».
        for (String name : List.of("Яблоко", "Report", "альбомы", "Ёж", "Библиотека", "Альбом")) {
            createFolder(token, parentId, name);
        }

        MvcResult result = perform(get("/api/v1/folders/{id}/children", parentId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        List<String> names = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .forEach(folder -> names.add(folder.get("name").asText()));
        assertThat(names).containsExactly("Альбом", "альбомы", "Библиотека", "Ёж", "Яблоко", "Report");
    }

    @Test
    void nameCollationDoesNotDependOnDatabaseLocale() {
        Map<String, Object> locale = jdbcTemplate.queryForMap("""
                SELECT version() AS version, datlocprovider, datcollate, datctype
                FROM pg_database WHERE datname = current_database()
                """);
        System.out.println("SORT-ENV " + locale);

        // В collation C нет ни алфавита, ни кириллического регистра: порядок получен не случайно от неё.
        assertThat(jdbcTemplate.queryForObject("SELECT lower('ЁЖ' COLLATE \"C\")", String.class)).isEqualTo("ЁЖ");
        assertThat(jdbcTemplate.queryForObject("SELECT 'ё' COLLATE \"C\" > 'я' COLLATE \"C\"", Boolean.class)).isTrue();

        assertThat(jdbcTemplate.queryForObject("SELECT lower('ЁЖ' COLLATE \"ru-x-icu\")", String.class)).isEqualTo("ёж");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT 'ё' COLLATE \"ru-x-icu\" < 'ж' COLLATE \"ru-x-icu\"", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT 'я' COLLATE \"ru-x-icu\" < 'a' COLLATE \"ru-x-icu\"", Boolean.class)).isTrue();
    }

    private String login(String email) throws Exception {
        createUser(email, PASSWORD);
        return loginAndGetAccessToken(email, PASSWORD);
    }

    private FileItemDto uploadText(String token, String name, Long folderId) throws Exception {
        return upload(token, name, "text/plain", ("content of " + name).getBytes(), folderId);
    }

    // Разные байты: в Camera файл с тем же checksum вернулся бы как существующий.
    private FileItemDto uploadPhoto(String token, String name, int variant) throws Exception {
        byte[] bytes = new byte[JPEG_HEADER.length + 3];
        System.arraycopy(JPEG_HEADER, 0, bytes, 0, JPEG_HEADER.length);
        bytes[JPEG_HEADER.length] = (byte) variant;
        bytes[JPEG_HEADER.length + 1] = (byte) 0xFF;
        bytes[JPEG_HEADER.length + 2] = (byte) 0xD9;
        return upload(token, name, "image/jpeg", bytes);
    }

    private void setCapturedAt(FileItemDto file, LocalDateTime capturedAt) {
        jdbcTemplate.update("UPDATE file_item SET captured_at = ? WHERE id = ?", capturedAt, file.getId());
    }

    private JsonNode listFiles(String token, Long folderId, int page, int size, String sort) throws Exception {
        MvcResult result = perform(request(token, folderId, page, size, sort))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder request(String token, Long folderId, int page, int size, String sort) {
        MockHttpServletRequestBuilder request = get("/api/v1/files")
                .param("folderId", folderId.toString())
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size))
                .header(HttpHeaders.AUTHORIZATION, authHeader(token));
        return sort == null ? request : request.param("sort", sort);
    }

    private List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(item -> names.add(item.get("originalFilename").asText()));
        return names;
    }

    private List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }
}
