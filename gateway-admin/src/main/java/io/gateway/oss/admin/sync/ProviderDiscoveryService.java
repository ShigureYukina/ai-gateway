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
public class ProviderDiscoveryService {

    private final AtomicLong versionCounter = new AtomicLong();
    private final AtomicReference<DiscoverySnapshot> snapshotRef = new AtomicReference<>(DiscoverySnapshot.empty());

    public DiscoverySnapshot getSnapshot() {
        return snapshotRef.get();
    }

    public void updateProvider(String provider, ProviderDiscovery providerDiscovery) {
        DiscoverySnapshot current = snapshotRef.get();
        Map<String, ProviderDiscovery> copy = new LinkedHashMap<>(current.providers());
        copy.put(provider, normalize(providerDiscovery));
        snapshotRef.set(new DiscoverySnapshot(Map.copyOf(copy), Instant.now(), versionCounter.incrementAndGet()));
    }

    public ProviderDiscovery getProvider(String provider) {
        return snapshotRef.get().providers().getOrDefault(provider, ProviderDiscovery.never());
    }

    public void resetForTests() {
        versionCounter.set(0);
        snapshotRef.set(DiscoverySnapshot.empty());
    }

    private ProviderDiscovery normalize(ProviderDiscovery discovery) {
        if (discovery == null) {
            return ProviderDiscovery.never();
        }
        Set<String> unique = new LinkedHashSet<>();
        if (discovery.models() != null) {
            for (String model : discovery.models()) {
                if (model != null && !model.isBlank()) {
                    unique.add(model.trim());
                }
            }
        }
        List<String> models = new ArrayList<>(unique);
        Collections.sort(models);
        return new ProviderDiscovery(discovery.source(), discovery.status(), discovery.lastFetchedAt(), List.copyOf(models), discovery.error());
    }

    public record DiscoverySnapshot(
            Map<String, ProviderDiscovery> providers,
            Instant updatedAt,
            long version
    ) {
        public static DiscoverySnapshot empty() {
            return new DiscoverySnapshot(Map.of(), Instant.EPOCH, 0L);
        }
    }

    public record ProviderDiscovery(
            String source,
            String status,
            Instant lastFetchedAt,
            List<String> models,
            String error
    ) {
        public static ProviderDiscovery never() {
            return new ProviderDiscovery("provider-active-discovery", "never", null, List.of(), null);
        }
    }
}
