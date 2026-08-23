package io.gateway.oss.admin.web;

import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.observability.RequestLogService.RequestLogEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class ConfigAuditControllerTest extends AdminNoFlywayWebIntegrationTestBase {

    @Autowired
    private RequestLogService requestLogService;

    // ─── GET /internal/config/audit ───

    // ─── GET /internal/config/versions/{configType}/{configKey} ───

    // ─── 鉴权：非 admin 返回 403 ───

    @Test
    void shouldReturn403ForUserTokenOnAudit() {
        webTestClient.get()
                .uri("/internal/config/audit")
                .header("Authorization", "Bearer " + userAccessToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("forbidden");
    }



    // ─── 鉴权：无 token 返回 401 ───

    @Test
    void shouldReturn401WhenAuthMissingOnAudit() {
        webTestClient.get()
                .uri("/internal/config/audit")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("unauthorized");
    }



    // ─── 审计查询参数过滤 ───

    // ─── rollback 版本不存在返回 404 ───

    @Test
    void shouldReturn404WhenRollbackVersionNotFound() {
        webTestClient.post()
                .uri("/internal/config/rollback/providers/nonexistent/999")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("version_not_found");
    }

    // ─── GET /internal/config/snapshot ───

    @Test
    void shouldReturnUnifiedAuditCenterAggregatingConfigAuditAndRequestLogs() {
        webTestClient.put()
                .uri("/admin/providers/openai")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "type", "openai-compatible",
                        "baseUrl", "http://203.0.113.20:9000",
                        "apiKey", "updated-key-for-audit"
                ))
                .exchange()
                .expectStatus().isOk();

        requestLogService.record(new RequestLogEntry(
                "req-audit-center-1",
                "dem***ey",
                "demo-client-key",
                "gpt-4o-mini",
                "openai",
                "openai-primary",
                "default-chat",
                200,
                45,
                Instant.parse("2026-01-01T00:00:00Z"),
                "non-streaming",
                12L,
                7L,
                5L,
                0.0012,
                null
        ));

        webTestClient.get()
                .uri("/internal/config/audit-center")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.entries[?(@.eventType=='config_audit')]").isNotEmpty()
                .jsonPath("$.entries[?(@.eventType=='config_audit' && @.resourceType=='providers' && @.action=='save' && @.result=='success')]").isNotEmpty()
                .jsonPath("$.entries[?(@.eventType=='request_log')]").isNotEmpty()
                .jsonPath("$.entries[?(@.eventType=='request_log' && @.requestId=='req-audit-center-1' && @.model=='gpt-4o-mini' && @.provider=='openai' && @.routeId=='openai-primary' && @.scene=='default-chat' && @.status==200)]").isNotEmpty();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/config/audit-center")
                        .queryParam("eventType", "request_log")
                        .queryParam("status", 200)
                        .queryParam("clientId", "dem***ey")
                        .build())
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.entries.length()").isEqualTo(1)
                .jsonPath("$.entries[0].eventType").isEqualTo("request_log")
                .jsonPath("$.entries[0].clientId").isEqualTo("dem***ey");

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/config/audit-center")
                        .queryParam("eventType", "config_audit")
                        .queryParam("configType", "providers")
                        .queryParam("operator", "admin")
                        .build())
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.entries.length()").value(length -> {
                    int size = ((Number) length).intValue();
                    if (size < 1) throw new AssertionError("expected config audit entries");
                })
                .jsonPath("$.entries[0].eventType").isEqualTo("config_audit")
                .jsonPath("$.entries[0].resourceType").isEqualTo("providers")
                .jsonPath("$.entries[0].actor").isEqualTo("admin");
    }



    @Test
    void shouldRecordAuditVersionsAndRollbackAfterAdminMutation() {
        AtomicReference<String> originalBaseUrlRef = new AtomicReference<>();
        webTestClient.get()
                .uri("/admin/providers")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers.openai.baseUrl").value(value -> originalBaseUrlRef.set(String.valueOf(value)));

        String originalBaseUrl = originalBaseUrlRef.get();
        String updatedBaseUrl = "http://203.0.113.21:9000";

        webTestClient.put()
                .uri("/admin/providers/openai")
                .header("Authorization", "Bearer " + adminAccessToken)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "type", "openai-compatible",
                        "baseUrl", updatedBaseUrl,
                        "apiKey", "updated-key-for-audit"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.baseUrl").isEqualTo(updatedBaseUrl);

        webTestClient.get()
                .uri("/internal/config/audit")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("\"entries\"")) throw new AssertionError("audit entries missing");
                    if (!body.contains("providers")) throw new AssertionError("providers audit entry missing");
                    if (!body.contains("openai")) throw new AssertionError("openai audit entry missing");
                });

        webTestClient.get()
                .uri("/internal/config/versions/providers/openai")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.versions[0].configType").isEqualTo("providers")
                .jsonPath("$.versions[0].configKey").isEqualTo("openai")
                .jsonPath("$.versions[0].versionNumber").isEqualTo(1)
                .jsonPath("$.versions.length()").value(length -> {
                    int size = ((Number) length).intValue();
                    if (size < 1) throw new AssertionError("expected at least one version after mutation");
                });

        webTestClient.post()
                .uri("/internal/config/rollback/providers/openai/1")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().is2xxSuccessful();

        webTestClient.get()
                .uri("/internal/config/snapshot")
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers.openai.baseUrl").isEqualTo(originalBaseUrl);

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/config/audit")
                        .queryParam("configType", "providers")
                        .queryParam("configKey", "openai")
                        .build())
                .header("Authorization", "Bearer " + adminAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.entries.length()").value(length -> {
                    int size = ((Number) length).intValue();
                    if (size < 1) throw new AssertionError("expected providers/openai audit entries after rollback");
                });
    }

}
