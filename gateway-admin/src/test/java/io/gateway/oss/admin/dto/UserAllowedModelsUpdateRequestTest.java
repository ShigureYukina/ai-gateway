package io.gateway.oss.admin.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserAllowedModelsUpdateRequestTest {

    @Test
    void constructorShouldSetAllowedModels() {
        List<String> allowedModels = List.of("gpt-4o-mini", "claude-3-5-sonnet");
        UserAllowedModelsUpdateRequest request = new UserAllowedModelsUpdateRequest(allowedModels);

        assertEquals(allowedModels, request.allowedModels());
    }

    @Test
    void allowedModelsCanBeNull() {
        UserAllowedModelsUpdateRequest request = new UserAllowedModelsUpdateRequest(null);

        assertNull(request.allowedModels());
    }
}
