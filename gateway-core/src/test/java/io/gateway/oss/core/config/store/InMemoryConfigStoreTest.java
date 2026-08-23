package io.gateway.oss.core.config.store;

import io.gateway.oss.core.config.InMemoryConfigStore;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryConfigStoreTest {

    private final InMemoryConfigStore store = new InMemoryConfigStore();

    @Test
    void shouldSaveAndLoadSingleEntry() {
        String json = "{\"baseUrl\":\"http://localhost:8080\"}";

        StepVerifier.create(store.save("providers", "openai", json))
                .verifyComplete();

        StepVerifier.create(store.load("providers", "openai"))
                .expectNext(json)
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyForNonExistentEntry() {
        StepVerifier.create(store.load("providers", "missing"))
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyForNonExistentType() {
        StepVerifier.create(store.load("unknown", "key"))
                .verifyComplete();
    }

    @Test
    void shouldOverwriteExistingEntry() {
        StepVerifier.create(store.save("providers", "openai", "{\"v\":1}"))
                .verifyComplete();
        StepVerifier.create(store.save("providers", "openai", "{\"v\":2}"))
                .verifyComplete();

        StepVerifier.create(store.load("providers", "openai"))
                .expectNext("{\"v\":2}")
                .verifyComplete();
    }

    @Test
    void shouldDeleteExistingEntry() {
        StepVerifier.create(store.save("providers", "openai", "{\"x\":1}"))
                .verifyComplete();

        StepVerifier.create(store.delete("providers", "openai"))
                .verifyComplete();

        StepVerifier.create(store.load("providers", "openai"))
                .verifyComplete();
    }

    @Test
    void shouldDeleteNonExistentEntryWithoutError() {
        StepVerifier.create(store.delete("providers", "never-existed"))
                .verifyComplete();
    }

    @Test
    void shouldLoadAllForType() {
        StepVerifier.create(store.save("providers", "openai", "{\"a\":1}")).verifyComplete();
        StepVerifier.create(store.save("providers", "anthropic", "{\"a\":2}")).verifyComplete();

        StepVerifier.create(store.loadAll("providers"))
                .assertNext(all -> {
                    assertEquals(2, all.size());
                    assertEquals("{\"a\":1}", all.get("openai"));
                    assertEquals("{\"a\":2}", all.get("anthropic"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyMapForNonExistentType() {
        StepVerifier.create(store.loadAll("unknown"))
                .assertNext(all -> assertTrue(all.isEmpty()))
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyMapForEmptyType() {
        StepVerifier.create(store.save("providers", "openai", "{}")).verifyComplete();
        StepVerifier.create(store.delete("providers", "openai")).verifyComplete();

        StepVerifier.create(store.loadAll("providers"))
                .assertNext(all -> assertTrue(all.isEmpty()))
                .verifyComplete();
    }

    @Test
    void shouldIsolateTypes() {
        StepVerifier.create(store.save("providers", "openai", "{\"p\":1}")).verifyComplete();
        StepVerifier.create(store.save("routes", "r1", "{\"r\":1}")).verifyComplete();
        StepVerifier.create(store.save("clients", "c1", "{\"c\":1}")).verifyComplete();

        StepVerifier.create(store.loadAll("providers"))
                .assertNext(all -> assertEquals(1, all.size()))
                .verifyComplete();
        StepVerifier.create(store.loadAll("routes"))
                .assertNext(all -> assertEquals(1, all.size()))
                .verifyComplete();
        StepVerifier.create(store.loadAll("clients"))
                .assertNext(all -> assertEquals(1, all.size()))
                .verifyComplete();
    }

    @Test
    void shouldSupportSystemTypeWithMultipleKeys() {
        StepVerifier.create(store.save("system", "limit", "{\"requestsPerWindow\":30}")).verifyComplete();
        StepVerifier.create(store.save("system", "resilience", "{\"maxAttempts\":3}")).verifyComplete();
        StepVerifier.create(store.save("system", "pricing", "{\"default\":{\"unitPrice\":0.0001}}")).verifyComplete();

        StepVerifier.create(store.loadAll("system"))
                .assertNext(all -> {
                    assertEquals(3, all.size());
                    assertEquals("{\"requestsPerWindow\":30}", all.get("limit"));
                    assertEquals("{\"maxAttempts\":3}", all.get("resilience"));
                })
                .verifyComplete();
    }

    @Test
    void clearShouldRemoveAllData() {
        StepVerifier.create(store.save("providers", "openai", "{}")).verifyComplete();
        StepVerifier.create(store.save("routes", "r1", "{}")).verifyComplete();

        store.clear();

        StepVerifier.create(store.loadAll("providers"))
                .assertNext(all -> assertTrue(all.isEmpty()))
                .verifyComplete();
        StepVerifier.create(store.loadAll("routes"))
                .assertNext(all -> assertTrue(all.isEmpty()))
                .verifyComplete();
    }

    @Test
    void saveIfAbsentOrReplaceExpired_shouldOnlyInsertOnceWhenExistingNotExpired() {
        StepVerifier.create(store.saveIfAbsentOrReplaceExpired("refresh-token-blacklist", "token", "{\"expiresAt\":9999999999999}", Duration.ofSeconds(30)))
                .expectNext(true)
                .verifyComplete();

        StepVerifier.create(store.saveIfAbsentOrReplaceExpired("refresh-token-blacklist", "token", "{\"expiresAt\":9999999999998}", Duration.ofSeconds(30)))
                .expectNext(false)
                .verifyComplete();

        StepVerifier.create(store.load("refresh-token-blacklist", "token"))
                .expectNext("{\"expiresAt\":9999999999999}")
                .verifyComplete();
    }
}
