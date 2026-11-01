package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.tbcarus.photocloudserver.model.StoredObject;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Физическое дерево проверяется прямым чтением диска: файлы должны быть доступны без приложения и БД.
class PhysicalLibraryTreeIntegrationTest extends LibraryAcceptanceSupport {

    private static final byte[] JPEG = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
    };

    private Long rootId;

    @BeforeEach
    void loadRoot() throws Exception {
        rootId = getRootId(token);
    }

    @AfterEach
    void dropTriggers() {
        for (String table : new String[]{"file_item", "folder"}) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS commit_failure ON " + table);
        }
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS commit_failure()");
    }

    @Test
    void filesAndFoldersKeepUserNamesInRealTree() throws Exception {
        Long documents = createFolder(token, rootId, "Документы").get("id").asLong();
        Long projects = createFolder(token, documents, "Projects 2026").get("id").asLong();

        FileItemDto report = upload(token, "Отчёт.pdf", "text/plain", "report".getBytes(), projects);
        upload(token, "Notes.txt", "text/plain", "notes".getBytes(), rootId);

        assertThat(Files.readString(library("Документы/Projects 2026/Отчёт.pdf"))).isEqualTo("report");
        assertThat(Files.readString(library("Notes.txt"))).isEqualTo("notes");
        assertThat(physicalPath(findFileItem(report.getId()))).isEqualTo(library("Документы/Projects 2026/Отчёт.pdf"));
        StoredObject storedObject = findFileItem(report.getId()).getStoredObject();
        assertThat(storedObject.getFilePath()).isNull();
        assertThat(storedObject.getFilename()).isNull();
        assertThat(storedObject.getFileExtension()).isNull();
        assertLibraryConsistent();
    }

    @Test
    void cameraKeepsOwnStructureSeparatelyFromLibrary() throws Exception {
        FileItemDto photo = upload(token, "IMG_001.jpg", "image/jpeg", JPEG);

        StoredObject storedObject = findFileItem(photo.getId()).getStoredObject();
        assertThat(storedObject.getFilePath()).startsWith(userId + "/camera/");
        assertThat(storedObject.getFilename()).matches("IMG_001\\.jpg_[0-9a-f-]{36}\\.jpg");
        assertThat(Files.exists(storageRoot().resolve(userId + "/files"))).isFalse();
    }

    @Test
    void occupiedPhysicalNameIsNotTakenOverByFolderOrFile() throws Exception {
        Files.createDirectories(library("Orphan"));
        Files.writeString(library("Orphan/keep.txt"), "keep");
        Files.writeString(library("orphan.bin"), "keep");

        assertThat(status(jsonCall(post("/api/v1/folders"), Map.of("parentId", rootId, "name", "Orphan")))).isEqualTo(409);
        assertThat(uploadCall("orphan.bin", "x".getBytes(), rootId).call().getStatus()).isEqualTo(409);

        assertThat(Files.readString(library("Orphan/keep.txt"))).isEqualTo("keep");
        assertThat(Files.readString(library("orphan.bin"))).isEqualTo("keep");
        assertThat(count("SELECT count(*) FROM folder WHERE name = 'Orphan'")).isZero();
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void renameFileChangesPhysicalNameAndKeepsId() throws Exception {
        FileItemDto file = upload(token, "old.txt", "text/plain", "content".getBytes(), rootId);

        assertThat(status(jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "new name.txt")))).isEqualTo(200);

        assertThat(names(library(""))).containsExactly("new name.txt");
        assertThat(findFileItem(file.getId()).getOriginalName()).isEqualTo("new name.txt");
        assertThat(download(file.getId())).isEqualTo("content".getBytes());
        assertLibraryConsistent();
    }

    @Test
    void caseOnlyRenameChangesRealNameOnDisk() throws Exception {
        FileItemDto file = upload(token, "report.txt", "text/plain", "content".getBytes(), rootId);
        Long folder = createFolder(token, rootId, "archive").get("id").asLong();

        assertThat(status(jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "REPORT.txt")))).isEqualTo(200);
        assertThat(status(jsonCall(patch("/api/v1/folders/{id}", folder), Map.of("name", "Archive")))).isEqualTo(200);

        assertThat(names(library(""))).containsExactlyInAnyOrder("REPORT.txt", "Archive");
        assertLibraryConsistent();
    }

    @Test
    void renameIntoNameOccupiedOnlyOnDiskDoesNotReplaceIt() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "content".getBytes(), rootId);
        Files.writeString(library("busy.txt"), "foreign");

        assertThat(status(jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "busy.txt")))).isEqualTo(409);

        assertThat(Files.readString(library("busy.txt"))).isEqualTo("foreign");
        assertThat(Files.readString(library("a.txt"))).isEqualTo("content");
        assertThat(findFileItem(file.getId()).getOriginalName()).isEqualTo("a.txt");
    }

    @Test
    void moveFileChangesLocationAndKeepsId() throws Exception {
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto file = upload(token, "a.txt", "text/plain", "content".getBytes(), rootId);

        assertThat(status(jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", target)))).isEqualTo(200);

        assertThat(Files.exists(library("a.txt"))).isFalse();
        assertThat(Files.readString(library("Target/a.txt"))).isEqualTo("content");
        assertThat(findFileItem(file.getId()).getFolder().getId()).isEqualTo(target);
        assertLibraryConsistent();
    }

    @Test
    void moveFileIntoNameOccupiedOnlyOnDiskDoesNotReplaceIt() throws Exception {
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto file = upload(token, "a.txt", "text/plain", "content".getBytes(), rootId);
        Files.writeString(library("Target/a.txt"), "foreign");

        assertThat(status(jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", target)))).isEqualTo(409);

        assertThat(Files.readString(library("Target/a.txt"))).isEqualTo("foreign");
        assertThat(Files.readString(library("a.txt"))).isEqualTo("content");
        assertThat(findFileItem(file.getId()).getFolder().getId()).isEqualTo(rootId);
    }

    @Test
    void renameFolderCarriesWholeSubtreeWithoutTouchingChildRecords() throws Exception {
        Long parent = createFolder(token, rootId, "Parent").get("id").asLong();
        Long child = createFolder(token, parent, "Child").get("id").asLong();
        FileItemDto file = upload(token, "deep.txt", "text/plain", "deep".getBytes(), child);

        assertThat(status(jsonCall(patch("/api/v1/folders/{id}", parent), Map.of("name", "Renamed")))).isEqualTo(200);

        assertThat(Files.exists(library("Parent"))).isFalse();
        assertThat(Files.readString(library("Renamed/Child/deep.txt"))).isEqualTo("deep");
        assertThat(findFileItem(file.getId()).getFolder().getId()).isEqualTo(child);
        assertThat(download(file.getId())).isEqualTo("deep".getBytes());
        assertLibraryConsistent();
    }

    @Test
    void moveFolderCarriesWholeSubtreeAndRejectsCycle() throws Exception {
        Long parent = createFolder(token, rootId, "Parent").get("id").asLong();
        Long child = createFolder(token, parent, "Child").get("id").asLong();
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto file = upload(token, "deep.txt", "text/plain", "deep".getBytes(), child);

        assertThat(status(jsonCall(post("/api/v1/folders/{id}/move", parent), Map.of("targetParentId", child)))).isEqualTo(400);
        assertThat(status(jsonCall(post("/api/v1/folders/{id}/move", parent), Map.of("targetParentId", target)))).isEqualTo(200);

        assertThat(Files.readString(library("Target/Parent/Child/deep.txt"))).isEqualTo("deep");
        assertThat(download(file.getId())).isEqualTo("deep".getBytes());
        assertLibraryConsistent();
    }

    @Test
    void folderTargetOccupiedOnlyOnDiskIsNotReplacedEvenWhenEmpty() throws Exception {
        Long folder = createFolder(token, rootId, "Dir").get("id").asLong();
        Files.createDirectories(library("Occupied"));

        assertThat(status(jsonCall(patch("/api/v1/folders/{id}", folder), Map.of("name", "Occupied")))).isEqualTo(409);

        assertThat(Files.isDirectory(library("Dir"))).isTrue();
        assertThat(folderRepository.findById(folder).orElseThrow().getName()).isEqualTo("Dir");
    }

    @Test
    void uploadIntoFolderUsesPathOfItsCurrentParents() throws Exception {
        Long parent = createFolder(token, rootId, "Parent").get("id").asLong();
        Long child = createFolder(token, parent, "Child").get("id").asLong();
        jsonCall(patch("/api/v1/folders/{id}", parent), Map.of("name", "Renamed")).call();

        upload(token, "a.txt", "text/plain", "x".getBytes(), child);

        assertThat(Files.exists(library("Renamed/Child/a.txt"))).isTrue();
        assertLibraryConsistent();
    }

    @Test
    void libraryCopyIsIndependentInBothDirections() throws Exception {
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto source = upload(token, "a.txt", "text/plain", "content".getBytes(), rootId);

        Long copy = id(jsonCall(post("/api/v1/files/{id}/copy", source.getId()), copyBody(target, null)).call());
        assertThat(Files.readString(library("Target/a.txt"))).isEqualTo("content");
        Long sameFolderCopy = id(jsonCall(post("/api/v1/files/{id}/copy", source.getId()), copyBody(rootId, "b.txt")).call());
        assertThat(Files.readString(library("b.txt"))).isEqualTo("content");

        assertThat(status(jsonCall(delete("/api/v1/files/{id}", copy), null))).isEqualTo(204);
        assertThat(Files.readString(library("a.txt"))).isEqualTo("content");
        assertThat(status(jsonCall(delete("/api/v1/files/{id}", source.getId()), null))).isEqualTo(204);
        assertThat(Files.readString(library("b.txt"))).isEqualTo("content");
        assertThat(findFileItem(sameFolderCopy).getOriginalName()).isEqualTo("b.txt");
        pendingDeleteCleaner.clean();
        assertLibraryConsistent();
    }

    @Test
    void cameraCopyBecomesLibraryFileWithoutUuidAndStaysIndependent() throws Exception {
        Long target = createFolder(token, rootId, "Photos").get("id").asLong();
        FileItemDto photo = upload(token, "IMG_001.jpg", "image/jpeg", JPEG);
        Path cameraFile = physicalPath(findFileItem(photo.getId()));

        Long copy = id(jsonCall(post("/api/v1/files/{id}/copy", photo.getId()), copyBody(target, null)).call());

        assertThat(Files.readAllBytes(library("Photos/IMG_001.jpg"))).isEqualTo(JPEG);
        assertThat(findFileItem(copy).getStoredObject().getFilePath()).isNull();
        assertThat(status(jsonCall(delete("/api/v1/files/{id}", copy), null))).isEqualTo(204);
        pendingDeleteCleaner.clean();
        assertThat(Files.readAllBytes(cameraFile)).isEqualTo(JPEG);
        assertThat(status(jsonCall(get("/api/v1/files/{id}/download", photo.getId()), null))).isEqualTo(200);
        assertLibraryConsistent();
    }

    @Test
    void cameraRenameStaysLogical() throws Exception {
        FileItemDto photo = upload(token, "IMG.jpg", "image/jpeg", JPEG);
        Path before = physicalPath(findFileItem(photo.getId()));

        assertThat(status(jsonCall(patch("/api/v1/files/{id}", photo.getId()), Map.of("originalName", "holiday.jpg")))).isEqualTo(200);

        assertThat(physicalPath(findFileItem(photo.getId()))).isEqualTo(before);
        assertThat(Files.exists(before)).isTrue();
    }

    @Test
    void busyFileRejectsFolderRenameWithoutChangingAnything() throws Exception {
        Long folder = createFolder(token, rootId, "Busy").get("id").asLong();
        upload(token, "open.txt", "text/plain", "x".getBytes(), folder);

        try (FileInputStream open = new FileInputStream(library("Busy/open.txt").toFile())) {
            assertThat(status(jsonCall(patch("/api/v1/folders/{id}", folder), Map.of("name", "Other")))).isEqualTo(409);
            assertThat(open.read()).isEqualTo('x');
        }

        assertThat(folderRepository.findById(folder).orElseThrow().getName()).isEqualTo("Busy");
        assertThat(status(jsonCall(patch("/api/v1/folders/{id}", folder), Map.of("name", "Other")))).isEqualTo(200);
        assertLibraryConsistent();
    }

    @Test
    void readingFileThroughNioDoesNotBlockItsRename() throws Exception {
        FileItemDto file = upload(token, "open.txt", "text/plain", "content".getBytes(), rootId);

        try (var open = Files.newInputStream(library("open.txt"))) {
            assertThat(status(jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "moved.txt")))).isEqualTo(200);
            assertThat(open.readAllBytes()).isEqualTo("content".getBytes());
        }
        assertLibraryConsistent();
    }

    @Test
    void commitFailureReturnsRenamedFileToOldName() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "content".getBytes(), rootId);
        failOnCommit("file_item", "UPDATE");

        assertThat(status(jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "b.txt")))).isGreaterThanOrEqualTo(500);

        assertThat(Files.readString(library("a.txt"))).isEqualTo("content");
        assertThat(Files.exists(library("b.txt"))).isFalse();
        assertThat(findFileItem(file.getId()).getOriginalName()).isEqualTo("a.txt");
    }

    @Test
    void commitFailureReturnsMovedFileToOldFolder() throws Exception {
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto file = upload(token, "a.txt", "text/plain", "content".getBytes(), rootId);
        failOnCommit("file_item", "UPDATE");

        assertThat(status(jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", target)))).isGreaterThanOrEqualTo(500);

        assertThat(Files.readString(library("a.txt"))).isEqualTo("content");
        assertThat(findFileItem(file.getId()).getFolder().getId()).isEqualTo(rootId);
    }

    @Test
    void commitFailureReturnsRenamedFolderWithContent() throws Exception {
        Long folder = createFolder(token, rootId, "Old").get("id").asLong();
        upload(token, "a.txt", "text/plain", "content".getBytes(), folder);
        failOnCommit("folder", "UPDATE");

        assertThat(status(jsonCall(patch("/api/v1/folders/{id}", folder), Map.of("name", "New")))).isGreaterThanOrEqualTo(500);

        assertThat(Files.readString(library("Old/a.txt"))).isEqualTo("content");
        assertThat(Files.exists(library("New"))).isFalse();
    }

    @Test
    void commitFailureRemovesDirectoryOfNewFolder() throws Exception {
        Files.createDirectories(library(""));
        failOnCommit("folder", "INSERT");

        assertThat(status(jsonCall(post("/api/v1/folders"), Map.of("parentId", rootId, "name", "New")))).isGreaterThanOrEqualTo(500);

        assertThat(Files.exists(library("New"))).isFalse();
    }

    @Test
    void missingParentDirectoryIsReportedInsteadOfBeingRecreated() throws Exception {
        Long folder = createFolder(token, rootId, "Lost").get("id").asLong();
        Files.delete(library("Lost"));

        assertThat(uploadCall("a.txt", "x".getBytes(), folder).call().getStatus()).isEqualTo(500);

        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(Files.exists(library("Lost"))).isFalse();
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void commitFailureKeepsPublishedUploadAsDocumented() throws Exception {
        failOnCommit("file_item", "INSERT");

        assertThat(uploadCall("a.txt", "content".getBytes(), rootId).call().getStatus()).isEqualTo(500);

        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(Files.readString(library("a.txt"))).isEqualTo("content");
        assertThat(tempFileCount()).isZero();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void junctionInLibraryPathCannotRedirectWritesOutsideStorage() throws Exception {
        Long folder = createFolder(token, rootId, "Redirected").get("id").asLong();
        Path outside = Files.createTempDirectory("photo-cloud-outside-");
        try {
            Files.delete(library("Redirected"));
            junction(library("Redirected"), outside);

            assertThat(uploadCall("a.txt", "x".getBytes(), folder).call().getStatus()).isEqualTo(500);

            assertThat(names(outside)).isEmpty();
            assertThat(fileItemRepository.findAll()).isEmpty();
        } finally {
            Files.deleteIfExists(library("Redirected"));
            Files.deleteIfExists(outside);
        }
    }

    private void failOnCommit(String table, String operation) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION commit_failure() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'commit failure' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER commit_failure AFTER " + operation + " ON " + table
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION commit_failure()");
    }

    private void junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
    }

    private byte[] download(Long fileId) throws Exception {
        return mockMvc.perform(get("/api/v1/files/{id}/download", fileId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private Path library(String relative) {
        Path files = storageRoot().resolve(userId + "/files");
        return relative.isEmpty() ? files : files.resolve(relative);
    }

    private List<String> names(Path directory) throws Exception {
        try (var stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).toList();
        }
    }

    // Исключение без обработчика MockMvc пробрасывает как есть; на настоящем сервере это ошибка 5xx.
    private int status(Callable<MockHttpServletResponse> call) {
        try {
            return call.call().getStatus();
        } catch (Exception ex) {
            return 599;
        }
    }
}
