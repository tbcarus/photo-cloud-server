package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProfileIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";

    @Test
    void profileUsesCurrentUserFields() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);

        MvcResult result = perform(get("/api/v1/profile")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.has("createdAt")).isTrue();
        assertThat(response.has("createAt")).isFalse();
        assertThat(response.get("displayName").asText()).isEqualTo("Test User");
        assertThat(response.has("lastLoginAt")).isTrue();
        assertThat(response.get("lastLoginAt").isNull()).isFalse();
        assertThat(response.has("firstName")).isFalse();
        assertThat(response.has("lastName")).isFalse();
    }
}
