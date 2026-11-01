package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.service.storage.StorageKeyGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Занятый физический путь никогда не заменяется. В библиотеке имя принадлежит пользователю, поэтому ответ 409.
 * В Camera имя содержит UUID: коллизия — внутренняя ошибка хранилища, 500, а не 409 (Android считает его постоянной ошибкой).
 */
class PhysicalStorageConflictIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";
    private static final byte[] JPEG = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
    };

    // Spy подменяет только имя файла Camera: так совпадение физического пути воспроизводится детерминированно.
    @MockitoSpyBean
    StorageKeyGenerator storageKeyGenerator;

    @Test
    void uploadIntoOccupiedLibraryPathConflictsAndKeepsExistingFile() throws Exception {
        User user = createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        Path foreignFile = writeForeignFile(user.getId() + "/files/notes.txt");

        perform(multipart("/api/v1/files/upload")
                        .file(filePart("notes.txt", "text/plain", "notes".getBytes()))
                        .param("folderId", getRootId(token).toString())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(Files.readString(foreignFile)).isEqualTo("foreign");
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(storedObjectRepository.findAll()).isEmpty();
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void uploadIntoOccupiedCameraPathReturnsInternalErrorAndKeepsExistingFile() throws Exception {
        User user = createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        Path foreignFile = writeForeignFile(user.getId() + "/camera/" + LocalDateTime.now().getYear() + "/taken.jpg");
        doReturn("taken.jpg").when(storageKeyGenerator).generateFilename(any(), any());

        perform(multipart("/api/v1/files/upload")
                        .file(filePart("IMG.jpg", "image/jpeg", JPEG))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Physical storage operation failed"));

        assertThat(Files.readString(foreignFile)).isEqualTo("foreign");
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(storedObjectRepository.findAll()).isEmpty();
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void copyIntoOccupiedLibraryPathConflictsAndKeepsExistingFile() throws Exception {
        User user = createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        FileItem source = findFileItem(upload(token, "notes.txt", "text/plain", "notes".getBytes()).getId());
        Long targetFolderId = createUserFolder(token);
        Path foreignFile = writeForeignFile(user.getId() + "/files/CopyTarget/notes.txt");

        perform(post("/api/v1/files/{id}/copy", source.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetFolderId":%d}
                                """.formatted(targetFolderId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(Files.readString(foreignFile)).isEqualTo("foreign");
        assertThat(Files.readAllBytes(physicalPath(source))).isEqualTo("notes".getBytes());
        assertThat(storedObjectRepository.findAll()).hasSize(1);
        assertThat(tempFileCount()).isZero();
    }

    private Path writeForeignFile(String relativePath) throws Exception {
        Path file = storageRoot().resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "foreign");
    }

    private Long createUserFolder(String token) throws Exception {
        String rootJson = perform(get("/api/v1/folders/root")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long rootId = objectMapper.readTree(rootJson).get("id").asLong();
        String folderJson = perform(post("/api/v1/folders")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":%d,"name":"CopyTarget"}
                                """.formatted(rootId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(folderJson).get("id").asLong();
    }
}
