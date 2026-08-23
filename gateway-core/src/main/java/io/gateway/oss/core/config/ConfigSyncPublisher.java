package io.gateway.oss.core.config;

import io.gateway.oss.core.util.RedisStoreUtils;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis pub/sub publisher for config sync notifications.
 * <p>
 * When Redis is available, publishes config change events to a sync channel
 * and subscribes to reload config from other instances.
 * </p>
 */
public class ConfigSyncPublisher {

    private static final Logger log = LoggerFactory.getLogger(ConfigSyncPublisher.class);
    private static final long RECONCILE_INTERVAL_SECONDS = 5;

    private final StringRedisTemplate redisTemplate;
    private final String syncChannel;
    private final ConfigLoadService configLoadService;
    private final ScheduledExecutorService reconcileExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "config-sync-reconcile");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Long> localAppliedVersions = new ConcurrentHashMap<>();

    public ConfigSyncPublisher(StringRedisTemplate redisTemplate, GatewayProperties properties, ConfigLoadService configLoadService) {
        this.redisTemplate = redisTemplate;
        this.syncChannel = RedisStoreUtils.safePrefix(properties.getSharedState().getKeyPrefix()) + ":config:sync";
        this.configLoadService = configLoadService;
    }

    public void subscribe() {
        try {
            redisTemplate.getConnectionFactory().getConnection()
                    .subscribe((message, pattern) -> {
                        String payload = new String(message.getBody());
                        SyncMessage syncMessage = parseMessage(payload);
                        log.info("ConfigSyncPublisher: received sync notification for '{}' version={} ", syncMessage.configType(), syncMessage.version());
                        reloadConfig(syncMessage.configType(), syncMessage.version());
                    }, syncChannel.getBytes());
            log.info("ConfigSyncPublisher: subscribed to config sync channel '{}'", syncChannel);
        } catch (RedisConnectionFailureException e) {
            log.warn("ConfigSyncPublisher: failed to subscribe to config sync channel: {}", e.getMessage());
        }
        reconcileExecutor.scheduleWithFixedDelay(this::reconcileVersionsSafely,
                RECONCILE_INTERVAL_SECONDS,
                RECONCILE_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void subscribeOnApplicationReady() {
        subscribe();
    }

    public void publish(String configType) {
        try {
            Long version = redisTemplate.opsForValue().increment(versionKey(configType));
            long effectiveVersion = version != null ? version : 0L;
            localAppliedVersions.put(configType, effectiveVersion);
            redisTemplate.convertAndSend(syncChannel, configType + ":" + effectiveVersion);
        } catch (RedisConnectionFailureException e) {
            log.warn("Failed to publish config sync for '{}': {}", configType, e.getMessage());
        }
    }

    private void reloadConfig(String configType, Long version) {
        configLoadService.reload(configType)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(v -> {
                    if (version != null) {
                        localAppliedVersions.put(configType, version);
                    }
                })
                .doOnError(e -> log.warn("ConfigSyncPublisher: failed to reload config for '{}': {}", configType, e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private void reconcileVersionsSafely() {
        for (String configType : new String[]{
                DynamicConfigService.TYPE_PROVIDERS,
                DynamicConfigService.TYPE_ROUTES,
                DynamicConfigService.TYPE_SCENES,
                DynamicConfigService.TYPE_CLIENTS,
                DynamicConfigService.TYPE_SYSTEM
        }) {
            try {
                String raw = redisTemplate.opsForValue().get(versionKey(configType));
                long remoteVersion = raw != null ? Long.parseLong(raw) : 0L;
                long localVersion = localAppliedVersions.getOrDefault(configType, 0L);
                if (remoteVersion > localVersion) {
                    log.info("ConfigSyncPublisher: reconciling '{}' localVersion={} remoteVersion={}",
                            configType, localVersion, remoteVersion);
                    reloadConfig(configType, remoteVersion);
                }
            } catch (Exception e) {
                log.warn("ConfigSyncPublisher: reconcile failed for '{}': {}", configType, e.getMessage());
            }
        }
    }

    private String versionKey(String configType) {
        return syncChannel + ":version:" + configType;
    }

    private SyncMessage parseMessage(String payload) {
        int separator = payload.lastIndexOf(':');
        if (separator <= 0 || separator == payload.length() - 1) {
            return new SyncMessage(payload, null);
        }
        String configType = payload.substring(0, separator);
        try {
            return new SyncMessage(configType, Long.parseLong(payload.substring(separator + 1)));
        } catch (NumberFormatException e) {
            return new SyncMessage(payload, null);
        }
    }

    @PreDestroy
    public void shutdown() {
        reconcileExecutor.shutdownNow();
    }

    private record SyncMessage(String configType, Long version) {}
}
