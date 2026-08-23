package io.gateway.oss.admin.config.audit;

import io.gateway.oss.core.config.InMemoryConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;


import static org.assertj.core.api.Assertions.assertThat;

class ConfigAuditServiceTest {

    private InMemoryConfigStore configStore;
    private ObjectMapper objectMapper;
    private ConfigAuditService service;

    @BeforeEach
    void setUp() {
        configStore = new InMemoryConfigStore();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new ConfigAuditService(configStore, objectMapper);
    }

    // ─── record + getRecent ───

    @Test
    void shouldRecordAuditEntry() {
        service.record("providers", "openai", "save", "admin", null, "{\"type\":\"openai-compatible\"}")
                .block();

        StepVerifier.create(service.getRecent(10))
                .assertNext(entries -> {
                    assertThat(entries).hasSize(1);
                    ConfigAuditService.AuditEntry entry = entries.get(0);
                    assertThat(entry.configType()).isEqualTo("providers");
                    assertThat(entry.configKey()).isEqualTo("openai");
                    assertThat(entry.action()).isEqualTo("save");
                    assertThat(entry.operator()).isEqualTo("admin");
                    assertThat(entry.oldValue()).isNull();
                    assertThat(entry.newValue()).isEqualTo("{\"type\":\"openai-compatible\"}");
                    assertThat(entry.auditId()).isNotBlank();
                    assertThat(entry.timestamp()).isNotNull();
                })
                .verifyComplete();
    }

    // ─── 按 configType 过滤 ───

    @Test
    void shouldQueryByConfigType() {
        service.record("providers", "openai", "save", "admin", null, "v1").block();
        service.record("routes", "route-1", "save", "admin", null, "v2").block();
        service.record("providers", "anthropic", "save", "admin", null, "v3").block();

        StepVerifier.create(service.query("providers", null, null, 100))
                .assertNext(entries -> {
                    assertThat(entries).hasSize(2);
                    assertThat(entries).allMatch(e -> "providers".equals(e.configType()));
                })
                .verifyComplete();
    }

    // ─── 按 operator 过滤 ───

    @Test
    void shouldQueryByOperator() {
        service.record("providers", "openai", "save", "admin", null, "v1").block();
        service.record("providers", "anthropic", "save", "operator-bob", null, "v2").block();
        service.record("routes", "route-1", "save", "admin", null, "v3").block();

        StepVerifier.create(service.query(null, null, "admin", 100))
                .assertNext(entries -> {
                    assertThat(entries).hasSize(2);
                    assertThat(entries).allMatch(e -> "admin".equals(e.operator()));
                })
                .verifyComplete();
    }

    // ─── limit 参数生效 ───

    @Test
    void shouldLimitResults() {
        for (int i = 0; i < 10; i++) {
            service.record("providers", "p" + i, "save", "admin", null, "v" + i).block();
        }

        StepVerifier.create(service.getRecent(3))
                .assertNext(entries -> assertThat(entries).hasSize(3))
                .verifyComplete();
    }

    // ─── 空存储返回空列表 ───

    @Test
    void shouldHandleEmptyStore() {
        StepVerifier.create(service.getRecent(10))
                .assertNext(entries -> assertThat(entries).isEmpty())
                .verifyComplete();

        StepVerifier.create(service.query("providers", null, null, 10))
                .assertNext(entries -> assertThat(entries).isEmpty())
                .verifyComplete();
    }

    // ─── 多条记录按时间倒序 ───

    @Test
    void shouldReturnEntriesInReverseChronologicalOrder() {
        service.record("providers", "first", "save", "admin", null, "v1").block();
        service.record("providers", "second", "save", "admin", null, "v2").block();
        service.record("providers", "third", "save", "admin", null, "v3").block();

        StepVerifier.create(service.getRecent(100))
                .assertNext(entries -> {
                    assertThat(entries).hasSize(3);
                    // 最新的排在前面
                    assertThat(entries.get(0).configKey()).isEqualTo("third");
                    assertThat(entries.get(1).configKey()).isEqualTo("second");
                    assertThat(entries.get(2).configKey()).isEqualTo("first");
                })
                .verifyComplete();
    }
}
