package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.ResilienceConfig;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.StampedLock;

public class InMemoryRouteStateStore implements RouteStateStore {

    private final Map<String, RouteHealthState> stateByRouteId = new ConcurrentHashMap<>();

    @Override
    public boolean isAvailable(String routeId, Instant now) {
        return state(routeId).isAvailable(now);
    }

    @Override
    public void recordSuccess(String routeId) {
        state(routeId).recordSuccess();
    }

    @Override
    public void recordRetryableFailure(String routeId, Instant now, ResilienceConfig config) {
        state(routeId).recordRetryableFailure(now, config);
    }

    private RouteHealthState state(String routeId) {
        return stateByRouteId.computeIfAbsent(routeId, ignored -> new RouteHealthState());
    }

    private static final class RouteHealthState {
        private final StampedLock lock = new StampedLock();
        private final ArrayDeque<Instant> recentRetryableFailures = new ArrayDeque<>();
        private Instant openUntil;

        boolean isAvailable(Instant now) {
            long stamp = lock.tryOptimisticRead();
            Instant open = openUntil;
            if (lock.validate(stamp)) {
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
                if (openUntil == null) return true;
                if (now.isBefore(openUntil)) return false;
                openUntil = null;
                return true;
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        void recordSuccess() {
            long stamp = lock.writeLock();
            try {
                recentRetryableFailures.clear();
                openUntil = null;
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        void recordRetryableFailure(Instant now, ResilienceConfig config) {
            long stamp = lock.writeLock();
            try {
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
