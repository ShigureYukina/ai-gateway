package io.gateway.oss.core.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiKeyToggleRequestTest {

    @Test
    void constructorShouldSetEnabledValue() {
        ApiKeyToggleRequest request = new ApiKeyToggleRequest(Boolean.TRUE);

        assertEquals(Boolean.TRUE, request.enabled());
    }

    @Test
    void enabledCanBeNull() {
        ApiKeyToggleRequest request = new ApiKeyToggleRequest(null);

        assertNull(request.enabled());
    }
}
