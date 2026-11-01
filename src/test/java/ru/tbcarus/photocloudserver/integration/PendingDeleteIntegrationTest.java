package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Удаление: записи и перенос в одной транзакции, очередь работы — сам каталог .pending-delete.
class PendingDeleteIntegrationTest extends LibraryAcceptanceSupport {

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
    void dropTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS commit_failure ON file_item");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS commit_failure ON folder");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS commit_failure()");
    }

    @Test
    void deletedFileLeavesItsNameBeforeResponseAndCleanerRemovesBytes() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "old".getBytes(), rootId);

        assertThat(status(deleteFile(file.getId()))).isEqualTo(204);

        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(storedObjectRepository.findAll()).isEmpty();
        assertThat(Files.exists(library("a.txt"))).isFalse();
        assertThat(Files.readString(pending("file-" + file.getId()))).isEqualTo("old");

        FileItemDto same = upload(token, "a.txt", "text/plain", "new".getBytes(), rootId);
        pendingDeleteCleaner.clean();

        assertThat(pendingDeleteCount()).isZero();
        assertThat(Files.readString(library("a.txt"))).isEqualTo("new");
        assertThat(findFileItem(same.getId()).getOriginalName()).isEqualTo("a.txt");
        assertLibraryConsistent();
    }

    @Test
    void deletingFolderRemovesWholeTreeAndMovesOnlyTopDirectory() throws Exception {
        Long top = createFolder(token, rootId, "Top").get("id").asLong();
        Long nested = createFolder(token, top, "Nested").get("id").asLong();
        Long deep = createFolder(token, nested, "Deep").get("id").asLong();
        Long sibling = createFolder(token, rootId, "Sibling").get("id").asLong();
        upload(token, "one.txt", "text/plain", "1".getBytes(), top);
        upload(token, "two.txt", "text/plain", "2".getBytes(), deep);
        upload(token, "photo.jpg", "image/jpeg", JPEG, deep);
        FileItemDto keep = upload(token, "keep.txt", "text/plain", "keep".getBytes(), sibling);
        Files.writeString(library("Top/Nested/unknown.bin"), "no record");

        assertThat(status(deleteFolder(top))).isEqualTo(204);

        assertThat(folderRepository.findById(top)).isEmpty();
        assertThat(folderRepository.findById(nested)).isEmpty();
        assertThat(folderRepository.findById(deep)).isEmpty();
        assertThat(fileItemRepository.findAll()).extracting("id").containsExactly(keep.getId());
        assertThat(storedObjectRepository.findAll()).hasSize(1);
        assertThat(count("SELECT count(*) FROM file_metadata")).isZero();
        assertThat(Files.exists(library("Top"))).isFalse();
        assertThat(names(pendingDeleteDir())).containsExactly("folder-" + top);
        assertThat(Files.readString(pending("folder-" + top + "/Nested/Deep/two.txt"))).isEqualTo("2");

        pendingDeleteCleaner.clean();

        assertThat(pendingDeleteCount()).isZero();
        assertThat(Files.readString(library("Sibling/keep.txt"))).isEqualTo("keep");
        assertLibraryConsistent();
    }

    @Test
    void folderNameIsFreeImmediatelyAndNewFolderSurvivesCleaning() throws Exception {
        Long old = createFolder(token, rootId, "Dir").get("id").asLong();
        upload(token, "old.txt", "text/plain", "old".getBytes(), old);
        assertThat(status(deleteFolder(old))).isEqualTo(204);

        Long fresh = createFolder(token, rootId, "DIR").get("id").asLong();
        upload(token, "new.txt", "text/plain", "new".getBytes(), fresh);
        pendingDeleteCleaner.clean();

        assertThat(Files.readString(library("DIR/new.txt"))).isEqualTo("new");
        assertLibraryConsistent();
    }

    @Test
    void repeatedDeleteAndDeleteOfChildAfterParentAre404() throws Exception {
        Long top = createFolder(token, rootId, "Top").get("id").asLong();
        Long child = createFolder(token, top, "Child").get("id").asLong();
        FileItemDto file = upload(token, "a.txt", "text/plain", "x".getBytes(), child);

        assertThat(status(deleteFolder(top))).isEqualTo(204);

        assertThat(status(deleteFolder(top))).isEqualTo(404);
        assertThat(status(deleteFolder(child))).isEqualTo(404);
        assertThat(status(deleteFile(file.getId()))).isEqualTo(404);
    }

    @Test
    void systemFoldersCannotBeDeleted() throws Exception {
        Long camera = upload(token, "IMG.jpg", "image/jpeg", JPEG).getFolderId();

        assertThat(status(deleteFolder(rootId))).isEqualTo(400);
        assertThat(status(deleteFolder(camera))).isEqualTo(400);

        assertThat(pendingDeleteCount()).isZero();
        assertThat(fileItemRepository.findAll()).hasSize(1);
    }

    @Test
    void cameraFileUsesSameMechanismAndChecksumBecomesFree() throws Exception {
        FileItemDto photo = upload(token, "IMG.jpg", "image/jpeg", JPEG);
        Path cameraFile = physicalPath(findFileItem(photo.getId()));

        assertThat(status(deleteFile(photo.getId()))).isEqualTo(204);

        assertThat(Files.exists(cameraFile)).isFalse();
        assertThat(precheckExisting(photo.getFolderId(), photo.getChecksum())).isFalse();
        FileItemDto again = upload(token, "IMG.jpg", "image/jpeg", JPEG);
        assertThat(again.getId()).isNotEqualTo(photo.getId());
        pendingDeleteCleaner.clean();
        assertThat(pendingDeleteCount()).isZero();
        assertLibraryConsistent();
    }

    // Сбой после переноса и до COMMIT: записи на месте, объект уже в служебном каталоге.
    @Test
    void cleanerFinishesFileDeleteInterruptedBeforeCommit() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "x".getBytes(), rootId);
        moveToPending(library("a.txt"), "file-" + file.getId());

        pendingDeleteCleaner.clean();

        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(storedObjectRepository.findAll()).isEmpty();
        assertThat(pendingDeleteCount()).isZero();
    }

    @Test
    void cleanerFinishesFolderDeleteInterruptedBeforeCommit() throws Exception {
        Long top = createFolder(token, rootId, "Top").get("id").asLong();
        Long child = createFolder(token, top, "Child").get("id").asLong();
        upload(token, "a.txt", "text/plain", "x".getBytes(), child);
        moveToPending(library("Top"), "folder-" + top);

        pendingDeleteCleaner.clean();

        assertThat(folderRepository.findById(top)).isEmpty();
        assertThat(folderRepository.findById(child)).isEmpty();
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(pendingDeleteCount()).isZero();
    }

    @Test
    void repeatedRequestAfterInterruptedDeleteCompletesWithoutMovingAnything() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "x".getBytes(), rootId);
        moveToPending(library("a.txt"), "file-" + file.getId());

        assertThat(status(deleteFile(file.getId()))).isEqualTo(204);

        assertThat(fileItemRepository.findAll()).isEmpty();
        pendingDeleteCleaner.clean();
        assertThat(pendingDeleteCount()).isZero();
    }

    @Test
    void commitFailureLeavesObjectMarkedForDeletionByItsMove() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "x".getBytes(), rootId);
        failOnCommit("file_item");

        assertThat(status(deleteFile(file.getId()))).isGreaterThanOrEqualTo(500);

        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(Files.exists(pending("file-" + file.getId()))).isTrue();

        dropTrigger();
        pendingDeleteCleaner.clean();

        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(pendingDeleteCount()).isZero();
    }

    @Test
    void cleanerWaitsForRunningDeleteBeforeDecidingAboutRecords() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "x".getBytes(), rootId);
        moveToPending(library("a.txt"), "file-" + file.getId());

        try (RowGate gate = holdRoot(rootId)) {
            Future<?> cleaner = executor.submit(() -> pendingDeleteCleaner.clean());
            awaitWaiting(1);
            assertThat(Files.exists(pending("file-" + file.getId()))).isTrue();
            assertThat(fileItemRepository.findAll()).hasSize(1);
            gate.commit();
            cleaner.get(30, TimeUnit.SECONDS);
        }

        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(pendingDeleteCount()).isZero();
    }

    @Test
    void cleanerRemovesRestOfPartiallyDeletedFolder() throws Exception {
        Long top = createFolder(token, rootId, "Top").get("id").asLong();
        upload(token, "a.txt", "text/plain", "x".getBytes(), top);
        upload(token, "b.txt", "text/plain", "y".getBytes(), top);
        assertThat(status(deleteFolder(top))).isEqualTo(204);
        Files.delete(pending("folder-" + top + "/a.txt"));

        pendingDeleteCleaner.clean();
        pendingDeleteCleaner.clean();

        assertThat(pendingDeleteCount()).isZero();
    }

    @Test
    void entriesOfUnknownOriginAreNotTouched() throws Exception {
        Files.createDirectories(pendingDeleteDir());
        for (String name : List.of("mystery.txt", "file-abc", "folder-", "file-1-copy")) {
            Files.writeString(pending(name), "keep");
        }

        pendingDeleteCleaner.clean();

        assertThat(names(pendingDeleteDir())).hasSize(4);
    }

    @Test
    void entryWithIdOutOfRangeDoesNotStopCleaningOfFollowingEntries() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "x".getBytes(), rootId);
        assertThat(status(deleteFile(file.getId()))).isEqualTo(204);
        String invalid = "file-099999999999999999999";
        Files.writeString(pending(invalid), "keep");
        // На NTFS список упорядочен по имени: недопустимый объект обрабатывается раньше корректного.
        assertThat(names(pendingDeleteDir()).get(0)).isEqualTo(invalid);

        pendingDeleteCleaner.clean();

        assertThat(names(pendingDeleteDir())).containsExactly(invalid);
        assertThat(Files.readString(pending(invalid))).isEqualTo("keep");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void pendingDeleteDirectoryReplacedByJunctionIsNotProcessed() throws Exception {
        Path outside = Files.createTempDirectory("photo-cloud-outside-");
        FileItemDto file = upload(token, "a.txt", "text/plain", "x".getBytes(), rootId);
        try {
            Files.writeString(outside.resolve("file-999999"), "keep");
            Files.createDirectory(outside.resolve("folder-999999"));
            Files.writeString(outside.resolve("folder-999999/inner.txt"), "keep");
            junction(pendingDeleteDir(), outside);

            pendingDeleteCleaner.clean();

            assertThat(Files.readString(outside.resolve("file-999999"))).isEqualTo("keep");
            assertThat(Files.readString(outside.resolve("folder-999999/inner.txt"))).isEqualTo("keep");

            // Перенос в подменённый каталог тоже не выполняется: данные остаются на месте.
            assertThat(status(deleteFile(file.getId()))).isEqualTo(500);
            assertThat(Files.readString(library("a.txt"))).isEqualTo("x");
            assertThat(fileItemRepository.findAll()).hasSize(1);
            assertThat(names(outside)).containsExactlyInAnyOrder("file-999999", "folder-999999");
        } finally {
            Files.deleteIfExists(pendingDeleteDir());
            Files.deleteIfExists(outside.resolve("folder-999999/inner.txt"));
            Files.deleteIfExists(outside.resolve("folder-999999"));
            Files.deleteIfExists(outside.resolve("file-999999"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void regularFileInPlaceOfPendingDeleteDirectoryIsNotProcessed() throws Exception {
        Files.writeString(pendingDeleteDir(), "keep");

        pendingDeleteCleaner.clean();

        assertThat(Files.readString(pendingDeleteDir())).isEqualTo("keep");
    }

    // Совпадение ID само по себе ничего не доказывает: живой объект на своём месте удалять нельзя.
    @Test
    void liveFileWithCoincidingIdIsNotDeleted() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "live".getBytes(), rootId);
        Files.createDirectories(pendingDeleteDir());
        Files.writeString(pending("file-" + file.getId()), "stale");

        pendingDeleteCleaner.clean();

        assertThat(Files.readString(library("a.txt"))).isEqualTo("live");
        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(Files.readString(pending("file-" + file.getId()))).isEqualTo("stale");
    }

    @Test
    void entryWithOtherContentDoesNotDeleteRecordOfMissingFile() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "real".getBytes(), rootId);
        Files.delete(library("a.txt"));
        Files.createDirectories(pendingDeleteDir());
        Files.writeString(pending("file-" + file.getId()), "other");

        pendingDeleteCleaner.clean();

        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(Files.exists(pending("file-" + file.getId()))).isTrue();
    }

    @Test
    void liveFolderWithCoincidingIdIsNotDeleted() throws Exception {
        Long folder = createFolder(token, rootId, "Live").get("id").asLong();
        upload(token, "a.txt", "text/plain", "x".getBytes(), folder);
        Files.createDirectories(pending("folder-" + folder));
        Files.writeString(pending("folder-" + folder + "/stale.txt"), "stale");

        pendingDeleteCleaner.clean();

        assertThat(folderRepository.findById(folder)).isPresent();
        assertThat(Files.exists(library("Live/a.txt"))).isTrue();
        assertThat(Files.exists(pending("folder-" + folder + "/stale.txt"))).isTrue();
    }

    // Источник и служебный объект существуют одновременно: состояние неоднозначно, данные не трогаем.
    @Test
    void occupiedServiceNameIsNotReplacedAndNothingIsDeleted() throws Exception {
        FileItemDto file = upload(token, "a.txt", "text/plain", "live".getBytes(), rootId);
        Files.createDirectories(pendingDeleteDir());
        Files.writeString(pending("file-" + file.getId()), "stale");

        assertThat(status(deleteFile(file.getId()))).isEqualTo(500);

        assertThat(Files.readString(library("a.txt"))).isEqualTo("live");
        assertThat(Files.readString(pending("file-" + file.getId()))).isEqualTo("stale");
        assertThat(fileItemRepository.findAll()).hasSize(1);
    }

    @Test
    void busyFileRejectsDeleteWithoutChangingAnything() throws Exception {
        FileItemDto file = upload(token, "open.txt", "text/plain", "x".getBytes(), rootId);
        Long folder = createFolder(token, rootId, "Busy").get("id").asLong();
        upload(token, "inner.txt", "text/plain", "y".getBytes(), folder);

        try (FileInputStream first = new FileInputStream(library("open.txt").toFile());
             FileInputStream second = new FileInputStream(library("Busy/inner.txt").toFile())) {
            assertThat(status(deleteFile(file.getId()))).isEqualTo(409);
            assertThat(status(deleteFolder(folder))).isEqualTo(409);
            assertThat(first.read()).isEqualTo('x');
            assertThat(second.read()).isEqualTo('y');
        }

        assertThat(fileItemRepository.findAll()).hasSize(2);
        assertThat(folderRepository.findById(folder)).isPresent();
        assertThat(pendingDeleteCount()).isZero();
    }

    @Test
    void busyFileInPendingDeleteIsRetriedByNextPass() throws Exception {
        Long top = createFolder(token, rootId, "Top").get("id").asLong();
        upload(token, "a.txt", "text/plain", "x".getBytes(), top);
        upload(token, "b.txt", "text/plain", "y".getBytes(), top);
        assertThat(status(deleteFolder(top))).isEqualTo(204);

        try (FileInputStream open = new FileInputStream(pending("folder-" + top + "/a.txt").toFile())) {
            pendingDeleteCleaner.clean();

            assertThat(open.read()).isEqualTo('x');
            assertThat(pendingDeleteCount()).isEqualTo(1);
        }

        pendingDeleteCleaner.clean();
        assertThat(pendingDeleteCount()).isZero();
    }

    @Test
    void readOnlyFileIsRemoved() throws Exception {
        Long top = createFolder(token, rootId, "Top").get("id").asLong();
        upload(token, "a.txt", "text/plain", "x".getBytes(), top);
        assertThat(status(deleteFolder(top))).isEqualTo(204);
        DosFileAttributeView view = Files.getFileAttributeView(pending("folder-" + top + "/a.txt"), DosFileAttributeView.class);
        if (view != null) {
            view.setReadOnly(true);
        }

        pendingDeleteCleaner.clean();

        assertThat(pendingDeleteCount()).isZero();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void cleanerDoesNotFollowJunction() throws Exception {
        Path outside = Files.createTempDirectory("photo-cloud-outside-");
        Files.writeString(outside.resolve("precious.txt"), "keep");
        try {
            Long top = createFolder(token, rootId, "Top").get("id").asLong();
            upload(token, "a.txt", "text/plain", "x".getBytes(), top);
            junction(library("Top/link"), outside);

            assertThat(status(deleteFolder(top))).isEqualTo(204);
            pendingDeleteCleaner.clean();

            assertThat(pendingDeleteCount()).isZero();
            assertThat(Files.readString(outside.resolve("precious.txt"))).isEqualTo("keep");
        } finally {
            Files.deleteIfExists(outside.resolve("precious.txt"));
            Files.deleteIfExists(outside);
        }
    }

    private void junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
    }

    private void moveToPending(Path source, String name) throws Exception {
        Files.createDirectories(pendingDeleteDir());
        Files.move(source, pending(name));
    }

    private boolean precheckExisting(Long folderId, String checksum) throws Exception {
        String body = mockMvc.perform(post("/api/v1/files/checksums/exists")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("folderId", folderId, "checksums", List.of(checksum)))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("existing").size() > 0;
    }

    private void failOnCommit(String table) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION commit_failure() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'commit failure' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER commit_failure AFTER DELETE ON " + table
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION commit_failure()");
    }

    private Callable<MockHttpServletResponse> deleteFile(Long id) throws Exception {
        return jsonCall(delete("/api/v1/files/{id}", id), null);
    }

    private Callable<MockHttpServletResponse> deleteFolder(Long id) throws Exception {
        return jsonCall(delete("/api/v1/folders/{id}", id), null);
    }

    private Path library(String relative) {
        return storageRoot().resolve(userId + "/files").resolve(relative);
    }

    private Path pending(String relative) {
        return pendingDeleteDir().resolve(relative);
    }

    private List<String> names(Path directory) throws Exception {
        try (var stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).toList();
        }
    }

    private int status(Callable<MockHttpServletResponse> call) {
        try {
            return call.call().getStatus();
        } catch (Exception ex) {
            return 599;
        }
    }
}
