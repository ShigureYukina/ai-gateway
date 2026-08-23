package io.gateway.oss.admin.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserControllerTest extends AdminInMemoryWebIntegrationTestBase {

    @Test
    void shouldReturnUsersList() {
        registerUser("phase5-user", "pass123");

        webTestClient.get()
                .uri("/admin/users")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("\"generatedAt\"");
                    assertThat(body).contains("\"username\":\"phase5-user\"");
                    assertThat(body).contains("\"role\":\"user\"");
                });
    }

    @Test
    void shouldReturn401WithoutAuthForUsersList() {
        webTestClient.get()
                .uri("/admin/users")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("unauthorized");
    }

    @Test
    void shouldCreateNewUser() {
        webTestClient.post()
                .uri("/admin/users")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "username", "created-user",
                        "password", "pass123",
                        "role", "user",
                        "displayName", "Created User",
                        "email", "created@example.com"
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.username").isEqualTo("created-user")
                .jsonPath("$.role").isEqualTo("user")
                .jsonPath("$.displayName").isEqualTo("Created User")
                .jsonPath("$.email").isEqualTo("created@example.com");
    }

    @Test
    void shouldUpdateUserRole() {
        registerUser("promote-user", "pass123");

        webTestClient.put()
                .uri("/admin/users/promote-user")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "admin", "displayName", "Promoted User", "email", "promoted@example.com"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("promote-user")
                .jsonPath("$.role").isEqualTo("admin")
                .jsonPath("$.displayName").isEqualTo("Promoted User")
                .jsonPath("$.email").isEqualTo("promoted@example.com");
    }

    @Test
    void shouldUpdateUserLimits() {
        registerUser("limits-user", "pass123");

        webTestClient.put()
                .uri("/admin/users/limits-user/limits")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "dailyTokens", 500000,
                        "monthlyTokens", 15000000,
                        "tokensPerMinute", 50000,
                        "maxTokens", 4096,
                        "dailyCost", 50.0,
                        "monthlyCost", 1500.0
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("limits-user")
                .jsonPath("$.limits.dailyTokens").isEqualTo(500000)
                .jsonPath("$.limits.monthlyTokens").isEqualTo(15000000)
                .jsonPath("$.limits.tokensPerMinute").isEqualTo(50000)
                .jsonPath("$.limits.maxTokens").isEqualTo(4096)
                .jsonPath("$.limits.dailyCost").isEqualTo(50.0)
                .jsonPath("$.limits.monthlyCost").isEqualTo(1500.0);
    }

    @Test
    void shouldDeleteUser() {
        registerUser("delete-user", "pass123");

        webTestClient.delete()
                .uri("/admin/users/delete-user")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void shouldResetPassword() {
        registerUser("reset-user", "old-pass");

        webTestClient.post()
                .uri("/admin/users/reset-user/reset-password")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.temporaryPassword").isNotEmpty()
                .jsonPath("$.temporaryPassword").value(v -> assertThat(String.valueOf(v)).isNotEqualTo("old-pass"));
    }

    @Test
    void shouldListApiKeys() {
        registerUser("key-user", "pass123");

        webTestClient.get()
                .uri("/admin/users/key-user/api-keys")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].keyId").isEqualTo("primary")
                .jsonPath("$[0].enabled").isEqualTo(true)
                .jsonPath("$[0].apiKeyMasked").value(v -> assertThat(String.valueOf(v)).startsWith("****"));
    }

    @Test
    void shouldCreateApiKey() {
        registerUser("admin-key-user", "pass123");

        webTestClient.post()
                .uri("/admin/users/admin-key-user/api-keys")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "admin-created-key", "allowedModels", List.of("gpt-4o-mini")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("admin-created-key")
                .jsonPath("$.apiKeyMasked").value(v -> assertThat(String.valueOf(v)).startsWith("gw-"))
                .jsonPath("$.allowedModels[0]").isEqualTo("gpt-4o-mini")
                .jsonPath("$.enabled").isEqualTo(true);
    }

    @Test
    void shouldUpdateApiKey() {
        registerUser("patch-key-user", "pass123");

        webTestClient.patch()
                .uri("/admin/users/patch-key-user/api-keys/primary")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "enabled", false,
                        "name", "patched-key",
                        "allowedModels", List.of("gpt-4o-mini")
                ))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get()
                .uri("/admin/users/patch-key-user/api-keys")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].enabled").isEqualTo(false)
                .jsonPath("$[0].name").isEqualTo("patched-key")
                .jsonPath("$[0].allowedModels[0]").isEqualTo("gpt-4o-mini");
    }

    @Test
    void shouldDeleteApiKey() {
        registerUser("del-key-user", "pass123");

        webTestClient.post()
                .uri("/admin/users/del-key-user/api-keys")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "to-delete"))
                .exchange()
                .expectStatus().isCreated();

        webTestClient.delete()
                .uri("/admin/users/del-key-user/api-keys/primary")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void shouldToggleApiKey() {
        registerUser("toggle-key-user", "pass123");

        webTestClient.put()
                .uri("/admin/users/toggle-key-user/api-keys/primary/toggle")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get()
                .uri("/admin/users/toggle-key-user/api-keys")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].enabled").isEqualTo(false);
    }

    @Test
    void shouldRotateApiKey() {
        registerUser("rotate-user", "rotate123");
        String userToken = loginAndGetAccessToken("rotate-user", "rotate123");

        String keyIdBody = webTestClient.get()
                .uri("/auth/keys")
                .header("Authorization", "Bearer " + userToken)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        String extractedKeyId = extractJsonValue(keyIdBody, "keyId");

        webTestClient.post()
                .uri("/admin/users/rotate-user/api-keys/" + extractedKeyId + "/rotate")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.keyId").isNotEmpty()
                .jsonPath("$.keyId").value(v -> assertThat((String) v).isNotEqualTo(extractedKeyId))
                .jsonPath("$.apiKey").isNotEmpty()
                .jsonPath("$.enabled").isEqualTo(true);
    }

    @Test
    void shouldUpdateAllowedModels() {
        registerUser("am-user", "pass123");

        webTestClient.put()
                .uri("/admin/users/am-user/allowed-models")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("allowedModels", List.of("gpt-4o-mini", "gpt-4o")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.allowedModels").isArray()
                .jsonPath("$.allowedModels.length()").isEqualTo(2);
    }

    private void registerUser(String username, String password) {
        webTestClient.post()
                .uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk();
    }
    private String extractJsonValue(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
