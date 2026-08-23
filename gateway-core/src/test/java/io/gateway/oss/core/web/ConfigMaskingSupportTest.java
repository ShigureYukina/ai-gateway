package io.gateway.oss.core.web;

import io.gateway.oss.core.web.support.ConfigMaskingSupport;

import io.gateway.oss.core.config.DynamicConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigMaskingSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ConfigMaskingSupport support;

    @BeforeEach
    void setUp() {
        support = new ConfigMaskingSupport(objectMapper);
    }

    @Test
    void maskSensitiveJson_forProviderConfig_masksApiKeyAndKeys() throws Exception {
        String json = """
                {"apiKey":"provider-secret-5678","keys":["alpha-1111","beta-2222"],"name":"demo"}
                """;

        String masked = support.maskSensitiveJson(json, DynamicConfigService.TYPE_PROVIDERS);
        Map<String, Object> map = objectMapper.readValue(masked, new TypeReference<>() {
        });

        assertEquals("****5678", map.get("apiKey"));
        assertEquals(List.of("****1111", "****2222"), map.get("keys"));
        assertEquals("demo", map.get("name"));
    }

    @Test
    void maskSensitiveJson_forClientConfig_masksOnlyApiKey() throws Exception {
        String json = """
                {"apiKey":"client-secret-9876","description":"kept"}
                """;

        String masked = support.maskSensitiveJson(json, DynamicConfigService.TYPE_CLIENTS);
        Map<String, Object> map = objectMapper.readValue(masked, new TypeReference<>() {
        });

        assertEquals("****9876", map.get("apiKey"));
        assertEquals("kept", map.get("description"));
    }

    @Test
    void maskSensitiveJson_invalidJson_returnsFallbackMask() {
        assertEquals("***", support.maskSensitiveJson("{bad json", DynamicConfigService.TYPE_PROVIDERS));
    }

    @Test
    void maskAndConfigKeyHelpers_followExpectedRules() {
        assertEquals("****3456", support.mask("client-123456"));
        assertEquals("****", support.mask("abcd"));
        assertNull(support.mask("   "));
        assertEquals(List.of("****1111", "****2222"), support.maskKeys(List.of("key-1111", "key-2222")));
        assertEquals(List.of(), support.maskKeys(null));
        assertEquals("****5678", support.maskConfigKey(DynamicConfigService.TYPE_CLIENTS, "client-5678"));
        assertEquals("provider-a", support.maskConfigKey(DynamicConfigService.TYPE_PROVIDERS, "provider-a"));
    }
}
