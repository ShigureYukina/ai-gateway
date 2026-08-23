package io.gateway.oss.admin.config.audit;

import io.gateway.oss.core.config.InMemoryConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigVersionServiceTest {

    private InMemoryConfigStore configStore;
    private ObjectMapper objectMapper;
    private ConfigVersionService service;

    @BeforeEach
    void setUp() {
        configStore = new InMemoryConfigStore();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new ConfigVersionService(configStore, objectMapper);
    }

    // ─── snapshot 后 getVersions 能查到 ───

    @Test
    void shouldSnapshotBeforeChange() {
        String json = "{\"type\":\"openai-compatible\",\"baseUrl\":\"http://old\"}";

        service.snapshotBeforeChange("providers", "openai", json, "admin").block();

        StepVerifier.create(service.getVersions("providers", "openai"))
                .assertNext(versions -> {
                    assertThat(versions).hasSize(1);
                    ConfigVersionService.ConfigVersion v = versions.get(0);
                    assertThat(v.configType()).isEqualTo("providers");
                    assertThat(v.configKey()).isEqualTo("openai");
                    assertThat(v.versionNumber()).isEqualTo(1);
                    assertThat(v.jsonValue()).isEqualTo(json);
                    assertThat(v.operator()).isEqualTo("admin");
                    assertThat(v.versionId()).isNotBlank();
                    assertThat(v.createdAt()).isNotNull();
                })
                .verifyComplete();
    }

    // ─── 多次 snapshot 版本号自增 ───

    @Test
    void shouldIncrementVersionNumber() {
        service.snapshotBeforeChange("providers", "openai", "{\"v\":1}", "admin").block();
        service.snapshotBeforeChange("providers", "openai", "{\"v\":2}", "admin").block();
        service.snapshotBeforeChange("providers", "openai", "{\"v\":3}", "admin").block();

        StepVerifier.create(service.getVersions("providers", "openai"))
                .assertNext(versions -> {
                    assertThat(versions).hasSize(3);
                    assertThat(versions.get(0).versionNumber()).isEqualTo(1);
                    assertThat(versions.get(1).versionNumber()).isEqualTo(2);
                    assertThat(versions.get(2).versionNumber()).isEqualTo(3);
                    assertThat(versions.get(0).jsonValue()).isEqualTo("{\"v\":1}");
                    assertThat(versions.get(1).jsonValue()).isEqualTo("{\"v\":2}");
                    assertThat(versions.get(2).jsonValue()).isEqualTo("{\"v\":3}");
                })
                .verifyComplete();
    }

    // ─── rollbackTo 返回正确的 JSON ───

    @Test
    void shouldRollbackToVersion() {
        service.snapshotBeforeChange("providers", "openai", "{\"v\":1}", "admin").block();
        service.snapshotBeforeChange("providers", "openai", "{\"v\":2}", "admin").block();

        StepVerifier.create(service.rollbackTo("providers", "openai", 1))
                .assertNext(json -> assertThat(json).isEqualTo("{\"v\":1}"))
                .verifyComplete();

        StepVerifier.create(service.rollbackTo("providers", "openai", 2))
                .assertNext(json -> assertThat(json).isEqualTo("{\"v\":2}"))
                .verifyComplete();
    }

    // ─── 不存在的版本返回 null（Mono empty） ───

    @Test
    void shouldReturnNullForNonExistentVersion() {
        // 无快照时查询
        StepVerifier.create(service.getVersion("providers", "nonexistent", 99))
                .verifyComplete();

        // 有其他快照但查询不存在的版本号
        service.snapshotBeforeChange("providers", "openai", "{\"v\":1}", "admin").block();

        StepVerifier.create(service.getVersion("providers", "openai", 99))
                .verifyComplete();
    }

    // ─── 从 ConfigStore 恢复计数器 ───

    @Test
    void shouldRestoreCounterOnStartup() {
        // 模拟持久化存储中已有版本数据
        ConfigVersionService.ConfigVersion v1 = new ConfigVersionService.ConfigVersion(
                "id-1", "providers", "openai", 1, "{\"v\":1}", java.time.Instant.now(), "admin");
        ConfigVersionService.ConfigVersion v2 = new ConfigVersionService.ConfigVersion(
                "id-2", "providers", "openai", 2, "{\"v\":2}", java.time.Instant.now(), "admin");
        ConfigVersionService.ConfigVersion v3 = new ConfigVersionService.ConfigVersion(
                "id-3", "providers", "openai", 3, "{\"v\":3}", java.time.Instant.now(), "admin");

        try {
            configStore.save("config-versions", "providers:openai:v1", objectMapper.writeValueAsString(v1)).block();
            configStore.save("config-versions", "providers:openai:v2", objectMapper.writeValueAsString(v2)).block();
            configStore.save("config-versions", "providers:openai:v3", objectMapper.writeValueAsString(v3)).block();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 创建新 service 实例，触发 @PostConstruct
        ConfigVersionService restoredService = new ConfigVersionService(configStore, objectMapper);
        restoredService.init();

        // 新的 snapshot 应该从 v4 开始（计数器从 v3 恢复）
        restoredService.snapshotBeforeChange("providers", "openai", "{\"v\":4}", "admin").block();

        StepVerifier.create(restoredService.getVersions("providers", "openai"))
                .assertNext(versions -> {
                    assertThat(versions).hasSize(4);
                    assertThat(versions.get(3).versionNumber()).isEqualTo(4);
                    assertThat(versions.get(3).jsonValue()).isEqualTo("{\"v\":4}");
                })
                .verifyComplete();
    }

    // ─── 空存储返回空列表 ───

    @Test
    void shouldReturnEmptyVersionsWhenNoSnapshots() {
        StepVerifier.create(service.getVersions("providers", "openai"))
                .assertNext(versions -> assertThat(versions).isEmpty())
                .verifyComplete();
    }
}
