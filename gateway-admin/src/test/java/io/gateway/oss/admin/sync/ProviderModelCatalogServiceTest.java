package io.gateway.oss.admin.sync;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelCatalogServiceTest {

    private final ProviderModelCatalogService service = new ProviderModelCatalogService();

    @Test
    void replaceSnapshot_shouldStoreProviderModelsCorrectly() {
        Instant now = Instant.parse("2026-06-04T10:15:30Z");
        Map<String, Set<String>> providerModels = new LinkedHashMap<>();
        providerModels.put(" openai ", new LinkedHashSet<>(List.of("gpt-4o", "gpt-4o-mini")));

        service.replaceSnapshot(providerModels, now);

        ProviderModelCatalogService.CatalogSnapshot snapshot = service.getSnapshot();
        assertEquals(now, snapshot.updatedAt());
        assertEquals(List.of("gpt-4o", "gpt-4o-mini"), snapshot.providerModels().get("openai"));
    }

    @Test
    void getModels_shouldReturnModelsForExistingProvider() {
        service.replaceSnapshot(Map.of("openai", Set.of("gpt-4o", "gpt-4o-mini")), Instant.now());

        assertEquals(List.of("gpt-4o", "gpt-4o-mini"), service.getModels("openai"));
    }

    @Test
    void getModels_shouldReturnEmptyListForUnknownProvider() {
        service.replaceSnapshot(Map.of("openai", Set.of("gpt-4o")), Instant.now());

        assertTrue(service.getModels("unknown").isEmpty());
    }

    @Test
    void hasModel_shouldReturnTrueForExistingModel() {
        service.replaceSnapshot(Map.of("openai", Set.of("gpt-4o")), Instant.now());

        assertTrue(service.hasModel("openai", "gpt-4o"));
    }

    @Test
    void hasModel_shouldReturnFalseForUnknownModel() {
        service.replaceSnapshot(Map.of("openai", Set.of("gpt-4o")), Instant.now());

        assertFalse(service.hasModel("openai", "gpt-4o-mini"));
    }

    @Test
    void replaceSnapshot_shouldHandleNullAndEmptyValues() {
        Map<String, Set<String>> providerModels = new LinkedHashMap<>();
        providerModels.put("openai", null);
        providerModels.put(" ", Set.of("gpt-4o"));
        providerModels.put("anthropic", Set.of(" "));

        service.replaceSnapshot(providerModels, Instant.now());

        assertEquals(List.of(), service.getModels("openai"));
        assertEquals(List.of(), service.getModels("anthropic"));
        assertFalse(service.getSnapshot().providerModels().containsKey(""));
    }

    @Test
    void replaceSnapshot_shouldNormalizeTrimDeduplicateAndSortModels() {
        Map<String, Set<String>> providerModels = new LinkedHashMap<>();
        providerModels.put(" openai ", new LinkedHashSet<>(List.of(" z-model ", "a-model", "a-model ", " ")));

        service.replaceSnapshot(providerModels, Instant.now());

        assertEquals(List.of("a-model", "z-model"), service.getModels("openai"));
    }

    @Test
    void resetForTests_shouldClearAllState() {
        service.replaceSnapshot(Map.of("openai", Set.of("gpt-4o")), Instant.now());

        service.resetForTests();

        assertTrue(service.getSnapshot().providerModels().isEmpty());
        assertEquals(Instant.EPOCH, service.getSnapshot().updatedAt());
        assertEquals(0L, service.getSnapshot().version());
        assertTrue(service.getModels("openai").isEmpty());
        assertFalse(service.hasModel("openai", "gpt-4o"));
    }

    @Test
    void versionCounter_shouldIncrementOnEachReplaceSnapshot() {
        service.replaceSnapshot(Map.of("openai", Set.of("gpt-4o")), Instant.now());
        assertEquals(1L, service.getSnapshot().version());

        service.replaceSnapshot(Map.of("anthropic", Set.of("claude")), Instant.now());
        assertEquals(2L, service.getSnapshot().version());
    }
}
