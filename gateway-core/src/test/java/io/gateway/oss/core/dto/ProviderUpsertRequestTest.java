package io.gateway.oss.core.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProviderUpsertRequestTest {

    @Test
    void constructorShouldSetAllFields() {
        List<String> models = List.of("gpt-4o-mini", "gpt-4.1");
        ProviderUpsertRequest request = new ProviderUpsertRequest(
                "openai",
                "https://api.openai.com",
                "sk-test",
                60L,
                true,
                models
        );

        assertEquals("openai", request.type());
        assertEquals("https://api.openai.com", request.baseUrl());
        assertEquals("sk-test", request.apiKey());
        assertEquals(60L, request.timeoutSeconds());
        assertEquals(Boolean.TRUE, request.enabled());
        assertEquals(models, request.models());
    }

    @Test
    void allFieldsCanBeNull() {
        ProviderUpsertRequest request = new ProviderUpsertRequest(null, null, null, null, null, null);

        assertNull(request.type());
        assertNull(request.baseUrl());
        assertNull(request.apiKey());
        assertNull(request.timeoutSeconds());
        assertNull(request.enabled());
        assertNull(request.models());
    }
}
