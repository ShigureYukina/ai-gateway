package io.gateway.oss.bootstrap;

import io.gateway.oss.core.security.AuthLoginController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("local")
class AdminRoutesAssemblyTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void adminRoutesShouldBeReachableInAssembledApplication() {
        String bearerToken = loginAsAdmin();

        webTestClient.get().uri("/admin/providers")
                .header("Authorization", bearerToken)
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri("/admin/routes")
                .header("Authorization", bearerToken)
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri("/admin/dashboard/overview")
                .header("Authorization", bearerToken)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void adminRoutesShouldBeProtectedInsteadOfMissing() {
        webTestClient.get().uri("/admin/providers")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(404));

        webTestClient.get().uri("/admin/routes")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(404));
    }

    private String loginAsAdmin() {
        AuthLoginController.LoginResponse response = webTestClient.post().uri("/auth/login")
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthLoginController.LoginResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.accessToken()).isNotBlank();
        return "Bearer " + response.accessToken();
    }
}
