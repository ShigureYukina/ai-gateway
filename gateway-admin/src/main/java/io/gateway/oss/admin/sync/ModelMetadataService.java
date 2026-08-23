package io.gateway.oss.admin.sync;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ModelMetadataService {

    private final AtomicLong versionCounter = new AtomicLong(0L);
    private final AtomicReference<MetadataSnapshot> snapshotRef =
            new AtomicReference<>(MetadataSnapshot.empty());

    public MetadataSnapshot getSnapshot() {
        return snapshotRef.get();
    }

    public Map<String, Object> getMetadata(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return Map.of();
        }
        return snapshotRef.get().modelMetadata().getOrDefault(modelId, Map.of());
    }

    public int getContextLength(String modelId) {
        Map<String, Object> metadata = getMetadata(modelId);
        Object cl = metadata.get("context_length");
        if (cl instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    public int getMaxTokens(String modelId) {
        Map<String, Object> metadata = getMetadata(modelId);
        Object mt = metadata.get("max_tokens");
        if (mt instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    public BigDecimal getOutputPrice(String modelId) {
        Map<String, Object> metadata = getMetadata(modelId);
        Object op = metadata.get("output_price");
        if (op instanceof BigDecimal bd) {
            return bd;
        }
        if (op instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return null;
    }

    public void replaceSnapshot(Map<String, Map<String, Object>> modelMetadata, Instant now) {
        Map<String, Map<String, Object>> normalized = new LinkedHashMap<>();
        if (modelMetadata != null) {
            for (Map.Entry<String, Map<String, Object>> entry : modelMetadata.entrySet()) {
                String model = normalizeKey(entry.getKey());
                if (model == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                normalized.put(model, Map.copyOf(entry.getValue()));
            }
        }

        snapshotRef.set(new MetadataSnapshot(
                Map.copyOf(normalized),
                now == null ? Instant.now() : now,
                versionCounter.incrementAndGet()
        ));
    }

    public void resetForTests() {
        versionCounter.set(0L);
        snapshotRef.set(MetadataSnapshot.empty());
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record MetadataSnapshot(
            Map<String, Map<String, Object>> modelMetadata,
            Instant updatedAt,
            long version
    ) {
        public static MetadataSnapshot empty() {
            return new MetadataSnapshot(Map.of(), Instant.EPOCH, 0L);
        }
    }
}
