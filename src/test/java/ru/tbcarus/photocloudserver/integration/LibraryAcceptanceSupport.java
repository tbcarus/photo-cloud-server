package ru.tbcarus.photocloudserver.integration;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

// Общие средства приёмки: управляемая очередь на блокировке ROOT и сверка инвариантов БД с файловой системой.
abstract class LibraryAcceptanceSupport extends AbstractIntegrationTest {

    protected static final String PASSWORD = "pass1";

    protected final ExecutorService executor = Executors.newFixedThreadPool(8);
    protected String token;
    protected Long userId;

    @BeforeEach
    void createAcceptanceUser() throws Exception {
        userId = createUser("accept@test.local", PASSWORD).getId();
        token = loginAndGetAccessToken("accept@test.local", PASSWORD);
    }

    @AfterEach
    void stopAcceptanceExecutor() {
        executor.shutdownNow();
    }

    protected Callable<MockHttpServletResponse> uploadCall(String name, byte[] bytes, Long folderId) {
        var request = multipart("/api/v1/files")
                .file(filePart(name, "application/octet-stream", bytes))
                .header(HttpHeaders.AUTHORIZATION, authHeader(token));
        if (folderId != null) {
            request.param("folderId", folderId.toString());
        }
        return () -> mockMvc.perform(request).andReturn().getResponse();
    }

    protected Callable<MockHttpServletResponse> jsonCall(MockHttpServletRequestBuilder builder, Map<String, ?> body) throws Exception {
        builder.header(HttpHeaders.AUTHORIZATION, authHeader(token));
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return () -> mockMvc.perform(builder).andReturn().getResponse();
    }

    protected Map<String, Object> copyBody(Long targetFolderId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("targetFolderId", targetFolderId);
        body.put("originalName", name);
        return body;
    }

    protected long waitingBackends() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                Long.class);
    }

    protected void awaitWaiting(long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (waitingBackends() == expected) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Ожидалось запросов в очереди блокировки: " + expected + ", фактически " + waitingBackends());
    }

    protected List<MockHttpServletResponse> results(List<Future<MockHttpServletResponse>> futures) throws Exception {
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (Future<MockHttpServletResponse> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        return responses;
    }

    // Запускает запросы по одному: следующий стартует, только когда предыдущий уже стоит в очереди блокировки.
    protected List<MockHttpServletResponse> runQueued(RowGate gate, List<Callable<MockHttpServletResponse>> calls) throws Exception {
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            futures.add(executor.submit(calls.get(i)));
            awaitWaiting(i + 1L);
        }
        gate.commit();
        return results(futures);
    }

    protected RowGate holdRoot(Long rootId) throws SQLException {
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM folder WHERE id = ? FOR NO KEY UPDATE")) {
            statement.setLong(1, rootId);
            statement.executeQuery();
        }
        return new RowGate(connection);
    }

    // ROOT ещё не зафиксирован: конкурирующие INSERT ... ON CONFLICT ждут исхода этой транзакции.
    protected RowGate holdUncommittedRoot() throws SQLException {
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO folder (user_id, name, folder_type) VALUES (?, 'root', 'ROOT')")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
        return new RowGate(connection);
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        return connection;
    }

    protected List<Integer> statuses(List<MockHttpServletResponse> responses) {
        return responses.stream().map(MockHttpServletResponse::getStatus).toList();
    }

    protected Long id(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString()).get("id").asLong();
    }

    protected long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    // Главная сверка: все инварианты модели имён и точное соответствие записей байтам на диске.
    protected void assertLibraryConsistent() throws IOException {
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(jdbcTemplate.queryForList("""
                SELECT parent_id || ':' || name FROM (
                    SELECT f.parent_id, lower(f.name) AS name FROM folder f WHERE f.parent_id IS NOT NULL
                    UNION ALL
                    SELECT i.folder_id, lower(i.original_name)
                    FROM file_item i JOIN folder p ON p.id = i.folder_id
                    WHERE p.folder_type <> 'CAMERA'
                ) names GROUP BY parent_id, name HAVING count(*) > 1
                """, String.class)).as("занятые дважды имена библиотеки").isEmpty();
        soft.assertThat(jdbcTemplate.queryForList("""
                SELECT i.checksum FROM file_item i JOIN folder p ON p.id = i.folder_id
                WHERE p.folder_type = 'CAMERA' GROUP BY i.user_id, i.folder_id, i.checksum HAVING count(*) > 1
                """, String.class)).as("дубли checksum в Camera").isEmpty();
        soft.assertThat(count("""
                SELECT count(*) FROM (SELECT user_id FROM folder WHERE folder_type = 'ROOT' GROUP BY user_id HAVING count(*) > 1) r
                """)).as("лишние ROOT").isZero();
        soft.assertThat(count("""
                SELECT count(*) FROM (SELECT user_id FROM folder WHERE folder_type = 'CAMERA' GROUP BY user_id HAVING count(*) > 1) c
                """)).as("лишние Camera").isZero();
        soft.assertThat(count("""
                SELECT count(*) FROM stored_object s WHERE NOT EXISTS (SELECT 1 FROM file_item i WHERE i.stored_object_id = s.id)
                """)).as("StoredObject без FileItem").isZero();
        soft.assertThat(foldersOutsideRoot()).as("папки в цикле или вне ROOT").isEmpty();
        soft.assertThat(physicalFiles()).as("байты на диске и StoredObject").isEqualTo(storedFiles());
        soft.assertThat(tempFileCount()).as("временные файлы").isZero();
        soft.assertAll();
    }

    private List<Long> foldersOutsideRoot() {
        Map<Long, Long> parents = new HashMap<>();
        jdbcTemplate.query("SELECT id, parent_id FROM folder", resultSet -> {
            long parent = resultSet.getLong("parent_id");
            parents.put(resultSet.getLong("id"), resultSet.wasNull() ? null : parent);
        });
        List<Long> broken = new ArrayList<>();
        for (Long folder : parents.keySet()) {
            Long current = folder;
            int steps = 0;
            while (current != null && steps++ <= parents.size()) {
                current = parents.get(current);
            }
            if (current != null) {
                broken.add(folder);
            }
        }
        return broken;
    }

    private Set<Path> storedFiles() {
        return fileItemRepository.findAll().stream()
                .map(file -> physicalPath(findFileItem(file.getId())))
                .collect(Collectors.toSet());
    }

    protected Set<Path> physicalFiles() throws IOException {
        Path tempDir = storageRoot().resolve("tmp");
        try (var stream = Files.walk(storageRoot())) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(tempDir))
                    .filter(path -> !path.startsWith(pendingDeleteDir()))
                    .filter(path -> storageRoot().relativize(path).getNameCount() > 2)
                    .map(Path::normalize)
                    .collect(Collectors.toSet());
        }
    }

    protected static final class RowGate implements AutoCloseable {
        private final Connection connection;

        private RowGate(Connection connection) {
            this.connection = connection;
        }

        void commit() throws SQLException {
            connection.commit();
        }

        void rollback() throws SQLException {
            connection.rollback();
        }

        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }

    protected void assertNoServerError(List<MockHttpServletResponse> responses) {
        assertThat(statuses(responses)).allMatch(status -> status < 500);
    }
}
