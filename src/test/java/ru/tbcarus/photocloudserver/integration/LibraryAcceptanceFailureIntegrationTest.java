package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;
import ru.tbcarus.photocloudserver.service.metadata.FileMetadataExtractor;
import ru.tbcarus.photocloudserver.service.storage.FileContentDetector;
import ru.tbcarus.photocloudserver.service.storage.FilenameSanitizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Отказы создаются настоящими триггерами PostgreSQL: откатывается реальная транзакция, а не mock репозитория.
class LibraryAcceptanceFailureIntegrationTest extends LibraryAcceptanceSupport {

    @MockitoSpyBean
    FileContentDetector fileContentDetector;

    @MockitoSpyBean
    FileMetadataExtractor fileMetadataExtractor;

    @MockitoSpyBean
    FilenameSanitizer filenameSanitizer;

    @AfterEach
    void dropTriggers() {
        for (String table : new String[]{"stored_object", "file_item", "file_metadata", "folder"}) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS acceptance_failure ON " + table);
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS acceptance_commit_failure ON " + table);
        }
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS acceptance_fail()");
    }

    private void failOn(String table, String operation, String sqlState) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION acceptance_fail() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'acceptance failure' USING ERRCODE = '%s'; END $$ LANGUAGE plpgsql
                """.formatted(sqlState));
        jdbcTemplate.execute("CREATE TRIGGER acceptance_failure BEFORE " + operation + " ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION acceptance_fail()");
    }

    // Отложенный триггер срабатывает на COMMIT: транзакция откатывается уже после завершения callback.
    private void failOnCommit(String table) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION acceptance_fail() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'acceptance failure' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER acceptance_commit_failure AFTER INSERT ON " + table
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION acceptance_fail()");
    }

    @ParameterizedTest
    @ValueSource(strings = {"stored_object", "file_item"})
    void constraintFailureWhileSavingUploadRollsBackAndRemovesBytes(String table) throws Exception {
        Long rootId = getRootId(token);
        failOn(table, "INSERT", "23505");

        MockHttpServletResponse response = uploadCall("a.txt", "a".getBytes(), rootId).call();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("DATABASE_CONSTRAINT_VIOLATION");
        assertThat(storedObjectRepository.findAll()).isEmpty();
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(physicalFiles()).isEmpty();
        assertLibraryConsistent();
    }

    @Test
    void metadataFailureInsideTransactionRollsBackAndRemovesBytes() throws Exception {
        byte[] jpeg = realJpeg();
        failOn("file_metadata", "INSERT", "22001");

        MockHttpServletResponse response = uploadCall("a.jpg", jpeg, null).call();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(storedObjectRepository.findAll()).isEmpty();
        assertThat(physicalFiles()).isEmpty();
        assertLibraryConsistent();
    }

    @Test
    void metadataIsWrittenByRealJpegUsedInFailureTest() throws Exception {
        FileItemDto file = upload(token, "a.jpg", "image/jpeg", realJpeg());

        assertThat(count("SELECT count(*) FROM file_metadata")).isEqualTo(1);
        assertThat(file.getId()).isPositive();
    }

    // Существующая гарантия: при сбое на COMMIT исход неизвестен, поэтому байты сознательно остаются.
    @Test
    void commitFailureRollsBackRecordsAndKeepsBytesAsDocumentedOrphan() throws Exception {
        Long rootId = getRootId(token);
        failOnCommit("file_item");

        MockHttpServletResponse response = uploadCall("a.txt", "a".getBytes(), rootId).call();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(storedObjectRepository.findAll()).isEmpty();
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(physicalFiles()).hasSize(1);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void constraintFailureWhileSavingCopyRemovesCopiedBytes() throws Exception {
        Long rootId = getRootId(token);
        FileItemDto source = upload(token, "a.txt", "text/plain", "a".getBytes(), rootId);
        failOn("file_item", "INSERT", "23505");

        MockHttpServletResponse response = jsonCall(post("/api/v1/files/{id}/copy", source.getId()), copyBody(rootId, "b.txt")).call();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(physicalFiles()).hasSize(1);
        assertLibraryConsistent();
    }

    @Test
    void failureWhileUpdatingFileKeepsOriginalNameAndFolder() throws Exception {
        Long rootId = getRootId(token);
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto file = upload(token, "a.txt", "text/plain", "a".getBytes(), rootId);
        failOn("file_item", "UPDATE", "23505");

        MockHttpServletResponse rename = executor.submit(jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "b.txt")))
                .get(10, TimeUnit.SECONDS);
        MockHttpServletResponse move = executor.submit(jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", target)))
                .get(10, TimeUnit.SECONDS);

        assertThat(rename.getStatus()).isEqualTo(500);
        assertThat(move.getStatus()).isEqualTo(500);
        assertThat(findFileItem(file.getId()).getOriginalName()).isEqualTo("a.txt");
        assertThat(findFileItem(file.getId()).getFolder().getId()).isEqualTo(rootId);
    }

    @Test
    void uniqueViolationWhileSavingFolderIsReportedAsConflict() throws Exception {
        Long rootId = getRootId(token);
        Long folder = createFolder(token, rootId, "Dir").get("id").asLong();
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        failOn("folder", "INSERT OR UPDATE", "23505");

        assertThat(jsonCall(post("/api/v1/folders"), Map.of("parentId", rootId, "name", "New")).call().getStatus()).isEqualTo(409);
        assertThat(jsonCall(patch("/api/v1/folders/{id}", folder), Map.of("name", "New")).call().getStatus()).isEqualTo(409);
        assertThat(jsonCall(post("/api/v1/folders/{id}/move", folder), Map.of("targetParentId", target)).call().getStatus()).isEqualTo(409);
        assertThat(folderRepository.findById(folder).orElseThrow().getName()).isEqualTo("Dir");
    }

    @Test
    void failureOfTypeDetectionRemovesTempFile() throws Exception {
        Long rootId = getRootId(token);
        doThrow(new IOException("detector")).when(fileContentDetector).detectMimeType(any());

        assertThat(uploadCall("a.txt", "a".getBytes(), rootId).call().getStatus()).isEqualTo(500);

        assertThat(tempFileCount()).isZero();
        assertThat(physicalFiles()).isEmpty();
    }

    @Test
    void failureOfMetadataExtractionRemovesTempFile() throws Exception {
        Long rootId = getRootId(token);
        doThrow(new IllegalStateException("metadata")).when(fileMetadataExtractor).extract(any(), any());

        try {
            assertThat(uploadCall("a.txt", "a".getBytes(), rootId).call().getStatus()).isEqualTo(500);
        } catch (Exception ex) {
            // Необработанное исключение MockMvc пробрасывает как есть: важна только судьба temp.
            assertThat(ex).hasRootCauseInstanceOf(IllegalStateException.class);
        }

        assertThat(tempFileCount()).isZero();
        assertThat(physicalFiles()).isEmpty();
    }

    @Test
    void failureWhilePublishingUploadRemovesTempFile() throws Exception {
        Long rootId = getRootId(token);
        Files.createDirectories(storageRoot().resolve(userId.toString()));
        Files.writeString(storageRoot().resolve(userId + "/files"), "file blocks directory");

        assertThat(uploadCall("a.txt", "a".getBytes(), rootId).call().getStatus()).isEqualTo(500);

        assertThat(tempFileCount()).isZero();
        assertThat(storedObjectRepository.findAll()).isEmpty();
        assertThat(fileItemRepository.findAll()).isEmpty();
    }

    @Test
    void repeatAfterSuccessfulUploadCreatesNoNewPhysicalCopy() throws Exception {
        Long rootId = getRootId(token);
        Long first = id(uploadCall("a.txt", "a".getBytes(), rootId).call());
        long storedObjects = storedObjectRepository.count();
        var filesBefore = physicalFiles();

        Long repeated = id(uploadCall("a.txt", "a".getBytes(), rootId).call());
        Long repeatedOtherCase = id(uploadCall("A.TXT", "a".getBytes(), rootId).call());

        assertThat(repeated).isEqualTo(first);
        assertThat(repeatedOtherCase).isEqualTo(first);
        assertThat(storedObjectRepository.count()).isEqualTo(storedObjects);
        assertThat(physicalFiles()).isEqualTo(filesBefore);
        assertLibraryConsistent();
    }

    @Test
    void uploadOfNotFoundFolderLeavesNothing() throws Exception {
        getRootId(token);

        assertThat(uploadCall("a.txt", "a".getBytes(), 999_999L).call().getStatus()).isEqualTo(404);

        assertThat(tempFileCount()).isZero();
        assertThat(physicalFiles()).isEmpty();
    }

    @Test
    void foreignFolderIsNotAvailableForAnyTargetOperation() throws Exception {
        Long myRoot = getRootId(token);
        FileItemDto file = upload(token, "a.txt", "text/plain", "a".getBytes(), myRoot);
        createUser("foreign@test.local", PASSWORD);
        String foreignToken = loginAndGetAccessToken("foreign@test.local", PASSWORD);
        Long foreignRoot = getRootId(foreignToken);

        assertThat(uploadCall("b.txt", "b".getBytes(), foreignRoot).call().getStatus()).isEqualTo(404);
        assertThat(jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", foreignRoot)).call().getStatus()).isEqualTo(404);
        assertThat(jsonCall(post("/api/v1/files/{id}/copy", file.getId()), copyBody(foreignRoot, "c.txt")).call().getStatus()).isEqualTo(404);
        assertThat(jsonCall(post("/api/v1/folders"), Map.of("parentId", foreignRoot, "name", "x")).call().getStatus()).isEqualTo(404);
        assertLibraryConsistent();
    }

    // H-1: прежде delete попадал между чтением и записью rename; теперь он ждёт конца операции в общей очереди.
    @Test
    void renameRacingWithFileDeleteAnswersWithoutServerError() throws Exception {
        Long rootId = getRootId(token);
        FileItemDto file = upload(token, "a.txt", "text/plain", "a".getBytes(), rootId);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(1);
        doAnswer(invocation -> {
            loaded.countDown();
            deleted.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(filenameSanitizer).validateLibraryName("b.txt");

        Future<Integer> rename = statusOf(jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "b.txt")));
        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Integer> delete = statusOf(jsonCall(delete("/api/v1/files/{id}", file.getId()), null));
        awaitWaiting(1);
        deleted.countDown();

        assertThat(rename.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(delete.get(20, TimeUnit.SECONDS)).isEqualTo(204);
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertLibraryConsistent();
    }

    @Test
    void moveRacingWithFileDeleteAnswersWithoutServerError() throws Exception {
        Long rootId = getRootId(token);
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto file = upload(token, "a.txt", "text/plain", "a".getBytes(), rootId);

        // Занятая строка файла держит move между чтением и записью; delete в это время обязан ждать его в очереди.
        try (Connection rowLock = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            rowLock.setAutoCommit(false);
            try (var statement = rowLock.createStatement()) {
                statement.executeQuery("SELECT id FROM file_item WHERE id = " + file.getId() + " FOR UPDATE");
            }
            Future<Integer> move = statusOf(jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", target)));
            awaitWaiting(1);
            Future<Integer> delete = statusOf(jsonCall(delete("/api/v1/files/{id}", file.getId()), null));
            awaitWaiting(2);
            rowLock.commit();

            assertThat(move.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(delete.get(20, TimeUnit.SECONDS)).isEqualTo(204);
        }
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertLibraryConsistent();
    }

    @Test
    void copyRacingWithSourceDeleteLeavesConsistentState() throws Exception {
        Long rootId = getRootId(token);
        FileItemDto file = upload(token, "a.txt", "text/plain", "a".getBytes(), rootId);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(1);
        doAnswer(invocation -> {
            loaded.countDown();
            deleted.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(filenameSanitizer).validateLibraryName("b.txt");

        Future<Integer> copy = statusOf(jsonCall(post("/api/v1/files/{id}/copy", file.getId()), copyBody(rootId, "b.txt")));
        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(jsonCall(delete("/api/v1/files/{id}", file.getId()), null).call().getStatus()).isEqualTo(204);
        deleted.countDown();

        // Источник удалён параллельно: клиент получает 404, а не внутреннюю ошибку.
        assertThat(copy.get(20, TimeUnit.SECONDS)).isEqualTo(404);
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertLibraryConsistent();
    }

    private Future<Integer> statusOf(java.util.concurrent.Callable<MockHttpServletResponse> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.call().getStatus();
            } catch (Exception ex) {
                System.out.println("UNHANDLED: " + ex);
                return 599;
            }
        }, executor);
    }

    private byte[] realJpeg() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }
}
