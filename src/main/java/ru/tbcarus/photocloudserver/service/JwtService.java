package ru.tbcarus.photocloudserver.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tbcarus.photocloudserver.exception.EntityNotFoundException;
import ru.tbcarus.photocloudserver.exception.InvalidRefreshTokenException;
import ru.tbcarus.photocloudserver.exception.RefreshTokenNotFoundException;
import ru.tbcarus.photocloudserver.exception.RefreshTokenOwnershipException;
import ru.tbcarus.photocloudserver.exception.TokenRevokedException;
import ru.tbcarus.photocloudserver.model.RefreshToken;
import ru.tbcarus.photocloudserver.model.TokenType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.RefreshResponse;
import ru.tbcarus.photocloudserver.repository.RefreshTokenRepository;

import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class JwtService {
    // Generates, validates, refreshes, and revokes JWT access and refresh tokens.
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${token.signing.key}")
    private String jwtSigningKey;

    @Value("${token.access-ttl-minutes}")
    private long accessTtlMinutes;
    @Value("${token.refresh-ttl-days}")
    private long refreshTtlDays;
    @Value("${token.refresh-rotation-threshold-days}")
    private long refreshRotationThresholdDays;

    @PostConstruct
    void validateConfig() {
        if (accessTtlMinutes < 1 || refreshTtlDays < 1 || refreshRotationThresholdDays < 1
                || refreshRotationThresholdDays >= refreshTtlDays) {
            throw new IllegalStateException(String.format(
                    "token.access-ttl-minutes=%d, token.refresh-ttl-days=%d and token.refresh-rotation-threshold-days=%d "
                            + "must be positive, threshold must be less than refresh TTL",
                    accessTtlMinutes, refreshTtlDays, refreshRotationThresholdDays));
        }
    }

    private Map<String, Object> generateClaims(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", user.getRoles());
        return claims;
    }

    public String generateAccessToken(User user) {
        Map<String, Object> claims = generateClaims(user);
        claims.put(TokenType.TOKEN_TYPE.getValue(), TokenType.ACCESS.getValue());
        // Срок прибавляется через Duration: в long без переполнения int.
        Instant now = Instant.now();
        return Jwts.builder()
                .claims(claims)
                .subject(user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(accessTtlMinutes))))
                .signWith(getSigningKey()).compact();
    }

    public String generateRefreshToken(User user) {
//        Optional<RefreshToken> refreshTokenFromDB = refreshTokenRepository.findByUserNameAndRevokedAndExpiresAfter(user.getUsername(), false, LocalDateTime.now());
//        if (refreshTokenFromDB.isPresent()) {
//            return refreshTokenFromDB.get().getToken();
//        }
        Map<String, Object> claims = generateClaims(user);
        claims.put(TokenType.TOKEN_TYPE.getValue(), TokenType.REFRESH.getValue());
        Instant now = Instant.now();
        String refreshToken = Jwts.builder()
                .claims(claims)
                .id(UUID.randomUUID().toString())
                .subject(user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofDays(refreshTtlDays))))
                .signWith(getSigningKey()).compact();
        RefreshToken entityRefreshToken = RefreshToken.builder()
                .token(refreshToken)
                .userName(user.getUsername())
                .expires(extractExpiration(refreshToken).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime())
                .revoked(false)
                .build();
        refreshTokenRepository.save(entityRefreshToken);
        return refreshToken;
    }

    public void revoke(String token) {
        Optional<RefreshToken> opt = refreshTokenRepository.findByToken(token);
        RefreshToken refreshToken = opt.orElseThrow(() -> new EntityNotFoundException(token, String.format("Token %s not found", token)));
        refreshToken.setRevoked(true);
        refreshToken.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(refreshToken);
    }

    public void revokeOwnedToken(String token, User user) {
        RefreshToken refreshToken = getRefreshTokenForLogout(token);
        assertOwnedBy(refreshToken, user);
        revoke(token);
    }

    public void revokeAll(User user) {
        List<RefreshToken> list = refreshTokenRepository.findAllByUserNameAndRevoked(user.getUsername(), false);
        if (!list.isEmpty()) {
            revokeList(list);
        }
    }

    public void revokeOther(String token, User user) {
        List<RefreshToken> allList = refreshTokenRepository.findAllByUserNameAndRevoked(user.getUsername(), false);
        List<RefreshToken> list = allList.stream().filter(t -> !t.getToken().equals(token)).toList();
        if (!list.isEmpty()) {
            revokeList(list);
        }
    }

    public void revokeOtherOwnedToken(String token, User user) {
        RefreshToken refreshToken = getRefreshTokenForOwnership(token);
        assertOwnedBy(refreshToken, user);
        revokeOther(token, user);
    }

    private void revokeList(List<RefreshToken> list) {
        list.forEach(t -> t.setRevoked(true));
        refreshTokenRepository.saveAll(list);
    }

    /**
     * Новый access по refresh R1. Если до истечения R1 остаётся не больше порога, R1 в той же транзакции
     * отзывается и заменяется новым refresh R2: ошибка сохранения R2 откатывает и отзыв R1.
     */
    @Transactional
    public RefreshResponse refreshTokens(User user, RefreshToken tokenDb) {
        if (tokenDb.isRevoked()) {
            throw new TokenRevokedException(tokenDb.getToken(), "token revoked");
        }
        Date expiration = validateRefreshToken(tokenDb);
        // Тот же окончательный отказ, что и для недействительного refresh: Android очищает пару и ждёт login.
        if (!user.isEnabled() || user.isBanned()) {
            throw new InvalidRefreshTokenException();
        }
        String accessToken = generateAccessToken(user);
        Instant rotationStart = expiration.toInstant().minus(Duration.ofDays(refreshRotationThresholdDays));
        if (Instant.now().isBefore(rotationStart)) {
            return new RefreshResponse(accessToken, null);
        }
        // Условный отзыв: из параллельных refresh одного R1 заменить его может только один.
        if (refreshTokenRepository.revokeIfActive(tokenDb.getId(), LocalDateTime.now()) != 1) {
            throw new TokenRevokedException(tokenDb.getToken(), "token revoked");
        }
        // TODO: если ответ с R2 не дошёл до клиента, R1 уже отозван и сессия восстанавливается только повторным login.
        return new RefreshResponse(accessToken, generateRefreshToken(user));
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String userName = extractUserName(token);
        return (userName.equals(userDetails.getUsername())) && !isTokenExpired(token);
    }

    public String extractUserName(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractTokenType(String token) {
        return extractClaim(token, c -> c.get(TokenType.TOKEN_TYPE.getValue(), String.class));
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolvers) {
        final Claims claims = extractAllClaims(token);
        return claimsResolvers.apply(claims);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser().setSigningKey(getSigningKey()).build().parseSignedClaims(token).getPayload();
    }

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSigningKey));
    }

    public RefreshToken getRefreshToken(String token) {
        return refreshTokenRepository.findByToken(token).orElseThrow(InvalidRefreshTokenException::new);
    }

    private RefreshToken getRefreshTokenForLogout(String token) {
        return refreshTokenRepository.findByToken(token).orElseThrow(RefreshTokenNotFoundException::new);
    }

    private RefreshToken getRefreshTokenForOwnership(String token) {
        return refreshTokenRepository.findByToken(token).orElseThrow(RefreshTokenNotFoundException::new);
    }

    private void assertOwnedBy(RefreshToken refreshToken, User user) {
        if (!refreshToken.getUserName().equals(user.getUsername())) {
            throw new RefreshTokenOwnershipException();
        }
    }

    private Date validateRefreshToken(RefreshToken tokenDb) {
        try {
            String token = tokenDb.getToken();
            if (!TokenType.REFRESH.getValue().equals(extractTokenType(token))) {
                throw new InvalidRefreshTokenException();
            }
            if (isTokenExpired(token)) {
                throw new InvalidRefreshTokenException();
            }
            if (!extractUserName(token).equals(tokenDb.getUserName())) {
                throw new InvalidRefreshTokenException();
            }
            return extractExpiration(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidRefreshTokenException();
        }
    }
}
