package io.gateway.oss.core.dto;

import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProviderModelsUpdateRequestTest {

    @Test
    void constructorShouldSetModels() {
        List<String> models = List.of("gpt-4o-mini", "gpt-4.1");
        ProviderModelsUpdateRequest request = new ProviderModelsUpdateRequest(models);

        assertEquals(models, request.models());
    }

    @Test
    void modelsFieldShouldHaveNotNullAnnotation() throws Exception {
        Method modelsMethod = ProviderModelsUpdateRequest.class.getDeclaredMethod("models");

        assertEquals("models", modelsMethod.getName());
        assertNotNull(modelsMethod.getAnnotation(NotNull.class));
    }
}
