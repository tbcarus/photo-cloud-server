package ru.tbcarus.photocloudserver.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.exception.EntityNotFoundException;
import ru.tbcarus.photocloudserver.exception.TokenRevokedException;
import ru.tbcarus.photocloudserver.model.RefreshToken;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.RefreshTokenRepository;

import java.security.Key;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class JwtService {
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${token.signing.key}")
    private String jwtSigningKey;

    private final long expirationTime = 5 * 60 * 1000;
    private final long refreshExpirationTime = 7 * 24 * 60 * 60 * 1000;

    private Map<String, Object> generateClaims(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", user.getRoles());
        return claims;
    }

    public String generateAccessToken(User user) {
        return Jwts.builder()
                .claims(generateClaims(user))
                .subject(user.getUsername())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(getSigningKey()).compact();
    }

    public String generateRefreshToken(User user) {
//        Optional<RefreshToken> refreshTokenFromDB = refreshTokenRepository.findByUserNameAndRevokedAndExpiresAfter(user.getUsername(), false, LocalDateTime.now());
//        if (refreshTokenFromDB.isPresent()) {
//            return refreshTokenFromDB.get().getToken();
//        }
        String refreshToken = Jwts.builder()
                .claims(generateClaims(user))
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + refreshExpirationTime))
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

    private void revokeList(List<RefreshToken> list) {
        list.forEach(t -> t.setRevoked(true));
        refreshTokenRepository.saveAll(list);
    }

    public String refreshAccessToken(User user, RefreshToken tokenDb) {
        if (tokenDb.isRevoked()) {
            throw new TokenRevokedException(tokenDb.getToken(), "token revoked");
        }
        return generateAccessToken(user);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String userName = extractUserName(token);
        return (userName.equals(userDetails.getUsername())) && !isTokenExpired(token);
    }

    public String extractUserName(String token) {
        return extractClaim(token, Claims::getSubject);
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
        return refreshTokenRepository.findByToken(token).orElseThrow(() ->
                new EntityNotFoundException(token, String.format("Token %s not found", token)));
    }
}
