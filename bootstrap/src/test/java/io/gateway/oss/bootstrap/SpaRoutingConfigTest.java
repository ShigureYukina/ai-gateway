package io.gateway.oss.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.reactive.config.EnableWebFlux;

/**
 * 使用真实 WebFlux ApplicationContext 验证 SPA fallback：
 * 只有当前置 HandlerMapping 均未命中时，才由最低优先级的兜底映射返回 index.html。
 */
@SpringJUnitConfig(classes = SpaRoutingConfigTest.TestApplication.class)
class SpaRoutingConfigTest {

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext).build();
    }

    @Test
    void shouldServeIndexForUnknownSpaRoute() {
        webTestClient.get().uri("/some-spa-route")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(body -> org.junit.jupiter.api.Assertions.assertTrue(body.contains("Test SPA")));
    }

    @Test
    void shouldServeIndexForRoot() {
        webTestClient.get().uri("/")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML);
    }

    @Test
    void shouldKeepApiPrefixesOutOfSpaFallback() {
        webTestClient.get().uri("/v1/nonexistent")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.get().uri("/admin/nonexistent")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.get().uri("/healthz")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldNotFallbackWithoutHtmlAccept() {
        webTestClient.get().uri("/some-spa-route")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldNotFallbackForNonGetRequest() {
        webTestClient.post().uri("/some-spa-route")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldKeepMatchedController404() {
        webTestClient.get().uri("/matched-404")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Configuration
    @EnableWebFlux
    @Import({SpaRoutingConfig.class, TestController.class})
    static class TestApplication {
    }

    @Controller
    static class TestController {

        @GetMapping("/matched-404")
        ResponseEntity<Void> matched404() {
            return ResponseEntity.notFound().build();
        }
    }
}
