package ru.tbcarus.photocloudserver.service.link;

import org.junit.jupiter.api.Test;
import ru.tbcarus.photocloudserver.exception.LinkSharingUnavailableException;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkTokenServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});

    private static LinkTokenService service(String baseUrl, String key) {
        LinkSharingProperties properties = new LinkSharingProperties();
        properties.setPublicBaseUrl(baseUrl);
        properties.setLinkKey(key);
        return new LinkTokenService(properties);
    }

    @Test
    void tokensAreRandomUrlSafeAndEncryptedWithFreshNonce() {
        LinkTokenService links = service("https://cloud.example.com/", KEY);
        String token = links.generateToken();

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(links.generateToken()).isNotEqualTo(token);
        assertThat(links.hash(token)).hasSize(64).isEqualTo(links.hash(token));
        String first = links.encrypt(token);
        assertThat(links.encrypt(token)).isNotEqualTo(first);
        assertThat(links.decrypt(first)).contains(token);
        assertThat(links.url(token)).isEqualTo("https://cloud.example.com/s/" + token);
        // Другой ключ секрет не восстанавливает: URL не выдаётся, но сама ссылка ищется по хешу.
        assertThat(service("https://cloud.example.com", OTHER_KEY).decrypt(first)).isEmpty();
    }

    @Test
    void missingConfigurationDisablesLinksWithoutBreakingStartup() {
        LinkTokenService noKey = service("https://cloud.example.com", "");
        LinkTokenService noUrl = service(null, KEY);

        assertThat(noKey.isConfigured()).isFalse();
        assertThat(noUrl.isConfigured()).isFalse();
        assertThatThrownBy(noKey::requireConfigured).isInstanceOf(LinkSharingUnavailableException.class);
        assertThatThrownBy(() -> noUrl.url("x")).isInstanceOf(LinkSharingUnavailableException.class);
        // Проверка формата и хеш не зависят от конфигурации: чтение по выданной ссылке продолжает работать.
        assertThat(noKey.isWellFormed("A".repeat(43))).isTrue();
    }

    @Test
    void invalidExplicitConfigurationStopsStartup() {
        assertThatThrownBy(() -> service("https://cloud.example.com", "c2hvcnQ=")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("c2hvcnQ=");
        assertThatThrownBy(() -> service("http://cloud.example.com", KEY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service("https://cloud.example.com/?x=1", KEY)).isInstanceOf(IllegalStateException.class);
        assertThat(service("http://localhost:18081", KEY).url("t")).isEqualTo("http://localhost:18081/s/t");
    }
}
