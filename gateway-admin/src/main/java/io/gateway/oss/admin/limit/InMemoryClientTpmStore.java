package io.gateway.oss.admin.limit;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryClientTpmStore implements ClientTpmStore {

    private final Map<String, AtomicLong> usage = new ConcurrentHashMap<>();

    @Override
    public long currentMinuteUsage(String clientId, Instant now) {
        AtomicLong counter = usage.get(minuteKey(clientId, now));
        return counter == null ? 0L : counter.get();
    }

    @Override
    public long reserve(String clientId, long tokens, long tpmLimit, Instant now) {
        if (tokens <= 0) {
            return currentMinuteUsage(clientId, now);
        }
        AtomicLong counter = usage.computeIfAbsent(minuteKey(clientId, now), ignored -> new AtomicLong(0L));
        // Lock-free CAS loop: read current, check limit, attempt add
        while (true) {
            long used = counter.get();
            if (used + tokens > tpmLimit) {
                return -1L;
            }
            if (counter.compareAndSet(used, used + tokens)) {
                return used + tokens;
            }
        }
    }

    @Override
    public void adjust(String clientId, long deltaTokens, Instant now) {
        if (deltaTokens == 0) {
            return;
        }
        AtomicLong counter = usage.computeIfAbsent(minuteKey(clientId, now), ignored -> new AtomicLong(0L));
        counter.updateAndGet(current -> Math.max(0L, current + deltaTokens));
    }

    private String minuteKey(String clientId, Instant now) {
        LocalDateTime minute = LocalDateTime.ofInstant(now, ZoneOffset.UTC).withSecond(0).withNano(0);
        return clientId + ":" + minute.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
    }

    public void resetForTests() {
        usage.clear();
    }
}
