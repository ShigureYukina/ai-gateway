package io.gateway.oss.admin.sync;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelMetadataServiceTest {

    private final ModelMetadataService service = new ModelMetadataService();

    @Test
    void replaceSnapshot_shouldStoreMetadataCorrectly() {
        Instant now = Instant.parse("2026-06-04T10:15:30Z");
        Map<String, Map<String, Object>> metadata = new LinkedHashMap<>();
        metadata.put(" model-a ", Map.of("context_length", 8192, "max_tokens", 4096));

        service.replaceSnapshot(metadata, now);

        ModelMetadataService.MetadataSnapshot snapshot = service.getSnapshot();
        assertEquals(now, snapshot.updatedAt());
        assertEquals(1, snapshot.modelMetadata().size());
        assertEquals(8192, snapshot.modelMetadata().get("model-a").get("context_length"));
    }

    @Test
    void getMetadata_shouldReturnMetadataForExistingModel() {
        service.replaceSnapshot(Map.of("model-a", Map.of("max_tokens", 4096)), Instant.now());

        Map<String, Object> metadata = service.getMetadata("model-a");

        assertEquals(4096, metadata.get("max_tokens"));
    }

    @Test
    void getMetadata_shouldReturnEmptyMapForUnknownModel() {
        service.replaceSnapshot(Map.of("model-a", Map.of("max_tokens", 4096)), Instant.now());

        assertTrue(service.getMetadata("unknown").isEmpty());
    }

    @Test
    void getContextLength_shouldExtractContextLengthFromMetadata() {
        service.replaceSnapshot(Map.of("model-a", Map.of("context_length", 16384)), Instant.now());

        assertEquals(16384, service.getContextLength("model-a"));
    }

    @Test
    void getMaxTokens_shouldExtractMaxTokensFromMetadata() {
        service.replaceSnapshot(Map.of("model-a", Map.of("max_tokens", 8192)), Instant.now());

        assertEquals(8192, service.getMaxTokens("model-a"));
    }

    @Test
    void getOutputPrice_shouldExtractOutputPriceFromMetadata() {
        service.replaceSnapshot(Map.of("model-a", Map.of("output_price", new BigDecimal("1.25"))), Instant.now());

        assertEquals(new BigDecimal("1.25"), service.getOutputPrice("model-a"));
    }

    @Test
    void resetForTests_shouldClearAllState() {
        service.replaceSnapshot(Map.of("model-a", Map.of("max_tokens", 4096)), Instant.now());

        service.resetForTests();

        assertTrue(service.getSnapshot().modelMetadata().isEmpty());
        assertEquals(Instant.EPOCH, service.getSnapshot().updatedAt());
        assertEquals(0L, service.getSnapshot().version());
        assertTrue(service.getMetadata("model-a").isEmpty());
        assertEquals(0, service.getContextLength("model-a"));
        assertEquals(0, service.getMaxTokens("model-a"));
        assertNull(service.getOutputPrice("model-a"));
    }

    @Test
    void versionCounter_shouldIncrementOnEachReplaceSnapshot() {
        service.replaceSnapshot(Map.of("model-a", Map.of("max_tokens", 4096)), Instant.now());
        assertEquals(1L, service.getSnapshot().version());

        service.replaceSnapshot(Map.of("model-b", Map.of("max_tokens", 8192)), Instant.now());
        assertEquals(2L, service.getSnapshot().version());
    }
}
