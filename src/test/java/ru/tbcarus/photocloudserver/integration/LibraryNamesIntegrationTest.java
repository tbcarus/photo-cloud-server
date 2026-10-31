package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LibraryNamesIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";
    private static final byte[] JPEG_BYTES = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
    };

    private String token;
    private Long rootId;
    private Long folderId;

    @BeforeEach
    void createUserWithFolder() throws Exception {
        createUser("names@test.local", PASSWORD);
        token = loginAndGetAccessToken("names@test.local", PASSWORD);
        rootId = getRootId(token);
        folderId = createFolder(token, rootId, "Docs").get("id").asLong();
    }

    @Test
    void sameBytesUnderDifferentNamesInOneFolderAreDifferentFiles() throws Exception {
        byte[] bytes = "same bytes".getBytes();

        FileItemDto first = upload(token, "a.txt", "text/plain", bytes, folderId);
        FileItemDto second = upload(token, "b.txt", "text/plain", bytes, folderId);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getOriginalFilename()).isEqualTo("b.txt");
        assertThat(findFileItem(second.getId()).getStoredObject().getId())
                .isNotEqualTo(findFileItem(first.getId()).getStoredObject().getId());
        assertThat(storageFileCount()).isEqualTo(2);
    }

    @Test
    void sameNameAndChecksumIsIdempotentWithoutNewObjects() throws Exception {
        byte[] bytes = "repeat".getBytes();

        FileItemDto first = upload(token, "a.txt", "text/plain", bytes, folderId);
        FileItemDto repeated = upload(token, "a.txt", "text/plain", bytes, folderId);

        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(storedObjectRepository.findAll()).hasSize(1);
        assertThat(storageFileCount()).isEqualTo(1);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void sameNameWithOtherContentConflictsAndKeepsExistingFile() throws Exception {
        FileItemDto first = upload(token, "a.txt", "text/plain", "one".getBytes(), folderId);

        uploadRequest("a.txt", "two".getBytes(), folderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(findFileItem(first.getId()).getChecksum()).isEqualTo(sha256("one".getBytes()));
        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(storageFileCount()).isEqualTo(1);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void namesCollideRegardlessOfCaseOnRealPostgres() throws Exception {
        upload(token, "Report.docx", "text/plain", "one".getBytes(), folderId);
        upload(token, "ОТЧЁТ.txt", "text/plain", "one".getBytes(), folderId);
        createFolder(token, folderId, "ÄPFEL");

        uploadRequest("REPORT.docx", "two".getBytes(), folderId).andExpect(status().isConflict());
        uploadRequest("отчёт.TXT", "two".getBytes(), folderId).andExpect(status().isConflict());
        createFolderRequest("äpfel", folderId).andExpect(status().isConflict());
        createFolderRequest("Отчёт.txt", folderId).andExpect(status().isConflict());

        // Имя с другим расширением или в другой папке — другое имя.
        upload(token, "Report.pdf", "text/plain", "two".getBytes(), folderId);
        upload(token, "Report.docx", "text/plain", "two".getBytes(), rootId);
    }

    @Test
    void repeatedContentUnderOtherCaseReturnsExistingFile() throws Exception {
        FileItemDto first = upload(token, "Report.docx", "text/plain", "one".getBytes(), folderId);

        FileItemDto repeated = upload(token, "REPORT.DOCX", "text/plain", "one".getBytes(), folderId);

        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(repeated.getOriginalFilename()).isEqualTo("Report.docx");
    }

    @Test
    void fileAndFolderShareNamespaceInBothDirections() throws Exception {
        createFolder(token, folderId, "report");
        upload(token, "Notes", "text/plain", "one".getBytes(), folderId);

        uploadRequest("Report", "two".getBytes(), folderId).andExpect(status().isConflict());
        createFolderRequest("notes", folderId).andExpect(status().isConflict());

        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(storageFileCount()).isEqualTo(1);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void renameIntoOccupiedNameConflictsAndChangesNothing() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "one".getBytes(), folderId);
        upload(token, "b.txt", "text/plain", "two".getBytes(), folderId);
        Long child = createFolder(token, folderId, "Child").get("id").asLong();

        renameFile(file.getId(), "B.TXT").andExpect(status().isConflict());
        renameFile(file.getId(), "CHILD").andExpect(status().isConflict());
        renameFolder(child, "A.txt").andExpect(status().isConflict());

        assertThat(findFileItem(file.getId()).getOriginalName()).isEqualTo("a.txt");
        assertThat(folderRepository.findById(child).orElseThrow().getName()).isEqualTo("Child");
    }

    @Test
    void moveIntoOccupiedNameConflictsAndKeepsParent() throws Exception {
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        upload(token, "SAME.txt", "text/plain", "taken".getBytes(), target);
        createFolder(token, target, "BUSY");
        FileItemDto file = upload(token, "same.txt", "text/plain", "one".getBytes(), folderId);
        FileItemDto folderNamedFile = upload(token, "busy", "text/plain", "two".getBytes(), folderId);
        Long folder = createFolder(token, folderId, "same.TXT.dir").get("id").asLong();
        Long busyFolder = createFolder(token, rootId, "busy").get("id").asLong();

        moveFile(file.getId(), target).andExpect(status().isConflict());
        moveFile(folderNamedFile.getId(), target).andExpect(status().isConflict());
        moveFolder(busyFolder, target).andExpect(status().isConflict());
        renameFolder(folder, "same.txt").andExpect(status().isConflict());

        assertThat(findFileItem(file.getId()).getFolder().getId()).isEqualTo(folderId);
        assertThat(findFileItem(folderNamedFile.getId()).getFolder().getId()).isEqualTo(folderId);
        assertThat(folderRepository.findById(busyFolder).orElseThrow().getParent().getId()).isEqualTo(rootId);
    }

    @Test
    void moveAcrossFolderOfSameChecksumUnderOtherNameIsAllowed() throws Exception {
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        upload(token, "a.txt", "text/plain", "same".getBytes(), target);
        FileItemDto file = upload(token, "b.txt", "text/plain", "same".getBytes(), folderId);

        moveFile(file.getId(), target).andExpect(status().isOk());

        assertThat(findFileItem(file.getId()).getFolder().getId()).isEqualTo(target);
    }

    @Test
    void copyChecksNameInBothTablesAndLeavesNothingAfterConflict() throws Exception {
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        upload(token, "Taken.txt", "text/plain", "taken".getBytes(), target);
        createFolder(token, target, "Dir");
        FileItemDto source = upload(token, "source.txt", "text/plain", "source".getBytes(), folderId);
        long filesBefore = storageFileCount();

        copyFile(source.getId(), target, "TAKEN.TXT").andExpect(status().isConflict());
        copyFile(source.getId(), target, "dir").andExpect(status().isConflict());
        copyFile(source.getId(), null, "SOURCE.txt").andExpect(status().isConflict());

        assertThat(storageFileCount()).isEqualTo(filesBefore);
        assertThat(tempFileCount()).isZero();
        assertThat(fileItemRepository.findAll()).hasSize(2);

        // Имя свободно: checksum копии в той же папке не мешает.
        copyFile(source.getId(), target, "copy.txt").andExpect(status().isOk());
    }

    @Test
    void renameChangingOnlyCaseKeepsSameObjects() throws Exception {
        FileItemDto file = upload(token, "report.docx", "text/plain", "one".getBytes(), folderId);
        Long child = createFolder(token, folderId, "child").get("id").asLong();

        renameFile(file.getId(), "Report.docx")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(file.getId()))
                .andExpect(jsonPath("$.originalFilename").value("Report.docx"));
        renameFolder(child, "CHILD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(child))
                .andExpect(jsonPath("$.name").value("CHILD"));
    }

    @Test
    void renameAndMoveKeepIds() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "one".getBytes(), folderId);
        Long child = createFolder(token, folderId, "Child").get("id").asLong();
        Long target = createFolder(token, rootId, "Target").get("id").asLong();

        moveFolder(child, target).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(child));
        moveFile(file.getId(), child).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(file.getId()));

        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(findFileItem(file.getId()).getFolder().getParent().getId()).isEqualTo(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\tb", "a\u0001b",
            ".", "..", "name.", "name ", "name. ", "CON", "con", "PRN.txt", "aux.tar.gz", "NUL", "COM1", "com9.log", "LPT1", "lpt9.txt"})
    void invalidNameIsRejectedEverywhereWithoutCorrection(String name) throws Exception {
        FileItemDto file = upload(token, "file.txt", "text/plain", "one".getBytes(), folderId);
        Long folder = createFolder(token, folderId, "Folder").get("id").asLong();
        long filesBefore = storageFileCount();

        uploadRequest(name, "two".getBytes(), folderId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        createFolderRequest(name, folderId).andExpect(status().isBadRequest());
        renameFile(file.getId(), name).andExpect(status().isBadRequest());
        renameFolder(folder, name).andExpect(status().isBadRequest());
        copyFile(file.getId(), folderId, name).andExpect(status().isBadRequest());

        assertThat(findFileItem(file.getId()).getOriginalName()).isEqualTo("file.txt");
        assertThat(folderRepository.findById(folder).orElseThrow().getName()).isEqualTo("Folder");
        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(storageFileCount()).isEqualTo(filesBefore);
        assertThat(tempFileCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {".nomedia", ".gitignore", " leading space", "with space.txt", "CONSOLE.txt", "COM10", "a.CON",
            "файл №1.txt", "日本語.txt", "emoji😀.txt", "no-extension"})
    void validNameIsStoredExactly(String name) throws Exception {
        FileItemDto file = upload(token, name, "text/plain", "one".getBytes(), folderId);
        JsonNode folder = createFolder(token, rootId, name);

        assertThat(file.getOriginalFilename()).isEqualTo(name);
        assertThat(folder.get("name").asText()).isEqualTo(name);
    }

    @Test
    void nameLengthIsLimitedBy255BytesOfUtf8WithoutTruncation() throws Exception {
        String ascii255 = "a".repeat(251) + ".txt";
        String cyrillic255 = "я".repeat(125) + "x.txt";
        String cyrillic256 = "я".repeat(126) + ".txt";
        assertThat(ascii255.getBytes(StandardCharsets.UTF_8)).hasSize(255);
        assertThat(cyrillic255.getBytes(StandardCharsets.UTF_8)).hasSize(255);
        assertThat(cyrillic256.getBytes(StandardCharsets.UTF_8)).hasSize(256);

        assertThat(upload(token, ascii255, "text/plain", "one".getBytes(), folderId).getOriginalFilename()).isEqualTo(ascii255);
        assertThat(createFolder(token, folderId, cyrillic255).get("name").asText()).isEqualTo(cyrillic255);
        uploadRequest(cyrillic256, "two".getBytes(), folderId).andExpect(status().isBadRequest());
        createFolderRequest(cyrillic256, folderId).andExpect(status().isBadRequest());
        uploadRequest("a".repeat(252) + ".txt", "two".getBytes(), folderId).andExpect(status().isBadRequest());
    }

    @Test
    void nonNfcNameIsRejectedAndNfcNameIsAccepted() throws Exception {
        String nfc = "Йод.txt";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo(nfc);
        FileItemDto file = upload(token, nfc, "text/plain", "one".getBytes(), folderId);

        // Другое представление того же имени не исправляется и не считается отдельным файлом.
        uploadRequest(nfd, "two".getBytes(), folderId).andExpect(status().isBadRequest());
        createFolderRequest(nfd, folderId).andExpect(status().isBadRequest());
        renameFile(file.getId(), nfd).andExpect(status().isBadRequest());

        assertThat(findFileItem(file.getId()).getOriginalName()).isEqualTo(nfc);
        assertThat(fileItemRepository.findAll()).hasSize(1);
    }

    @Test
    void cameraNameIsReservedInRootForFilesEvenBeforeCameraExists() throws Exception {
        assertThat(folderRepository.findAll()).extracting("folderType").doesNotContain(FolderType.CAMERA);

        uploadRequest("Camera", "one".getBytes(), rootId).andExpect(status().isConflict());
        createFolderRequest("CAMERA", rootId).andExpect(status().isConflict());
        FileItemDto nested = upload(token, "camera", "text/plain", "one".getBytes(), folderId);
        renameFile(nested.getId(), "Camera").andExpect(status().isOk());
        moveFile(nested.getId(), rootId).andExpect(status().isConflict());
        Long nestedFolder = createFolder(token, folderId, "cAmErA.dir").get("id").asLong();
        renameFolder(nestedFolder, "camera2").andExpect(status().isOk());

        // Системная Camera создаётся свободно: имя никем не занято.
        FileItemDto photo = upload(token, "photo.jpg", "image/jpeg", JPEG_BYTES);
        assertThat(findFileItem(photo.getId()).getFolder().getFolderType()).isEqualTo(FolderType.CAMERA);
        assertThat(findFileItem(photo.getId()).getFolder().getName()).isEqualTo("Camera");
    }

    @Test
    void filesInRootFollowLibraryRulesAndNoSystemFolderIsCreated() throws Exception {
        FileItemDto file = upload(token, "root.txt", "text/plain", "one".getBytes(), rootId);
        uploadRequest("ROOT.TXT", "two".getBytes(), rootId).andExpect(status().isConflict());

        assertThat(file.getFolderId()).isEqualTo(rootId);
        assertThat(folderRepository.findAll()).extracting("folderType")
                .containsExactlyInAnyOrder(FolderType.ROOT, FolderType.USER);
    }

    @Test
    void cameraKeepsItsOwnRules() throws Exception {
        byte[] otherJpeg = JPEG_BYTES.clone();
        otherJpeg[otherJpeg.length - 3] = 0x01;

        FileItemDto first = upload(token, "IMG_0001.jpg", "image/jpeg", JPEG_BYTES);
        FileItemDto sameNameOtherContent = upload(token, "IMG_0001.jpg", "image/jpeg", otherJpeg);
        FileItemDto sameContentOtherName = upload(token, "copy of IMG.jpg", "image/jpeg", JPEG_BYTES);
        Long cameraId = first.getFolderId();
        // Имя Camera очищается прежним способом: новые правила библиотеки его не отклоняют.
        FileItemDto cleaned = upload(token, "a:b .jpg.", "image/jpeg", thirdJpeg());

        assertThat(sameNameOtherContent.getId()).isNotEqualTo(first.getId());
        assertThat(sameContentOtherName.getId()).isEqualTo(first.getId());
        assertThat(cleaned.getOriginalFilename()).isEqualTo("a_b .jpg.");
        assertThat(findFileItem(first.getId()).getStoredObject().getFilename()).matches("IMG_0001\\.jpg_[0-9a-f-]{36}\\.jpg");
        assertThat(fileItemRepository.findAllByStoredObjectId(findFileItem(first.getId()).getStoredObject().getId())).hasSize(1);

        // Pre-check Camera видит только Camera: тот же checksum в библиотеке на него не влияет.
        byte[] libraryOnly = "library only".getBytes();
        upload(token, "library.txt", "text/plain", libraryOnly, folderId);
        perform(post("/api/v1/files/checksums/exists")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "folderId", cameraId,
                                "checksums", List.of(sha256(JPEG_BYTES), sha256(libraryOnly))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existing[0]").value(sha256(JPEG_BYTES)))
                .andExpect(jsonPath("$.missing[0]").value(sha256(libraryOnly)));

        // Rename в Camera не проверяет занятость имени.
        renameFile(sameNameOtherContent.getId(), "other.jpg").andExpect(status().isOk());
        renameFile(sameNameOtherContent.getId(), "IMG_0001.jpg").andExpect(status().isOk());
    }

    @Test
    void uploadWithExplicitCameraFolderKeepsCameraRoute() throws Exception {
        FileItemDto first = upload(token, "IMG.jpg", "image/jpeg", JPEG_BYTES);

        FileItemDto repeated = upload(token, "IMG.jpg", "image/jpeg", JPEG_BYTES, first.getFolderId());

        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(perform(get("/api/v1/folders/{id}/children", rootId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.folderType == 'CAMERA')].name").value("Camera"))
                .andReturn().getResponse().getContentAsString()).doesNotContain("FILES");
    }

    @Test
    void moveAcrossCameraBoundaryIsRejectedInBothDirections() throws Exception {
        FileItemDto photo = upload(token, "photo.jpg", "image/jpeg", JPEG_BYTES);
        FileItemDto file = upload(token, "file.txt", "text/plain", "one".getBytes(), folderId);
        Long cameraId = photo.getFolderId();

        moveFile(photo.getId(), folderId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        moveFile(file.getId(), cameraId).andExpect(status().isBadRequest());
        moveFile(file.getId(), rootId).andExpect(status().isOk());

        assertThat(findFileItem(photo.getId()).getFolder().getId()).isEqualTo(cameraId);
    }

    @Test
    void copyIsAllowedOnlyFromCameraIntoLibrary() throws Exception {
        FileItemDto photo = upload(token, "photo.jpg", "image/jpeg", JPEG_BYTES);
        FileItemDto file = upload(token, "file.txt", "text/plain", "one".getBytes(), folderId);
        long filesBefore = storageFileCount();

        copyFile(file.getId(), photo.getFolderId(), null).andExpect(status().isBadRequest());
        copyFile(photo.getId(), null, null).andExpect(status().isConflict());
        assertThat(storageFileCount()).isEqualTo(filesBefore);

        copyFile(photo.getId(), folderId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").value(folderId))
                .andExpect(jsonPath("$.originalFilename").value("photo.jpg"));
        // Повторная копия — конфликт имени в библиотеке, а не дубль checksum.
        copyFile(photo.getId(), folderId, null).andExpect(status().isConflict());
        copyFile(photo.getId(), folderId, "photo-2.jpg").andExpect(status().isOk());
    }

    @Test
    void copyOfCameraFileWithNameInvalidForLibraryRequiresExplicitName() throws Exception {
        FileItemDto photo = upload(token, "a:b .jpg.", "image/jpeg", JPEG_BYTES);

        copyFile(photo.getId(), folderId, null).andExpect(status().isBadRequest());
        assertThat(fileItemRepository.findAll()).hasSize(1);

        copyFile(photo.getId(), folderId, "ab.jpg").andExpect(status().isOk());
    }

    private byte[] thirdJpeg() {
        byte[] bytes = JPEG_BYTES.clone();
        bytes[bytes.length - 3] = 0x02;
        return bytes;
    }

    private ResultActions uploadRequest(String name, byte[] bytes, Long targetFolderId) throws Exception {
        return perform(multipart("/api/v1/files")
                .file(filePart(name, "text/plain", bytes))
                .param("folderId", targetFolderId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(token)));
    }

    private ResultActions createFolderRequest(String name, Long parentId) throws Exception {
        return perform(post("/api/v1/folders")
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("parentId", parentId, "name", name))));
    }

    private ResultActions renameFile(Long fileId, String name) throws Exception {
        return perform(patch("/api/v1/files/{id}", fileId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("originalName", name))));
    }

    private ResultActions renameFolder(Long id, String name) throws Exception {
        return perform(patch("/api/v1/folders/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", name))));
    }

    private ResultActions moveFile(Long fileId, Long targetFolderId) throws Exception {
        return perform(post("/api/v1/files/{id}/move", fileId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("targetFolderId", targetFolderId))));
    }

    private ResultActions moveFolder(Long id, Long targetParentId) throws Exception {
        return perform(post("/api/v1/folders/{id}/move", id)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("targetParentId", targetParentId))));
    }

    private ResultActions copyFile(Long fileId, Long targetFolderId, String name) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("targetFolderId", targetFolderId);
        body.put("originalName", name);
        return perform(post("/api/v1/files/{id}/copy", fileId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
