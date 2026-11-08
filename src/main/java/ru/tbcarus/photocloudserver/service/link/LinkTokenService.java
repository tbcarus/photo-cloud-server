package ru.tbcarus.photocloudserver.service.link;

import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.exception.LinkSharingUnavailableException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Секреты публичных ссылок. Токен — 32 случайных байта в URL-safe Base64 без padding. В БД хранятся SHA-256 токена
 * (поиск) и сам токен, зашифрованный AES-GCM ключом из конфигурации (повторная выдача URL владельцу).
 * Ни токен, ни ключ в журнал не пишутся.
 */
@Service
public class LinkTokenService {

    public static final String LINK_PATH = "/s/";
    private static final int TOKEN_BYTES = 32;
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final String publicBaseUrl;

    // Пустая конфигурация отключает LINK; заданное, но неверное значение останавливает запуск: ошибку видно сразу.
    public LinkTokenService(LinkSharingProperties properties) {
        this.key = parseKey(properties.getLinkKey());
        this.publicBaseUrl = parseBaseUrl(properties.getPublicBaseUrl());
    }

    /** Выдача и показ ссылок требуют и ключа, и публичного адреса; чтение по ссылке — нет. */
    public boolean isConfigured() {
        return key != null && publicBaseUrl != null;
    }

    public void requireConfigured() {
        if (!isConfigured()) {
            throw new LinkSharingUnavailableException();
        }
    }

    public String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Форма токена проверяется до хеширования: строка другой длины или алфавита ссылкой быть не может. */
    public boolean isWellFormed(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** Base64 от nonce и шифртекста с тегом. Для каждого секрета — новый случайный nonce. */
    public String encrypt(String token) {
        requireConfigured();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Link token encryption failed", ex);
        }
    }

    /** Пусто, если секрет не расшифровывается текущим ключом: например, ключ заменён без сохранения прежнего. */
    public Optional<String> decrypt(String encrypted) {
        requireConfigured();
        try {
            byte[] data = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, NONCE_BYTES));
            byte[] token = cipher.doFinal(data, NONCE_BYTES, data.length - NONCE_BYTES);
            return Optional.of(new String(token, StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** Адрес строится из настроенного публичного адреса Server, а не из Host входящего запроса. */
    public String url(String token) {
        requireConfigured();
        return publicBaseUrl + LINK_PATH + token;
    }

    private static SecretKeySpec parseKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException ex) {
            bytes = new byte[0];
        }
        if (bytes.length != KEY_BYTES) {
            // Значение ключа в сообщение не попадает.
            throw new IllegalStateException("sharing.link-key must be Base64 of " + KEY_BYTES + " bytes");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    // Внешний адрес — только HTTPS; HTTP допустим лишь для localhost изолированных проверок.
    private static String parseBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("sharing.public-base-url is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = "https".equals(scheme) || "http".equals(scheme) && LOCAL_HOSTS.contains(host);
        if (host == null || !allowed || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalStateException("sharing.public-base-url must be an https URL (http only for localhost)");
        }
        return trimmed;
    }
}
