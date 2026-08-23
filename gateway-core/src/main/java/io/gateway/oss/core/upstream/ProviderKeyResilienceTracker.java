package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.ResilienceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.StampedLock;

@Component
public class ProviderKeyResilienceTracker {

    private final GatewayProperties properties;
    private final Clock clock;
    private final Map<String, KeyHealthState> stateByKeySlot = new ConcurrentHashMap<>();

    @Autowired
    public ProviderKeyResilienceTracker(GatewayProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ProviderKeyResilienceTracker(GatewayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isAvailable(String keySlotId) {
        return state(keySlotId).isAvailable(now());
    }

    public void recordSuccess(String keySlotId) {
        state(keySlotId).recordSuccess(now());
    }

    public void recordRetryableFailure(String keySlotId) {
        state(keySlotId).recordRetryableFailure(now(), properties.getResilience());
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private KeyHealthState state(String keySlotId) {
        if (stateByKeySlot.size() > 100) {
            cleanupStaleEntries(Duration.ofHours(1));
        }
        return stateByKeySlot.computeIfAbsent(keySlotId, ignored -> new KeyHealthState());
    }

    void cleanupStaleEntries(Duration maxIdle) {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(maxIdle);
        stateByKeySlot.entrySet().removeIf(entry -> {
            long stamp = entry.getValue().lock.tryOptimisticRead();
            boolean stale = entry.getValue().lastAccessedAt.isBefore(cutoff)
                    && entry.getValue().openUntil == null;
            if (!entry.getValue().lock.validate(stamp)) {
                stamp = entry.getValue().lock.readLock();
                try {
                    stale = entry.getValue().lastAccessedAt.isBefore(cutoff)
                            && entry.getValue().isAvailableUnlocked(now);
                } finally {
                    entry.getValue().lock.unlockRead(stamp);
                }
            }
            return stale;
        });
    }

    private static final class KeyHealthState {
        private final StampedLock lock = new StampedLock();
        private final ArrayDeque<Instant> recentRetryableFailures = new ArrayDeque<>();
        private Instant openUntil;
        volatile Instant lastAccessedAt = Instant.EPOCH;

        boolean isAvailable(Instant now) {
            long stamp = lock.tryOptimisticRead();
            Instant open = openUntil;
            Instant last = lastAccessedAt;
            if (lock.validate(stamp)) {
                lastAccessedAt = now;
                if (open == null) return true;
                if (now.isBefore(open)) return false;
                stamp = lock.writeLock();
                try {
                    openUntil = null;
                    return true;
                } finally {
                    lock.unlockWrite(stamp);
                }
            }
            stamp = lock.writeLock();
            try {
                lastAccessedAt = now;
                return isAvailableUnlocked(now);
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        private boolean isAvailableUnlocked(Instant now) {
            if (openUntil == null) return true;
            if (now.isBefore(openUntil)) return false;
            openUntil = null;
            return true;
        }

        void recordSuccess(Instant now) {
            long stamp = lock.writeLock();
            try {
                lastAccessedAt = now;
                recentRetryableFailures.clear();
                openUntil = null;
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        void recordRetryableFailure(Instant now, ResilienceConfig config) {
            long stamp = lock.writeLock();
            try {
                lastAccessedAt = now;
                pruneExpired(now, config);
                recentRetryableFailures.addLast(now);
                if (recentRetryableFailures.size() >= config.getRetryableFailureThreshold()) {
                    openUntil = now.plus(config.getOpenDuration());
                }
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        private void pruneExpired(Instant now, ResilienceConfig config) {
            Instant cutoff = now.minus(config.getFailureWindow());
            while (!recentRetryableFailures.isEmpty() && recentRetryableFailures.peekFirst().isBefore(cutoff)) {
                recentRetryableFailures.removeFirst();
            }
        }
    }
}
