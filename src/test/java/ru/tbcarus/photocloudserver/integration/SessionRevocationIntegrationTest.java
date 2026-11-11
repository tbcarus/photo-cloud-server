package ru.tbcarus.photocloudserver.integration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.LoginResponse;
import ru.tbcarus.photocloudserver.service.EmailRequestService;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Успешный reset отзывает все refresh-сессии пользователя; access JWT действует до истечения TTL.
// Выдача новой сессии (login, замена refresh) и reset сериализуются блокировкой строки пользователя.
class SessionRevocationIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "sessions@test.local";
    private static final String OTHER = "other@test.local";

    @MockitoSpyBean
    PasswordEncoder spyEncoder;

    @Autowired
    EmailRequestService emailRequestService;

    @Value("${token.signing.key}")
    private String signingKey;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void successfulResetRevokesAllSessionsButIssuedAccessWorksUntilTtl() throws Exception {
        User user = createUser(EMAIL, "oldpass");
        createUser(OTHER, "pass1");
        LoginResponse phone = login(EMAIL, "oldpass");
        LoginResponse tablet = login(EMAIL, "oldpass");
        LoginResponse other = login(OTHER, "pass1");

        assertThat(resetConfirm(resetCode(user), "newpass").getStatus()).isEqualTo(200);

        for (LoginResponse session : List.of(phone, tablet)) {
            MockHttpServletResponse refresh = refresh(session.refreshToken());
            assertThat(refresh.getStatus()).isEqualTo(403);
            assertThat(refresh.getContentAsString()).contains("REFRESH_TOKEN_REVOKED");
        }
        assertThat(activeSessions(EMAIL)).isZero();
        // Отзываются только refresh-сессии: выданный access действует до истечения, проверки banned на запросе нет.
        assertThat(protectedCall(phone.accessToken())).isEqualTo(200);
        // Чужие сессии не затронуты; новый вход и его refresh работают.
        assertThat(refresh(other.refreshToken()).getStatus()).isEqualTo(200);
        assertThat(refresh(login(EMAIL, "newpass").refreshToken()).getStatus()).isEqualTo(200);
        assertThat(loginStatus(EMAIL, "oldpass")).isEqualTo(401);
    }

    @Test
    void failedResetKeepsSessions() throws Exception {
        User user = createUser(EMAIL, "oldpass");
        LoginResponse session = login(EMAIL, "oldpass");
        String used = resetCode(user);
        jdbcTemplate.update("UPDATE email_requests SET used = true WHERE code = ?", used);
        String expired = resetCode(user);
        jdbcTemplate.update("UPDATE email_requests SET created_at = ? WHERE code = ?", LocalDateTime.now().minusDays(30), expired);
        String valid = resetCode(user);

        assertThat(resetConfirm("unknown-code", "newpass").getStatus()).isEqualTo(400);
        assertThat(resetConfirm(used, "newpass").getStatus()).isEqualTo(400);
        assertThat(resetConfirm(expired, "newpass").getStatus()).isEqualTo(400);
        // Пароль не проходит проверку длины: до кода дело не доходит.
        assertThat(resetConfirm(valid, "x").getStatus()).isEqualTo(400);

        assertThat(activeSessions(EMAIL)).isEqualTo(1);
        assertThat(refresh(session.refreshToken()).getStatus()).isEqualTo(200);
        assertThat(loginStatus(EMAIL, "oldpass")).isEqualTo(200);
    }

    @Test
    void loginCheckedBeforeResetCommitGetsNoSession() throws Exception {
        User user = createUser(EMAIL, "oldpass");
        String code = resetCode(user);
        // Login прошёл проверку старого пароля; reset фиксируется до выдачи сессии.
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch resetDone = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object matches = invocation.callRealMethod();
            if (armed.compareAndSet(true, false)) {
                checked.countDown();
                assertThat(resetDone.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return matches;
        }).when(spyEncoder).matches(any(), any());

        Future<Integer> racingLogin = executor.submit(() -> loginStatus(EMAIL, "oldpass"));
        assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(resetConfirm(code, "newpass").getStatus()).isEqualTo(200);
        resetDone.countDown();

        assertThat(racingLogin.get(10, TimeUnit.SECONDS)).isEqualTo(401);
        assertThat(activeSessions(EMAIL)).isZero();
        assertThat(loginStatus(EMAIL, "newpass")).isEqualTo(200);
    }

    @Test
    void loginWaitingForRunningResetGetsNoSession() throws Exception {
        User user = createUser(EMAIL, "oldpass");
        String code = resetCode(user);
        ResetGate gate = holdResetAfterLock();

        // Reset держит строку пользователя; login со старым паролем ждёт её.
        Future<MockHttpServletResponse> reset = executor.submit(() -> resetConfirm(code, "newpass"));
        assertThat(gate.locked().await(10, TimeUnit.SECONDS)).isTrue();
        Future<Integer> racingLogin = executor.submit(() -> loginStatus(EMAIL, "oldpass"));
        awaitWaitingOnLock(1);
        gate.release().countDown();

        assertThat(reset.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        assertThat(racingLogin.get(10, TimeUnit.SECONDS)).isEqualTo(401);
        assertThat(activeSessions(EMAIL)).isZero();
    }

    @Test
    void refreshReplacementWaitingForRunningResetCreatesNoSurvivingSession() throws Exception {
        User user = createUser(EMAIL, "oldpass");
        // До истечения меньше порога замены: refresh выдаёт новую сессию R2 вместо R1.
        String r1 = insertRefreshToken(EMAIL, Duration.ofDays(10));
        String code = resetCode(user);
        ResetGate gate = holdResetAfterLock();

        Future<MockHttpServletResponse> reset = executor.submit(() -> resetConfirm(code, "newpass"));
        assertThat(gate.locked().await(10, TimeUnit.SECONDS)).isTrue();
        Future<MockHttpServletResponse> replacement = executor.submit(() -> refresh(r1));
        awaitWaitingOnLock(1);
        gate.release().countDown();

        assertThat(reset.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        MockHttpServletResponse refreshed = replacement.get(10, TimeUnit.SECONDS);
        assertThat(refreshed.getStatus()).isEqualTo(403);
        assertThat(refreshed.getContentAsString()).contains("REFRESH_TOKEN_REVOKED");
        assertThat(activeSessions(EMAIL)).isZero();
    }

    @Test
    void replacementCommittedBeforeResetIsRevokedByIt() throws Exception {
        User user = createUser(EMAIL, "oldpass");
        String r1 = insertRefreshToken(EMAIL, Duration.ofDays(10));
        MockHttpServletResponse replaced = refresh(r1);
        assertThat(replaced.getStatus()).isEqualTo(200);
        String r2 = objectMapper.readTree(replaced.getContentAsString()).get("refreshToken").asText();

        assertThat(resetConfirm(resetCode(user), "newpass").getStatus()).isEqualTo(200);

        assertThat(refresh(r2).getStatus()).isEqualTo(403);
        assertThat(activeSessions(EMAIL)).isZero();
    }

    // Reset останавливается после блокировки строки пользователя, на хешировании нового пароля.
    private ResetGate holdResetAfterLock() {
        ResetGate gate = new ResetGate(new CountDownLatch(1), new CountDownLatch(1));
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (armed.compareAndSet(true, false)) {
                gate.locked().countDown();
                assertThat(gate.release().await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(spyEncoder).encode(any());
        return gate;
    }

    private record ResetGate(CountDownLatch locked, CountDownLatch release) {
    }

    // Опрос состояния PostgreSQL, а не задержка: ждём, пока заданное число запросов встанет в очередь блокировки.
    private void awaitWaitingOnLock(long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (waitingOnLock() == expected) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Ожидалось запросов в очереди блокировки: " + expected + ", фактически " + waitingOnLock());
    }

    private long waitingOnLock() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                Long.class);
    }

    private String resetCode(User user) {
        EmailRequest request = emailRequestService.generateEmailRequest(user, EmailRequestType.PASSWORD_RESET);
        return request.getCode();
    }

    private LoginResponse login(String email, String password) throws Exception {
        MockHttpServletResponse response = loginCall(email, password);
        assertThat(response.getStatus()).isEqualTo(200);
        return objectMapper.readValue(response.getContentAsString(), LoginResponse.class);
    }

    private int loginStatus(String email, String password) throws Exception {
        return loginCall(email, password).getStatus();
    }

    private MockHttpServletResponse loginCall(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse resetConfirm(String code, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password/reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn().getResponse();
    }

    private int protectedCall(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/v1/folders/root").header(HttpHeaders.AUTHORIZATION, authHeader(accessToken)))
                .andReturn().getResponse().getStatus();
    }

    private int activeSessions(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_name = ? AND revoked = false", Integer.class, email);
    }

    // Refresh с заданным остатком срока: login всегда выдаёт полные 180 дней.
    private String insertRefreshToken(String email, Duration remaining) {
        Instant expiration = Instant.now().plus(remaining).truncatedTo(ChronoUnit.SECONDS);
        String token = Jwts.builder()
                .claim("roles", List.of("USER"))
                .claim("token_type", "REFRESH")
                .id(UUID.randomUUID().toString())
                .subject(email)
                .issuedAt(Date.from(expiration.minus(Duration.ofDays(180))))
                .expiration(Date.from(expiration))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(signingKey)))
                .compact();
        jdbcTemplate.update("INSERT INTO refresh_token (token, user_name, expires, revoked) VALUES (?, ?, ?, false)",
                token, email, LocalDateTime.ofInstant(expiration, ZoneId.systemDefault()));
        return token;
    }
}
