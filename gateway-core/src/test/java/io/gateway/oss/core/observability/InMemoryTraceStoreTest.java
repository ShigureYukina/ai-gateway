package io.gateway.oss.core.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

class InMemoryTraceStoreTest {

    private InMemoryTraceStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore();
    }

    private static TraceRecord trace(String requestId) {
        return new TraceRecord(requestId, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void shouldSaveAndGetByRequestId() {
        store.save(trace("req-1"));

        var retrieved = store.getByRequestId("req-1");
        assertNotNull(retrieved);
        assertEquals("req-1", retrieved.requestId());
    }

    @Test
    void shouldReturnNullForUnknownRequestId() {
        assertNull(store.getByRequestId("non-existent"));
    }

    @Test
    void shouldSaveWithNullRequestId() {
        store.save(trace(null)); // should not throw
        assertTrue(store.getRecent(10).isEmpty());
    }

    @Test
    void shouldReturnRecentTracesInLifoOrder() {
        store.save(trace("r1"));
        store.save(trace("r2"));
        store.save(trace("r3"));

        var recent = store.getRecent(10);
        assertEquals(3, recent.size());
        assertEquals("r3", recent.get(0).requestId());
        assertEquals("r2", recent.get(1).requestId());
        assertEquals("r1", recent.get(2).requestId());
    }

    @Test
    void shouldRespectLimit() {
        store.save(trace("r1"));
        store.save(trace("r2"));

        var limited = store.getRecent(1);
        assertEquals(1, limited.size());
        assertEquals("r2", limited.getFirst().requestId());
    }

    @Test
    void shouldEvictOldestWhenExceedingMax() {
        for (int i = 0; i < 510; i++) {
            store.save(trace("req-" + i));
        }

        // Should have evicted the first 10
        assertNull(store.getByRequestId("req-0"));
        assertNull(store.getByRequestId("req-9"));
        assertNotNull(store.getByRequestId("req-10"));
        assertEquals(500, store.getRecent(1000).size());
    }

    @Test
    void shouldRemoveOldPositionOnUpdate() {
        store.save(trace("r1"));
        store.save(trace("r2"));

        // Re-save r1 — should move to front
        store.save(trace("r1"));

        var recent = store.getRecent(10);
        assertEquals(2, recent.size());
        assertEquals("r1", recent.get(0).requestId()); // now newest
        assertEquals("r2", recent.get(1).requestId());
    }

    @Test
    void shouldResetForTests() {
        store.save(trace("r1"));
        store.resetForTests();

        assertNull(store.getByRequestId("r1"));
        assertTrue(store.getRecent(10).isEmpty());
    }
}
