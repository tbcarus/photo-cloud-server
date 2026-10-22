package ru.tbcarus.photocloudserver.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.tbcarus.photocloudserver.model.Role;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.RefreshTokenRepository;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JwtServiceTtlTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZg==";

    // Контекст Spring без БД: сроки внедряются из application.yml.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(RefreshTokenRepository.class, () -> mock(RefreshTokenRepository.class))
            .withUserConfiguration(JwtService.class)
            .withPropertyValues("token.signing.key=" + KEY);

    private final User user = User.builder().email("ttl@test.local").roles(Set.of(Role.USER)).build();

    @Test
    void defaultTtlsAreOneDayForAccessAndHalfYearForRefreshWithoutOverflow() {
        contextRunner.run(context -> {
            JwtService service = context.getBean(JwtService.class);

            // 180 дней в миллисекундах не помещаются в int: прежняя арифметика дала бы exp в прошлом.
            assertThat(lifetime(service.generateAccessToken(user))).isEqualTo(Duration.ofMinutes(1440));
            assertThat(lifetime(service.generateRefreshToken(user))).isEqualTo(Duration.ofDays(180));
        });
    }

    @Test
    void nonPositiveValuesOrThresholdNotLessThanRefreshTtlFailStartup() {
        for (String[] properties : new String[][]{
                {"token.access-ttl-minutes=0"},
                {"token.refresh-ttl-days=-1"},
                {"token.refresh-rotation-threshold-days=0"},
                {"token.refresh-ttl-days=30", "token.refresh-rotation-threshold-days=30"},
                {"token.refresh-ttl-days=30", "token.refresh-rotation-threshold-days=31"}}) {
            contextRunner.withPropertyValues(properties)
                    .run(context -> assertThat(context).as(String.join(", ", properties)).hasFailed());
        }
        contextRunner.withPropertyValues("token.refresh-ttl-days=31", "token.refresh-rotation-threshold-days=30")
                .run(context -> assertThat(context).hasNotFailed());
    }

    private Duration lifetime(String token) {
        Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(KEY))).build()
                .parseSignedClaims(token).getPayload();
        return Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());
    }
}
