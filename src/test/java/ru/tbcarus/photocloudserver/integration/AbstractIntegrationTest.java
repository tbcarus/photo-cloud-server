package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultHandler;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.Role;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.LoginResponse;
import ru.tbcarus.photocloudserver.repository.EmailRequestRepository;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.FolderRepository;
import ru.tbcarus.photocloudserver.repository.StoredObjectRepository;
import ru.tbcarus.photocloudserver.repository.UserRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    private static final Path TEST_STORAGE = createTestStorage();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected FileItemRepository fileItemRepository;

    @Autowired
    protected StoredObjectRepository storedObjectRepository;

    @Autowired
    protected FolderRepository folderRepository;

    @Autowired
    protected EmailRequestRepository emailRequestRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("storage.root", () -> TEST_STORAGE.toString());
    }

    @BeforeEach
    void cleanBeforeEach(TestInfo testInfo) throws IOException {
        System.out.println();
        System.out.printf("=== %s ===%n", testInfo.getTestMethod().orElseThrow().getName());
        cleanupDbAndStorage();
    }

    @AfterAll
    static void removeTestStorage() throws IOException {
        deleteChildren(TEST_STORAGE);
        Files.deleteIfExists(TEST_STORAGE);
    }

    protected User createUser(String email, String rawPassword) {
        return createUser(email, rawPassword, true, false);
    }

    protected User createUser(String email, String rawPassword, boolean enabled, boolean banned) {
        User user = User.builder()
                .email(email.toLowerCase())
                .password(passwordEncoder.encode(rawPassword))
                .displayName("Test User")
                .roles(Set.of(Role.USER))
                .enabled(enabled)
                .banned(banned)
                .build();
        return userRepository.save(user);
    }

    protected String loginAndGetAccessToken(String email, String rawPassword) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(email, rawPassword);

        MvcResult result = perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        LoginResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
        return response.accessToken();
    }

    protected String authHeader(String accessToken) {
        return "Bearer " + accessToken;
    }

    protected FileItemDto upload(String accessToken, String filename, String contentType, byte[] bytes) throws Exception {
        MvcResult result = perform(multipart("/api/v1/files")
                        .file(filePart(filename, contentType, bytes))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(accessToken)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), FileItemDto.class);
    }

    protected MockMultipartFile filePart(String filename, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", filename, contentType, bytes);
    }

    protected ResultActions perform(RequestBuilder requestBuilder) throws Exception {
        return mockMvc.perform(requestBuilder).andDo(logHttpExchange());
    }

    protected String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    protected FileItem findFileItem(Long id) {
        return fileItemRepository.findWithRelationsById(id)
                .orElseThrow(() -> new AssertionError("File item " + id + " was not found"));
    }

    protected void deletePhysicalFile(Long id) throws IOException {
        Files.deleteIfExists(TEST_STORAGE.resolve(findFileItem(id).getStoredObject().getStorageKey()));
    }

    protected Path storageRoot() {
        return TEST_STORAGE;
    }

    protected long storageFileCount() throws IOException {
        if (!Files.exists(TEST_STORAGE)) {
            return 0;
        }
        try (var stream = Files.walk(TEST_STORAGE)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    protected void cleanupDbAndStorage() throws IOException {
        jdbcTemplate.execute("TRUNCATE TABLE file_metadata, file_item, stored_object, folder, refresh_token, email_requests, user_roles, users RESTART IDENTITY CASCADE");
        deleteChildren(TEST_STORAGE);
        Files.createDirectories(TEST_STORAGE);
    }

    private ResultHandler logHttpExchange() {
        return result -> {
            var request = result.getRequest();
            var response = result.getResponse();
            String url = request.getRequestURI();
            if (request.getQueryString() != null) {
                url += "?" + request.getQueryString();
            }

            System.out.printf("REQUEST  %s %s%n", request.getMethod(), url);
            printRequestHeaders(result);
            printRequestBody(result);
            printMultipartFiles(result);
            System.out.printf("RESPONSE %d%n", response.getStatus());
            printResponseHeaders(result);
            printResponseBody(result);
            System.out.println();
        };
    }

    private void printRequestHeaders(MvcResult result) {
        var request = result.getRequest();
        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            String value = HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                    ? maskAuthorization(request.getHeader(name))
                    : request.getHeader(name);
            System.out.printf("  > %s: %s%n", name, value);
        }
    }

    private void printRequestBody(MvcResult result) throws Exception {
        var request = result.getRequest();
        if (isJson(request.getContentType()) && request.getContentAsByteArray().length > 0) {
            System.out.printf("  > body: %s%n", maskSensitiveJson(request.getContentAsString()));
        }
    }

    private void printMultipartFiles(MvcResult result) {
        if (!(result.getRequest() instanceof MultipartHttpServletRequest multipartRequest)) {
            return;
        }
        Iterator<String> names = multipartRequest.getFileNames();
        while (names.hasNext()) {
            MultipartFile file = multipartRequest.getFile(names.next());
            if (file != null) {
                System.out.printf("  > multipart file: filename=%s, contentType=%s, size=%d%n",
                        file.getOriginalFilename(),
                        file.getContentType(),
                        file.getSize());
            }
        }
    }

    private void printResponseHeaders(MvcResult result) {
        result.getResponse().getHeaderNames().forEach(name ->
                System.out.printf("  < %s: %s%n", name, result.getResponse().getHeader(name)));
    }

    private void printResponseBody(MvcResult result) throws Exception {
        var response = result.getResponse();
        byte[] body = response.getContentAsByteArray();
        if (body.length == 0) {
            System.out.println("  < body: <empty>");
            return;
        }
        if (isBinary(response.getContentType())) {
            System.out.printf("  < body: <binary omitted, %d bytes>%n", body.length);
            return;
        }
        String bodyText = response.getContentAsString();
        if (isJson(response.getContentType())) {
            bodyText = maskSensitiveJson(bodyText);
        }
        System.out.printf("  < body: %s%n", bodyText);
    }

    private boolean isJson(String contentType) {
        return contentType != null && contentType.contains(MediaType.APPLICATION_JSON_VALUE);
    }

    private boolean isBinary(String contentType) {
        return contentType != null && (
                contentType.startsWith("image/")
                        || contentType.startsWith("audio/")
                        || contentType.startsWith("video/")
                        || contentType.startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE));
    }

    private String maskAuthorization(String headerValue) {
        if (headerValue == null || !headerValue.startsWith("Bearer ")) {
            return "<masked>";
        }
        String token = headerValue.substring("Bearer ".length());
        String prefix = token.substring(0, Math.min(3, token.length()));
        return "Bearer " + prefix + "...<masked>";
    }

    private String maskSensitiveJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            maskSensitiveJson(root);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            return json;
        }
    }

    private void maskSensitiveJson(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.fieldNames().forEachRemaining(fieldName -> {
                JsonNode child = objectNode.get(fieldName);
                if ("accessToken".equals(fieldName) || "refreshToken".equals(fieldName)) {
                    objectNode.put(fieldName, "<masked>");
                } else {
                    maskSensitiveJson(child);
                }
            });
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(this::maskSensitiveJson);
        }
    }

    private static Path createTestStorage() {
        try {
            return Files.createTempDirectory("photo-cloud-server-it-");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteChildren(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(directory))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }
}
