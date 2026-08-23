package io.gateway.oss.core.config;

import java.util.function.Supplier;

/**
 * Utility for hybrid store bean resolution.
 * Eliminates duplicated switch-on-backend logic across 5 domain config files.
 *
 * <p>Usage in a hybrid @Configuration inner class:
 * <pre>{@code
 * @Bean
 * public MyStore hybridStore() {
 *     return BackendSelector.resolve(
 *         properties.getStore().getUsage(),
 *         InMemoryMyStore::new,
 *         () -> new RedisMyStore(redis, props),
 *         () -> new PostgresMyStore(jdbc)
 *     );
 * }
 * }</pre>
 */
public final class BackendSelector {

    private BackendSelector() {
    }

    /**
     * Selects the appropriate store implementation based on backend type.
     * Falls back to inMemory for HYBRID or unknown values.
     */
    public static <T> T resolve(Backend backend,
                                Supplier<T> inMemory,
                                Supplier<T> redis,
                                Supplier<T> postgres) {
        return switch (backend) {
            case REDIS -> redis.get();
            case POSTGRESQL -> postgres.get();
            default -> inMemory.get();
        };
    }
}
