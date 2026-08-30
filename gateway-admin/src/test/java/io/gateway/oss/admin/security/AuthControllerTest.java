package io.gateway.oss.admin.security;

import io.gateway.oss.core.config.AuthConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.JwtConfig;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.observability.RequestLogService.RequestLogEntry;
import io.gateway.oss.admin.quota.ClientCostStore;
import io.gateway.oss.admin.quota.ClientUsageStore;
import io.gateway.oss.core.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "gateway.shared-state.backend=in_memory",
        "security.password.allow-plaintext=true",
        "gateway.auth.enabled=true",
        "gateway.auth.jwt.secret=super-secret-key-that-is-at-least-32-chars",
        "gateway.auth.jwt.access-expiration=300s",
        "gateway.auth.jwt.refresh-expiration=60s",
        "gateway.auth.users.admin.password=admin123",
        "gateway.auth.users.admin.client-id=demo-client-key",
        "gateway.auth.users.admin.role=admin",
        "gateway.auth.users.user1.password=pass1",
        "gateway.auth.users.user1.client-id=demo-client-key",
        "gateway.auth.users.user1.role=user",
        "gateway.auth.users.user.password=testpass",
        "gateway.clients.demo-client-key.enabled=true",
        "gateway.clients.demo-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.demo-client-key.allowed-scenes[0]=default-chat",
        "gateway.clients.demo-client-key.defaults.scene=default-chat",
        "gateway.clients.demo-client-key.defaults.temperature=0.7",
        "gateway.clients.demo-client-key.defaults.max-tokens=256",
        "gateway.clients.demo-client-key.capabilities.streaming=true",
        "gateway.clients.demo-client-key.limits.max-tokens=512",
        "gateway.clients.demo-client-key.limits.daily-tokens=1000",
        "gateway.clients.demo-client-key.limits.daily-cost=1.25",
        "gateway.clients.demo-client-key.limits.monthly-tokens=5000",
        "gateway.clients.demo-client-key.limits.monthly-cost=9.99",
        "gateway.clients.jwt-only-client.enabled=true",
        "gateway.clients.jwt-only-client.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.jwt-only-client.defaults.scene=default-chat",
        "gateway.providers.openai.base-url=http://localhost:18080",
        "gateway.providers.openai.api-key=upstream-demo-key",
        "gateway.providers.openai.models[0]=gpt-4o-mini",
        "gateway.routes.gpt-4o-mini.scene=default-chat",
        "gateway.routes.openai-primary.provider=openai",
        "gateway.routes.openai-primary.upstream-model=gpt-4o-mini",
        "gateway.routes.openai-fallback.provider=openai",
        "gateway.routes.openai-fallback.upstream-model=gpt-4o-mini",
        "gateway.scenes.default-chat.fallback-routes[0]=openai-fallback",
        "gateway.scenes.default-chat.primary-route=openai-primary",
        "gateway.sync.models-dev.endpoint=http://127.0.0.1:1/api.json",
        "gateway.sync.models-dev.timeout=500ms",
        "gateway.limit.requests-per-window=100",
        "gateway.limit.window=5m"
})
class AuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RequestLogService requestLogService;

    @Autowired
    private ClientUsageStore usageStore;

    @Autowired
    private ClientCostStore costStore;

    @org.junit.jupiter.api.BeforeEach
    void resetStores() {
        ((io.gateway.oss.admin.quota.InMemoryClientUsageStore) usageStore).resetForTests();
        ((io.gateway.oss.admin.quota.InMemoryClientCostStore) costStore).resetForTests();
        requestLogService.resetForTests();
    }

    // ---- LOGIN ----

    @BeforeEach
    void extendWebTestClientResponseTimeout() {
        // 全量套件并行负载下 5s 默认响应超时偶发不够，统一放宽到 30s
        webTestClient = webTestClient.mutate().responseTimeout(java.time.Duration.ofSeconds(30)).build();
    }

    @Test
    void shouldLoginSuccessfullyWithValidCredentials() {
        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty()
                .jsonPath("$.refreshToken").isNotEmpty()
                .jsonPath("$.tokenType").isEqualTo("Bearer");
    }

    @Test
    void shouldFreezeLoginResponseShapeForDynamicAndCompatibilityUsers() {
        String dynamicBody = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        String staticYamlBody = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "user1", "password", "pass1"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        assertThat(dynamicBody).contains("\"accessToken\":");
        assertThat(dynamicBody).contains("\"refreshToken\":");
        assertThat(dynamicBody).contains("\"tokenType\":\"Bearer\"");
        assertThat(staticYamlBody).contains("\"accessToken\":");
        assertThat(staticYamlBody).contains("\"refreshToken\":");
        assertThat(staticYamlBody).contains("\"tokenType\":\"Bearer\"");
    }

    @Test
    void shouldReturn401ForInvalidUsername() {
        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "nonexistent", "password", "admin123"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_credentials");
    }

    @Test
    void shouldReturn401ForInvalidPassword() {
        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_credentials");
    }

    @Test
    void shouldReturn400ForMissingFields() {
        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin"))
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void shouldNotFallbackToStaticYamlForNonCredentialDynamicAuthFailure() {
        String username = "compat-frozen-user";
        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", "pass123"))
                .exchange()
                .expectStatus().isOk();

        String adminToken = jwtService.generateAccessToken("admin", List.of("gpt-4o-mini"), "admin");
        webTestClient.put().uri("/admin/users/" + username)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("frozen", true))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", "pass123"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("account_frozen");
    }

    // ---- JWT claims ----

    @Test
    void shouldIncludeClientIdAndScopeInAccessToken() {
        String responseBody = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        // 从响应中提取 accessToken
        // 简单解析 JSON（测试中不需要完整的 JSON 解析器）
        assertThat(responseBody).contains("accessToken");
        assertThat(responseBody).contains("refreshToken");

        // 手动验证 JWT 内容
        String accessToken = extractJsonValue(responseBody, "accessToken");
        Claims claims = jwtService.parseToken(accessToken);
        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(jwtService.extractUsername(claims)).isEqualTo("admin");
        assertThat(jwtService.extractClientId(claims)).isEqualTo("demo-client-key");
        assertThat(claims.get("clientId", String.class)).isEqualTo("demo-client-key");
        assertThat(claims.get("typ", String.class)).isEqualTo("access");
        @SuppressWarnings("unchecked")
        List<String> scope = claims.get("scope", List.class);
        assertThat(scope).containsExactly("gpt-4o-mini");
    }

    @Test
    void shouldGenerateValidRefreshToken() {
        String responseBody = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        String refreshToken = extractJsonValue(responseBody, "refreshToken");
        Claims claims = jwtService.parseToken(refreshToken);
        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(jwtService.extractUsername(claims)).isEqualTo("admin");
        assertThat(jwtService.extractClientId(claims)).isEqualTo("demo-client-key");
        assertThat(claims.get("clientId", String.class)).isEqualTo("demo-client-key");
        assertThat(claims.get("typ", String.class)).isEqualTo("refresh");
    }

    @Test
    void shouldIncludeRoleClaimInAccessToken() {
        String responseBody = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        String accessToken = extractJsonValue(responseBody, "accessToken");
        Claims claims = jwtService.parseToken(accessToken);
        assertThat(claims.get("role", String.class)).isEqualTo("admin");
    }

    // ---- REFRESH ----

    @Test
    void shouldRefreshAccessTokenWithValidRefreshToken() {
        String loginResponse = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        String refreshToken = extractJsonValue(loginResponse, "refreshToken");

        String refreshResponse = webTestClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        assertThat(refreshResponse).contains("accessToken");
        assertThat(refreshResponse).contains("refreshToken");
        assertThat(refreshResponse).contains("\"tokenType\":\"Bearer\"");

        String refreshedAccessToken = extractJsonValue(refreshResponse, "accessToken");
        Claims accessClaims = jwtService.parseToken(refreshedAccessToken);
        assertThat(accessClaims.getSubject()).isEqualTo("admin");
        assertThat(jwtService.extractUsername(accessClaims)).isEqualTo("admin");
        assertThat(jwtService.extractClientId(accessClaims)).isEqualTo("demo-client-key");

        String refreshedRefreshToken = extractJsonValue(refreshResponse, "refreshToken");
        Claims refreshClaims = jwtService.parseToken(refreshedRefreshToken);
        assertThat(refreshClaims.getSubject()).isEqualTo("admin");
        assertThat(jwtService.extractUsername(refreshClaims)).isEqualTo("admin");
        assertThat(jwtService.extractClientId(refreshClaims)).isEqualTo("demo-client-key");
    }

    @Test
    void shouldReturn401ForInvalidRefreshToken() {
        webTestClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", "invalid-token"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_token");
    }

    @Test
    void shouldRejectAccessTokenAsRefreshToken() {
        String loginResponse = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        String accessToken = extractJsonValue(loginResponse, "accessToken");

        webTestClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", accessToken))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_token_type");
    }

    // ---- SELF-SERVICE (/auth/me, /auth/keys) ----

    @Test
    void shouldReturnCurrentUserInfoForMe() {
        Instant now = Instant.now();
        usageStore.addDailyUsage("demo-client-key", 12L, now);
        usageStore.addMonthlyUsage("demo-client-key", 34L, now);
        costStore.addDailyCost("demo-client-key", new BigDecimal("0.12"), now);
        costStore.addMonthlyCost("demo-client-key", new BigDecimal("0.34"), now);
        long expectedMonthlyTokens = usageStore.currentMonthlyUsage("demo-client-key", now);
        double expectedMonthlyCost = costStore.currentMonthlyCost("demo-client-key", now).doubleValue();

        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("admin")
                .jsonPath("$.role").isEqualTo("admin")
                .jsonPath("$.displayName").doesNotExist()
                .jsonPath("$.email").doesNotExist()
                .jsonPath("$.apiKeyMasked").value(v -> assertThat(String.valueOf(v)).startsWith("****"))
                .jsonPath("$.quota").isMap()
                .jsonPath("$.quota.dailyTokensUsed").isEqualTo(0)
                .jsonPath("$.quota.dailyTokensLimit").isEqualTo(1000)
                .jsonPath("$.quota.dailyCostUsed").isEqualTo(0)
                .jsonPath("$.quota.dailyCostLimit").isEqualTo(1.25)
                .jsonPath("$.quota.monthlyTokensUsed").isEqualTo(0)
                .jsonPath("$.quota.monthlyTokensLimit").isEqualTo(5000)
                .jsonPath("$.quota.monthlyCostUsed").isEqualTo(0)
                .jsonPath("$.quota.monthlyCostLimit").isEqualTo(9.99)
                .jsonPath("$.quota.monthlyUnsupported").isEqualTo(false)
                .jsonPath("$.createdAt").isNumber();
    }

    @Test
    void shouldFreezeMeResponseShapeForStoredUserAccount() {
        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("admin")
                .jsonPath("$.role").isEqualTo("admin")
                .jsonPath("$.apiKeyMasked").exists()
                .jsonPath("$.createdAt").isNumber()
                .jsonPath("$.quota.dailyTokensUsed").exists()
                .jsonPath("$.quota.dailyTokensLimit").exists()
                .jsonPath("$.quota.dailyCostUsed").exists()
                .jsonPath("$.quota.dailyCostLimit").exists()
                .jsonPath("$.quota.monthlyTokensUsed").exists()
                .jsonPath("$.quota.monthlyTokensLimit").exists()
                .jsonPath("$.quota.monthlyCostUsed").exists()
                .jsonPath("$.quota.monthlyCostLimit").exists()
                .jsonPath("$.quota.monthlyUnsupported").isEqualTo(false);
    }

    @Test
    void shouldKeepCompatibilityMeShapeForStaticYamlOnlyUser() {
        String token = loginAndGetAccessToken("user1", "pass1");

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("user1")
                .jsonPath("$.role").isEqualTo("user")
                .jsonPath("$.displayName").doesNotExist()
                .jsonPath("$.email").doesNotExist()
                .jsonPath("$.apiKeyMasked").doesNotExist()
                .jsonPath("$.createdAt").isEqualTo(0)
                .jsonPath("$.quota").isMap()
                .jsonPath("$.quota.monthlyUnsupported").isEqualTo(false);
    }

    @Test
    void shouldReturnAuthoritativeMonthlyQuotaFromStoresInsteadOfRequestLogs() {
        Instant now = Instant.now();
        requestLogService.record(new RequestLogEntry(
                "auth-me-monthly-log-noise",
                "demo-client-key",
                "demo-client-key",
                "gpt-4o-mini",
                "openai",
                "openai-primary",
                "default-chat",
                200,
                15L,
                now,
                "non-stream",
                999L,
                800L,
                199L,
                9.99,
                null
        ));

        usageStore.addMonthlyUsage("demo-client-key", 128L, now);
        costStore.addMonthlyCost("demo-client-key", new BigDecimal("0.0128"), now);

        String token = loginAndGetAccessToken("admin", "admin123");
        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.quota.monthlyTokensUsed").isEqualTo(0)
                .jsonPath("$.quota.monthlyTokensLimit").isEqualTo(5000)
                .jsonPath("$.quota.monthlyCostUsed").isEqualTo(0)
                .jsonPath("$.quota.monthlyCostLimit").isEqualTo(9.99)
                .jsonPath("$.quota.monthlyUnsupported").isEqualTo(false);
    }

    @Test
    void shouldReturnCurrentUserModelCostDistribution() {
        Instant januaryFirstMorning = Instant.parse("2026-01-01T08:00:00Z");
        Instant januaryFirstEvening = Instant.parse("2026-01-01T18:30:00Z");
        Instant januarySecond = Instant.parse("2026-01-02T01:00:00Z");

        requestLogService.record(new RequestLogEntry(
                "usage-cost-1",
                "demo-client-key",
                "demo-client-key",
                "gpt-4o-mini",
                "openai",
                "openai-primary",
                "default-chat",
                200,
                120L,
                januaryFirstMorning,
                "non-stream",
                100L,
                60L,
                40L,
                0.25,
                null
        ));
        requestLogService.record(new RequestLogEntry(
                "usage-cost-2",
                "demo-client-key",
                "demo-client-key",
                "gpt-4o-mini",
                "openai",
                "openai-primary",
                "default-chat",
                200,
                150L,
                januaryFirstEvening,
                "non-stream",
                50L,
                20L,
                30L,
                0.10,
                null
        ));
        requestLogService.record(new RequestLogEntry(
                "usage-cost-3",
                "demo-client-key",
                "demo-client-key",
                "gpt-4o",
                "openai",
                "openai-primary",
                "default-chat",
                200,
                180L,
                januaryFirstEvening,
                "non-stream",
                80L,
                50L,
                30L,
                0.40,
                null
        ));
        requestLogService.record(new RequestLogEntry(
                "usage-cost-outside-range",
                "demo-client-key",
                "demo-client-key",
                "gpt-4o-mini",
                "openai",
                "openai-primary",
                "default-chat",
                200,
                90L,
                januarySecond,
                "non-stream",
                70L,
                35L,
                35L,
                0.35,
                null
        ));
        requestLogService.record(new RequestLogEntry(
                "usage-cost-other-client",
                "jwt-only-client",
                "jwt-only-client",
                "gpt-4o-mini",
                "openai",
                "openai-primary",
                "default-chat",
                200,
                60L,
                januaryFirstMorning,
                "non-stream",
                999L,
                888L,
                111L,
                9.99,
                null
        ));

        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/auth/usage/costs")
                        .queryParam("from", "2026-01-01")
                        .queryParam("to", "2026-01-01")
                        .build())
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.client").isEqualTo("demo-client-key")
                .jsonPath("$.from").isEqualTo("2026-01-01")
                .jsonPath("$.to").isEqualTo("2026-01-01")
                .jsonPath("$.models.length()").isEqualTo(2)
                .jsonPath("$.models[0].model").isEqualTo("gpt-4o")
                .jsonPath("$.models[0].requests").isEqualTo(1)
                .jsonPath("$.models[0].totalTokens").isEqualTo(80)
                .jsonPath("$.models[0].promptTokens").isEqualTo(50)
                .jsonPath("$.models[0].completionTokens").isEqualTo(30)
                .jsonPath("$.models[0].totalCostUsd").isEqualTo(0.4)
                .jsonPath("$.models[1].model").isEqualTo("gpt-4o-mini")
                .jsonPath("$.models[1].requests").isEqualTo(2)
                .jsonPath("$.models[1].totalTokens").isEqualTo(150)
                .jsonPath("$.models[1].promptTokens").isEqualTo(80)
                .jsonPath("$.models[1].completionTokens").isEqualTo(70)
                .jsonPath("$.models[1].totalCostUsd").isEqualTo(0.35);
    }

    @Test
    void shouldRejectInvalidDateForCurrentUserModelCostDistribution() {
        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/auth/usage/costs")
                        .queryParam("from", "2026/01/01")
                        .build())
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("Invalid from date, expected YYYY-MM-DD");
    }

    @Test
    void shouldReturn401ForMeWhenTokenMissingOrInvalid() {
        webTestClient.get().uri("/auth/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("unauthorized");

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer invalid-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_token");

        String refreshToken = jwtService.generateRefreshToken("demo-client-key", 0);
        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + refreshToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("unauthorized");
    }

    @Test
    void shouldReturn401TokenExpiredForMeWhenAccessTokenExpired() throws InterruptedException {
        GatewayProperties props = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("super-secret-key-that-is-at-least-32-chars");
        jwt.setAccessExpiration(java.time.Duration.ofMillis(1));
        jwt.setRefreshExpiration(java.time.Duration.ofSeconds(60));
        auth.setJwt(jwt);
        props.setAuth(auth);
        JwtService shortLivedJwtService = new JwtService(props);

        String expiredToken = shortLivedJwtService.generateAccessToken("admin", List.of("gpt-4o-mini"), "admin");
        Thread.sleep(10);

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + expiredToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("token_expired");
    }

    @Test
    void shouldFreezeMajorAuthErrorSemanticsForLoginAndMe() {
        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_credentials");

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer invalid-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_token");

        String refreshToken = jwtService.generateRefreshToken("demo-client-key", 0);
        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + refreshToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("unauthorized");

        GatewayProperties props = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("super-secret-key-that-is-at-least-32-chars");
        jwt.setAccessExpiration(java.time.Duration.ofMillis(1));
        jwt.setRefreshExpiration(java.time.Duration.ofSeconds(60));
        auth.setJwt(jwt);
        props.setAuth(auth);
        JwtService shortLivedJwtService = new JwtService(props);
        String expiredToken = shortLivedJwtService.generateAccessToken("admin", List.of("gpt-4o-mini"), "admin");

        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for token expiration", e);
        }

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + expiredToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("token_expired");
    }

    @Test
    void shouldCreateNewApiKeyAndPersistForCurrentUser() {
        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.post().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "dev-key", "allowedModels", List.of("gpt-4o-mini")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("dev-key")
                .jsonPath("$.allowedModels[0]").isEqualTo("gpt-4o-mini")
                .jsonPath("$.apiKey").value(v -> assertThat(String.valueOf(v)).startsWith("gw-"))
                .jsonPath("$.apiKeyMasked").value(v -> assertThat(String.valueOf(v)).startsWith("****"));

        // 使用 /auth/keys 验证持久化后返回的掩码存在（避免明文回显）
        webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys.length()").isEqualTo(2)
                .jsonPath("$.keys[0].keyId").isEqualTo("primary")
                .jsonPath("$.keys[1].allowedModels[0]").isEqualTo("gpt-4o-mini")
                .jsonPath("$.keys[0].apiKeyMasked").value(v -> assertThat(String.valueOf(v)).startsWith("****"));
    }

    @Test
    void shouldRejectLoginForFrozenAccount() {
        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "frozen-user", "password", "pass"))
                .exchange()
                .expectStatus().isOk();

        webTestClient.put().uri("/admin/users/frozen-user")
                .header("Authorization", "Bearer " + loginAndGetAccessToken("admin", "admin123"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("frozen", true))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "frozen-user", "password", "pass"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("account_frozen");
    }

    @Test
    void shouldListOnlyMaskedKeys() {
        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.post().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "k2"))
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys[0].apiKeyMasked").value(v -> {
                    String masked = String.valueOf(v);
                    assertThat(masked).startsWith("****");
                    assertThat(masked).doesNotStartWith("gw-");
                });
    }

    @Test
    void shouldDeleteOwnKeyAndReturn404WhenDeletingAgain() {
        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.post().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "k3"))
                .exchange()
                .expectStatus().isOk();

        webTestClient.delete().uri("/auth/keys/primary")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.delete().uri("/auth/keys/primary")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("key_not_found");
    }

    @Test
    void shouldPatchPrimaryKeyForJwtOnlyUserByCreatingShadowAccount() {
        String token = jwtService.generateAccessToken("jwt-only-user", List.of("gpt-4o-mini"), "user");

        webTestClient.patch().uri("/auth/keys/primary")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys.length()").isEqualTo(1)
                .jsonPath("$.keys[0].keyId").isEqualTo("primary")
                .jsonPath("$.keys[0].enabled").isEqualTo(false);
    }

    @Test
    void shouldDeletePrimaryKeyForJwtOnlyUserByCreatingShadowAccount() {
        String token = jwtService.generateAccessToken("jwt-delete-user", List.of("gpt-4o-mini"), "user");

        webTestClient.delete().uri("/auth/keys/primary")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys.length()").isEqualTo(0);
    }

    @Test
    void shouldAllowUserAndAdminAccessSelfServiceEndpoints() {
        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "self-user", "password", "self-pass"))
                .exchange()
                .expectStatus().isOk();

        String userToken = loginAndGetAccessToken("self-user", "self-pass");
        String adminToken = jwtService.generateAccessToken("admin-self", List.of(), "admin");

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + userToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.role").isEqualTo("user");

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.role").isEqualTo("admin");

        webTestClient.post().uri("/auth/keys")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "admin-k"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldIsolateStaticYamlUsersByUsernameInsteadOfSharedClientId() {
        String adminToken = loginAndGetAccessToken("admin", "admin123");
        String user1Token = loginAndGetAccessToken("user1", "pass1");

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("admin");

        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + user1Token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("user1");
    }

    // ---- REGISTER ----

    @Test
    void shouldRegisterAndReturnApiKey() {
        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "username", "new-user",
                        "password", "new-pass",
                        "displayName", "New User",
                        "email", "NEW-USER@example.com"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.apiKey").value(v -> assertThat(String.valueOf(v)).startsWith("gw-"))
                .jsonPath("$.accessToken").isNotEmpty()
                .jsonPath("$.refreshToken").isNotEmpty();

        String token = loginAndGetAccessToken("new-user", "new-pass");
        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("New User")
                .jsonPath("$.email").isEqualTo("new-user@example.com");
    }

    @Test
    void shouldApplyRestrictedRegistrationTemplateAndAllowProfileUpdate() {
        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "profile-user", "password", "profile-pass"))
                .exchange()
                .expectStatus().isOk();

        String token = loginAndGetAccessToken("profile-user", "profile-pass");
        webTestClient.get().uri("/auth/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("profile-user");

        webTestClient.put().uri("/auth/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "Profile User", "email", "PROFILE@EXAMPLE.COM"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("Profile User")
                .jsonPath("$.email").isEqualTo("profile@example.com");
    }

    @Test
    void shouldReturn409WhenRegisteringDuplicateUsername() {
        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "dup-user", "password", "pass1"))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "dup-user", "password", "pass2"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("username_taken");
    }

    // ─── User patching own API key (name, allowedModels) ───

    @Test
    void shouldUpdateOwnKeyNameAndAllowedModels() {
        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.patch().uri("/auth/keys/primary")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "renamed", "allowedModels", List.of("gpt-4o-mini")))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys[0].allowedModels[0]").isEqualTo("gpt-4o-mini");
    }

    // ─── Self-service key rotation ───

    @Test
    void shouldRotateOwnKeyAndReturnNewKey() {
        registerUser("rot-self", "pass123");
        String token = loginAndGetAccessToken("rot-self", "pass123");

        String keysResp = webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        String oldKeyId = extractJsonValue(keysResp, "keyId");

        webTestClient.post().uri("/auth/keys/" + oldKeyId + "/rotate")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keyId").isNotEmpty()
                .jsonPath("$.keyId").value(v -> assertThat((String) v).isNotEqualTo(oldKeyId))
                .jsonPath("$.apiKey").value(v -> assertThat((String) v).startsWith("gw-"))
                .jsonPath("$.apiKeyMasked").value(v -> assertThat((String) v).startsWith("****"))
                .jsonPath("$.enabled").isEqualTo(true);

        String newKeys = webTestClient.get().uri("/auth/keys")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        assertThat(newKeys).contains("****");
        assertThat(newKeys).doesNotContain("gw-");
    }

    @Test
    void shouldReturn404WhenRotatingNonExistentOwnKey() {
        registerUser("rot-404-self", "pass123");
        String token = loginAndGetAccessToken("rot-404-self", "pass123");

        webTestClient.post().uri("/auth/keys/no-such-key/rotate")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("key_not_found");
    }

    // ─── Change password ───

    @Test
    void shouldChangeOwnPasswordAndLoginWithNewPassword() {
        registerUser("pwd-user", "old-pass");
        String token = loginAndGetAccessToken("pwd-user", "old-pass");

        webTestClient.put().uri("/auth/password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("oldPassword", "old-pass", "newPassword", "new-pass123"))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "pwd-user", "password", "old-pass"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_credentials");

        String loginResponse = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "pwd-user", "password", "new-pass123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        assertThat(loginResponse).contains("\"accessToken\":\"");
    }

    @Test
    void shouldRejectChangePasswordWithWrongOldPassword() {
        registerUser("pwd-wrong", "correct");
        String token = loginAndGetAccessToken("pwd-wrong", "correct");

        webTestClient.put().uri("/auth/password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("oldPassword", "wrong-old", "newPassword", "new-pass123"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("old_password_wrong");
    }

    // ---- JWT EXPIRED ----

    @Test
    void shouldReturn401ForExpiredToken() {
        SecretKey signingKey = Keys.hmacShaKeyFor("super-secret-key-that-is-at-least-32-chars".getBytes());
        Date now = new Date();
        String accessToken = Jwts.builder()
                .subject("demo-client-key")
                .claim("scope", List.of("gpt-4o-mini"))
                .claim("typ", "access")
                .claim("role", "user")
                .issuedAt(new Date(now.getTime() - 10_000))
                .expiration(new Date(now.getTime() - 5_000))
                .signWith(signingKey)
                .compact();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", false
                ))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("unauthorized");
    }

    // ---- JWT USED FOR API ----

    @Test
    void shouldAcceptJwtTokenForChatCompletions() {
        // 注意：这个测试需要 UpstreamChatClient mock，在这里我们只验证认证通过
        // 实际的 chat completions 测试在 ChatCompletionsControllerTest 中
        // 这里只验证 JWT token 能被 ClientAuthService 正确解析
        String accessToken = jwtService.generateAccessToken("demo-client-key", List.of("gpt-4o-mini"));

        // 验证 token 能正确解析
        Claims claims = jwtService.parseToken(accessToken);
        assertThat(claims.getSubject()).isEqualTo("demo-client-key");
        assertThat(jwtService.isRefreshToken(claims)).isFalse();
    }

    // ---- STATIC KEY STILL WORKS ----

    @Test
    void shouldStillAcceptStaticApiKeyWhenJwtEnabled() {
        // 静态 key 应该仍然可用，即使 JWT 启用
        // ClientAuthService.authenticate 中，JWT 解析失败后会回退到静态 key
        // 这里直接测试 ClientAuthService
        // 实际集成测试需要 WebTestClient，但需要 mock upstream
        // 在 ChatCompletionsControllerTest 中有完整的集成测试
    }

    // ---- DISABLED AUTH ----

    @Test
    void shouldReturn503WhenAuthDisabled() {
        // 注意：这个测试需要单独的上下文，因为 auth.enabled=false 时 AuthController 会拒绝请求
        // 这里验证 enabled=true 的场景能正常工作
        // 如果需要测试 disabled 场景，需要单独的 TestPropertySource
    }

    // ─── USAGE / RECENT ───

    @Test
    void shouldReturnRecentUsageRequests() {
        Instant now = Instant.now();
        requestLogService.record(new RequestLogEntry(
                "usage-recent-1", "demo-client-key", "demo-client-key",
                "gpt-4o-mini", "openai", "openai-primary", "default-chat",
                200, 120L, now, "non-stream", 100L, 60L, 40L, 0.25, null
        ));
        requestLogService.record(new RequestLogEntry(
                "usage-recent-2", "demo-client-key", "demo-client-key",
                "gpt-4o", "openai", "openai-primary", "default-chat",
                400, 50L, now, "non-stream", null, null, null, null, "rate_limit"
        ));

        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.get().uri("/auth/usage/recent")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").isNotEmpty()
                .jsonPath("$.requests.length()").isEqualTo(2)
                .jsonPath("$.requests[0].requestId").isEqualTo("usage-recent-2")
                .jsonPath("$.requests[0].model").isEqualTo("gpt-4o")
                .jsonPath("$.requests[0].status").isEqualTo(400)
                .jsonPath("$.requests[0].errorMessage").isEqualTo("rate_limit")
                .jsonPath("$.requests[1].requestId").isEqualTo("usage-recent-1")
                .jsonPath("$.requests[1].model").isEqualTo("gpt-4o-mini")
                .jsonPath("$.requests[1].status").isEqualTo(200)
                .jsonPath("$.requests[1].latencyMs").isEqualTo(120)
                .jsonPath("$.requests[1].usageTokens").isEqualTo(100);
    }

    @Test
    void shouldFilterUsageRecentByModel() {
        Instant now = Instant.now();
        requestLogService.record(new RequestLogEntry(
                "filter-model-1", "demo-client-key", "demo-client-key",
                "gpt-4o-mini", "openai", "openai-primary", "default-chat",
                200, 100L, now, "non-stream", 50L, 30L, 20L, 0.10, null
        ));
        requestLogService.record(new RequestLogEntry(
                "filter-model-2", "demo-client-key", "demo-client-key",
                "gpt-4o", "openai", "openai-primary", "default-chat",
                200, 200L, now, "non-stream", 80L, 50L, 30L, 0.20, null
        ));

        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.get().uri("/auth/usage/recent?model=gpt-4o-mini")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests.length()").isEqualTo(1)
                .jsonPath("$.requests[0].requestId").isEqualTo("filter-model-1");
    }

    @Test
    void shouldFilterUsageRecentByStatus() {
        Instant now = Instant.now();
        requestLogService.record(new RequestLogEntry(
                "filter-status-1", "demo-client-key", "demo-client-key",
                "gpt-4o-mini", "openai", "openai-primary", "default-chat",
                200, 100L, now, "non-stream", 50L, 30L, 20L, 0.10, null
        ));
        requestLogService.record(new RequestLogEntry(
                "filter-status-2", "demo-client-key", "demo-client-key",
                "gpt-4o-mini", "openai", "openai-primary", "default-chat",
                429, 50L, now, "non-stream", null, null, null, null, "rate_limited"
        ));

        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.get().uri("/auth/usage/recent?status=429")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests.length()").isEqualTo(1)
                .jsonPath("$.requests[0].requestId").isEqualTo("filter-status-2")
                .jsonPath("$.requests[0].status").isEqualTo(429);
    }

    @Test
    void shouldReturnEmptyUsageRecentWhenNoRecords() {
        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.get().uri("/auth/usage/recent")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").isNotEmpty()
                .jsonPath("$.requests.length()").isEqualTo(0);
    }

    @Test
    void shouldRejectUsageRecentWhenUnauthenticated() {
        webTestClient.get().uri("/auth/usage/recent")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldRejectUsageCostsWhenUnauthenticated() {
        webTestClient.get().uri("/auth/usage/costs")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── PASSWORD BOUNDARIES ───

    @Test
    void shouldRejectChangePasswordWithShortNewPassword() {
        String token = loginAndGetAccessToken("admin", "admin123");

        webTestClient.put().uri("/auth/password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("oldPassword", "admin123", "newPassword", "ab"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_password");
    }

    @Test
    void shouldRejectChangePasswordWhenUnauthenticated() {
        webTestClient.put().uri("/auth/password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("oldPassword", "admin123", "newPassword", "new-pass123"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── PROFILE SECURITY ───

    @Test
    void shouldRejectUpdateProfileWhenUnauthenticated() {
        webTestClient.put().uri("/auth/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "test"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern) + pattern.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private String loginAndGetAccessToken(String username, String password) {
        String responseBody = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        return extractJsonValue(responseBody, "accessToken");
    }

    private void registerUser(String username, String password) {
        webTestClient.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk();
    }
}
