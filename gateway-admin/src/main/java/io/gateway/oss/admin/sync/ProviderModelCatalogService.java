package io.gateway.oss.admin.sync;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ProviderModelCatalogService {

    private final AtomicLong versionCounter = new AtomicLong(0L);
    private final AtomicReference<CatalogSnapshot> snapshotRef =
            new AtomicReference<>(CatalogSnapshot.empty());

    public CatalogSnapshot getSnapshot() {
        return snapshotRef.get();
    }

    /**
     * Resets all internal state to initial values. For use by test cleanup only.
     */
    public void resetForTests() {
        versionCounter.set(0L);
        snapshotRef.set(CatalogSnapshot.empty());
    }

    public List<String> getModels(String provider) {
        if (provider == null || provider.isBlank()) {
            return List.of();
        }
        return snapshotRef.get().providerModels().getOrDefault(provider, List.of());
    }

    public boolean hasModel(String provider, String model) {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            return false;
        }
        List<String> models = snapshotRef.get().providerModels().get(provider);
        return models != null && models.contains(model);
    }

    public void replaceSnapshot(Map<String, Set<String>> providerModels, Instant now) {
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        if (providerModels != null) {
            for (Map.Entry<String, Set<String>> entry : providerModels.entrySet()) {
                String provider = normalizeKey(entry.getKey());
                if (provider == null) {
                    continue;
                }
                Set<String> uniqueModels = new LinkedHashSet<>();
                if (entry.getValue() != null) {
                    for (String model : entry.getValue()) {
                        String normalizedModel = normalizeKey(model);
                        if (normalizedModel != null) {
                            uniqueModels.add(normalizedModel);
                        }
                    }
                }
                List<String> models = new ArrayList<>(uniqueModels);
                Collections.sort(models);
                normalized.put(provider, List.copyOf(models));
            }
        }

        snapshotRef.set(new CatalogSnapshot(
                Map.copyOf(normalized),
                now == null ? Instant.now() : now,
                versionCounter.incrementAndGet()
        ));
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record CatalogSnapshot(
            Map<String, List<String>> providerModels,
            Instant updatedAt,
            long version
    ) {
        public static CatalogSnapshot empty() {
            return new CatalogSnapshot(Map.of(), Instant.EPOCH, 0L);
        }
    }
}
