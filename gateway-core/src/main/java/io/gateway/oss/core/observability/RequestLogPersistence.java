package io.gateway.oss.core.observability;

import io.gateway.oss.core.config.ConfigStore;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 请求日志持久化与异步写入生命周期协作者。
 */
final class RequestLogPersistence {

    private static final Logger log = LoggerFactory.getLogger(RequestLogPersistence.class);
    private static final String CONFIG_TYPE = "request-logs";

    private final ConfigStore configStore;
    private final RequestLogCodec codec;
    private final int maxEntries;
    private final int maxPersistRetries;
    private final GatewayMetricsRecorder metricsRecorder;
    private final Set<PendingWrite> pendingWrites = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingWriteCount = new AtomicInteger();

    RequestLogPersistence(ConfigStore configStore, RequestLogCodec codec, int maxEntries, int maxPersistRetries) {
        this.configStore = Objects.requireNonNull(configStore);
        this.codec = Objects.requireNonNull(codec);
        this.maxEntries = maxEntries;
        this.maxPersistRetries = maxPersistRetries;
        this.metricsRecorder = new GatewayMetricsRecorder(Metrics.globalRegistry);
    }

    void init(Consumer<RequestLogService.RequestLogEntry> entryConsumer, Runnable onLoaded) {
        configStore.loadAll(CONFIG_TYPE)
                .map(Map::values)
                .flatMapMany(Flux::fromIterable)
                .flatMap(codec::deserialize)
                .sort(Comparator.comparing(RequestLogService.RequestLogEntry::timestamp,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .take(maxEntries)
                .doOnNext(entryConsumer)
                .doOnComplete(onLoaded)
                .doOnError(error -> log.warn("request_logs_load_failed reason={}", error.getMessage()))
                .onErrorResume(error -> Mono.empty())
                .subscribe();
    }

    void persist(RequestLogService.RequestLogEntry entry) {
        persistWithRetry(entry, 0);
    }

    Mono<RequestLogService.RequestLogEntry> loadByRequestId(String requestId) {
        return configStore.load(CONFIG_TYPE, requestId)
                .flatMap(codec::deserialize);
    }

    void shutdown() {
        int count = pendingWriteCount.get();
        if (count <= 0) {
            return;
        }
        log.info("waiting_for_pending_writes count={}", count);
        long deadline = System.currentTimeMillis() + 5000;
        while (pendingWriteCount.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int remaining = pendingWriteCount.get();
        if (remaining > 0) {
            log.warn("shutdown_with_pending_writes remaining={}", remaining);
        }
    }

    void resetForTests() {
        pendingWrites.forEach(PendingWrite::dispose);
        pendingWrites.clear();
        pendingWriteCount.set(0);
    }

    private void persistWithRetry(RequestLogService.RequestLogEntry entry, int attempt) {
        long start = System.nanoTime();
        PendingWrite pendingWrite = new PendingWrite();
        pendingWrites.add(pendingWrite);
        pendingWriteCount.incrementAndGet();
        Disposable disposable = codec.serialize(entry)
                .flatMap(json -> configStore.save(CONFIG_TYPE, entry.requestId(), json))
                .doOnSuccess(ignored -> metricsRecorder.recordWriteLatency(
                        "requestLogPersist",
                        (System.nanoTime() - start) / 1_000_000))
                .doOnError(error -> {
                    if (attempt < maxPersistRetries) {
                        log.warn("request_log_persist_retry request_id={} attempt={} reason={}",
                                entry.requestId(), attempt + 1, error.getMessage());
                        persistWithRetry(entry, attempt + 1);
                    } else {
                        log.warn("request_log_persist_failed request_id={} reason={}", entry.requestId(), error.getMessage());
                    }
                })
                .doFinally(signalType -> {
                    if (pendingWrite.finish()) {
                        pendingWrites.remove(pendingWrite);
                        pendingWriteCount.decrementAndGet();
                    }
                })
                .onErrorResume(error -> Mono.empty())
                .subscribe();
        pendingWrite.attach(disposable);
    }

    private static final class PendingWrite {
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private Disposable disposable;

        synchronized void attach(Disposable disposable) {
            if (finished.get()) {
                disposable.dispose();
                return;
            }
            this.disposable = disposable;
        }

        boolean finish() {
            return finished.compareAndSet(false, true);
        }

        synchronized void dispose() {
            finished.set(true);
            if (disposable != null) {
                disposable.dispose();
                disposable = null;
            }
        }
    }
}
