package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import ru.tbcarus.photocloudserver.model.RefreshToken;
import ru.tbcarus.photocloudserver.model.dto.LoginResponse;
import ru.tbcarus.photocloudserver.repository.RefreshTokenRepository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Замена refresh за 30 дней до истечения, проверки при refresh и logout в окне замены. */
class RefreshRotationIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";
    private static final String PROTECTED = "/api/v1/folders/root";
    private static final String FOREIGN_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTBmZWRjYmE5ODc2NTQzMjEwZmVkY2JhOTg3NjU0MzIxMA==";

    @Value("${token.signing.key}")
    private String signingKey;

    @MockitoSpyBean
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void loginIssuesAccessForOneDayAndRefreshForHalfYear() throws Exception {
        createUser("ttl@test.local", PASSWORD);

        LoginResponse tokens = login("ttl@test.local");

        assertThat(lifetime(claims(tokens.accessToken()))).isEqualTo(Duration.ofMinutes(1440));
        assertThat(lifetime(claims(tokens.refreshToken()))).isEqualTo(Duration.ofDays(180));
        assertThat(row(tokens.refreshToken()).get("expires", Timestamp.class).toLocalDateTime())
                .isCloseTo(LocalDateTime.now().plusDays(180), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void refreshWithMoreThanThirtyDaysLeftReturnsOnlyAccess() throws Exception {
        createUser("no-rotation@test.local", PASSWORD);
        String fresh = login("no-rotation@test.local").refreshToken();
        String nearThreshold = insertRefreshToken("no-rotation@test.local", signingKey, Duration.ofDays(30).plusMinutes(5));

        for (String token : List.of(fresh, nearThreshold)) {
            String access = accessOf(refresh(token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").doesNotExist())
                    .andReturn());

            perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, authHeader(access))).andExpect(status().isOk());
            assertThat(row(token).get("revoked", Boolean.class)).isFalse();
        }
        assertThat(tokenCount("no-rotation@test.local")).isEqualTo(2);
    }

    @Test
    void refreshWithThirtyDaysOrLessLeftReplacesRefresh() throws Exception {
        createUser("rotation@test.local", PASSWORD);
        String r1 = insertRefreshToken("rotation@test.local", signingKey, Duration.ofDays(30).minusMinutes(5));

        MvcResult result = refresh(r1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String r2 = body.get("refreshToken").asText();
        perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, authHeader(body.get("accessToken").asText())))
                .andExpect(status().isOk());

        TokenRow revoked = row(r1);
        assertThat(revoked.get("revoked", Boolean.class)).isTrue();
        assertThat(revoked.get("revoked_at", Timestamp.class)).isNotNull();

        Claims r2Claims = claims(r2);
        assertThat(r2Claims.get("token_type")).isEqualTo("REFRESH");
        assertThat(r2Claims.getSubject()).isEqualTo("rotation@test.local");
        assertThat(lifetime(r2Claims)).isEqualTo(Duration.ofDays(180));
        TokenRow replacement = row(r2);
        assertThat(replacement.get("revoked", Boolean.class)).isFalse();
        assertThat(replacement.get("user_name", String.class)).isEqualTo("rotation@test.local");
        assertThat(replacement.get("expires", Timestamp.class).toLocalDateTime())
                .isCloseTo(LocalDateTime.now().plusDays(180), within(1, ChronoUnit.MINUTES));

        // R2 действует и далёк от порога; R1 больше не принимается.
        refresh(r2).andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotExist());
        refresh(r1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));
    }

    @Test
    void failedSaveOfReplacementRollsBackRevocationOfR1() throws Exception {
        createUser("rollback@test.local", PASSWORD);
        String r1 = insertRefreshToken("rollback@test.local", signingKey, Duration.ofDays(10));
        doThrow(new DataIntegrityViolationException("simulated")).when(refreshTokenRepository).save(any(RefreshToken.class));

        refresh(r1)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DATABASE_CONSTRAINT_VIOLATION"));

        assertThat(row(r1).get("revoked", Boolean.class)).isFalse();
        assertThat(tokenCount("rollback@test.local")).isEqualTo(1);
    }

    @Test
    void concurrentRefreshOfSameR1CreatesSingleReplacement() throws Exception {
        createUser("concurrent@test.local", PASSWORD);
        String r1 = insertRefreshToken("concurrent@test.local", signingKey, Duration.ofDays(10));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            Callable<Integer> call = () -> refresh(r1).andReturn().getResponse().getStatus();
            List<Future<Integer>> futures = List.of(executor.submit(call), executor.submit(call));
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 403);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_name = ? AND revoked = false",
                Integer.class, "concurrent@test.local")).isEqualTo(1);
    }

    @Test
    void disabledOrBannedUserGetsNoAccessAndNoReplacement() throws Exception {
        createUser("blocked@test.local", PASSWORD);
        String r1 = insertRefreshToken("blocked@test.local", signingKey, Duration.ofDays(10));

        jdbcTemplate.update("UPDATE users SET banned = true WHERE email = ?", "blocked@test.local");
        refresh(r1)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        jdbcTemplate.update("UPDATE users SET banned = false, enabled = false WHERE email = ?", "blocked@test.local");
        refresh(r1)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        assertThat(row(r1).get("revoked", Boolean.class)).isFalse();
        assertThat(tokenCount("blocked@test.local")).isEqualTo(1);
    }

    @Test
    void tokenTypesAreNotInterchangeable() throws Exception {
        createUser("types@test.local", PASSWORD);
        LoginResponse tokens = login("types@test.local");

        perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, authHeader(tokens.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        refresh(tokens.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void expiredOrForeignSignedRefreshIsRejected() throws Exception {
        createUser("invalid-refresh@test.local", PASSWORD);

        for (String token : List.of(
                insertRefreshToken("invalid-refresh@test.local", signingKey, Duration.ofMinutes(-1)),
                insertRefreshToken("invalid-refresh@test.local", FOREIGN_KEY, Duration.ofDays(10)))) {
            refresh(token)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
            assertThat(row(token).get("revoked", Boolean.class)).isFalse();
        }
    }

    @Test
    void logoutInReplacementWindowRevokesReplacementToo() throws Exception {
        createUser("logout-window@test.local", PASSWORD);
        String r1 = insertRefreshToken("logout-window@test.local", signingKey, Duration.ofDays(10));
        // Путь Android: истёкший access → refresh с заменой → повтор logout с новым access, но с R1 в теле.
        JsonNode replaced = objectMapper.readTree(refresh(r1).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String access = replaced.get("accessToken").asText();
        String r2 = replaced.get("refreshToken").asText();

        logout(access, r1).andExpect(status().isOk());
        // Сам по себе отзыв R1 не затрагивает R2 — поэтому Android отзывает и полученный R2.
        assertThat(row(r2).get("revoked", Boolean.class)).isFalse();
        logout(access, r2).andExpect(status().isOk());

        assertThat(row(r2).get("revoked", Boolean.class)).isTrue();
        refresh(r2)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));
    }

    private LoginResponse login(String email) throws Exception {
        MvcResult result = perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return perform(post("/api/v1/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private ResultActions logout(String accessToken, String refreshToken) throws Exception {
        return perform(post("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, authHeader(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private String accessOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    // Refresh с заданным остатком срока: login всегда выдаёт полные 180 дней.
    private String insertRefreshToken(String email, String key, Duration remaining) {
        Instant expiration = Instant.now().plus(remaining).truncatedTo(ChronoUnit.SECONDS);
        String token = Jwts.builder()
                .claim("roles", List.of("USER"))
                .claim("token_type", "REFRESH")
                .id(UUID.randomUUID().toString())
                .subject(email)
                .issuedAt(Date.from(expiration.minus(Duration.ofDays(180))))
                .expiration(Date.from(expiration))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(key)))
                .compact();
        jdbcTemplate.update("INSERT INTO refresh_token (token, user_name, expires, revoked) VALUES (?, ?, ?, false)",
                token, email, LocalDateTime.ofInstant(expiration, ZoneId.systemDefault()));
        return token;
    }

    private TokenRow row(String token) {
        return new TokenRow(jdbcTemplate.queryForMap("SELECT * FROM refresh_token WHERE token = ?", token));
    }

    private int tokenCount(String email) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_token WHERE user_name = ?", Integer.class, email);
    }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(signingKey))).build()
                .parseSignedClaims(token).getPayload();
    }

    private Duration lifetime(Claims claims) {
        return Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());
    }

    private record TokenRow(Map<String, Object> columns) {
        <T> T get(String column, Class<T> type) {
            return type.cast(columns.get(column));
        }
    }
}
