package io.gateway.oss.admin.integration;

import io.gateway.oss.core.upstream.UpstreamChatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Auth disabled 模式集成测试（独立上下文）。
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles({"test", "test-redis"})
@TestPropertySource(properties = {
        "gateway.auth.enabled=false",
        "gateway.clients.demo-client-key.enabled=true",
        "gateway.clients.demo-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.demo-client-key.defaults.temperature=0.7",
        "gateway.clients.demo-client-key.defaults.max-tokens=256",
        "gateway.clients.demo-client-key.capabilities.streaming=true",
        "gateway.clients.demo-client-key.limits.max-tokens=512",
        "gateway.providers.openai.base-url=http://localhost:18080",
        "gateway.providers.openai.api-key=test-upstream-key",
        "gateway.routes.gpt-4o-mini.scene=default-chat",
        "gateway.routes.openai-primary.provider=openai",
        "gateway.routes.openai-primary.upstream-model=gpt-4o-mini",
        "gateway.scenes.default-chat.primary-route=openai-primary",
        "gateway.pricing.default.unit-price=0.0001",
        "gateway.security.block-internal-urls=false"
})
class AuthDisabledIT extends RedisIntegrationTestSupport {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private UpstreamChatClient upstreamChatClient;

    @BeforeEach
    void setUp() {
        super.resetRedisBackedState();
        reset(upstreamChatClient);
    }

    @Test
    void authDisabled_loginEndpointDisabledButStaticKeyStillValid() {
        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("auth_disabled");

        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "chatcmpl_noauth", "object", "chat.completion")));

        webTestClient.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void authDisabled_chatSucceedsWithValidStaticApiKey() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "chatcmpl_arbitrary", "object", "chat.completion")));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl_arbitrary");
    }
}
