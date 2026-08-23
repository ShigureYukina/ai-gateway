package io.gateway.oss.core.upstream.state;

import io.gateway.oss.core.upstream.InMemoryProviderRuntimeStateStore;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryProviderRuntimeStateStoreTest {

    private InMemoryProviderRuntimeStateStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryProviderRuntimeStateStore();
    }

    @Test
    void shouldReturnUnknownForMissingProvider() {
        ProviderRuntimeStateStore.ProviderRuntimeState state = store.get("non-existent");
        assertTrue(state.runtimeAvailable());
    }

    @Test
    void shouldSaveAndRetrieve() {
        var state = new ProviderRuntimeStateStore.ProviderRuntimeState(true, null, null, 0, 1, null, null, null);
        store.save("provider-1", state);

        var retrieved = store.get("provider-1");
        assertTrue(retrieved.runtimeAvailable());
        assertEquals(1, retrieved.consecutiveSuccesses());
    }

    @Test
    void shouldUpdateExistingProvider() {
        store.save("provider-1", new ProviderRuntimeStateStore.ProviderRuntimeState(true, null, null, 0, 1, null, null, null));
        store.save("provider-1", new ProviderRuntimeStateStore.ProviderRuntimeState(false, null, null, 3, 0, null, null, "timeout"));

        var retrieved = store.get("provider-1");
        assertFalse(retrieved.runtimeAvailable());
        assertEquals(3, retrieved.consecutiveFailures());
        assertEquals("timeout", retrieved.reason());
    }

    @Test
    void shouldReturnAllProviders() {
        store.save("p1", new ProviderRuntimeStateStore.ProviderRuntimeState(true, null, null, 0, 1, null, null, null));
        store.save("p2", new ProviderRuntimeStateStore.ProviderRuntimeState(false, null, null, 2, 0, 500, 100L, "error"));

        Map<String, ProviderRuntimeStateStore.ProviderRuntimeState> all = store.getAll();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("p1"));
        assertTrue(all.containsKey("p2"));

        // Verify snapshot isolation
        all.put("p3", null);
        assertEquals(2, store.getAll().size());
    }

    @Test
    void shouldReturnEmptyMapWhenNoProviders() {
        assertTrue(store.getAll().isEmpty());
    }

    @Test
    void shouldResetForTests() {
        store.save("p1", new ProviderRuntimeStateStore.ProviderRuntimeState(true, null, null, 0, 1, null, null, null));
        store.resetForTests();
        assertTrue(store.getAll().isEmpty());
        assertTrue(store.get("p1").runtimeAvailable());
    }
}
