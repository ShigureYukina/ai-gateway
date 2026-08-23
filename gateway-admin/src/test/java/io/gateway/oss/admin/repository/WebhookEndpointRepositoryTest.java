package io.gateway.oss.admin.repository;

import io.gateway.oss.admin.entity.WebhookEndpointEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebhookEndpointRepositoryTest extends RepositoryDataJpaTestSupport {

    @Autowired
    private WebhookEndpointRepository repository;

    @Test
    void shouldSaveAndFindById() {
        var entity = createEndpoint("test-endpoint", "https://hooks.test.com/callback");
        var saved = repository.save(entity);

        var found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("test-endpoint", found.get().getName());
        assertEquals("https://hooks.test.com/callback", found.get().getUrl());
    }

    @Test
    void shouldReturnEmptyForUnknownId() {
        assertTrue(repository.findById(9999L).isEmpty());
    }

    @Test
    void shouldUpdateEndpoint() {
        var saved = repository.save(createEndpoint("update-test", "https://hooks.test.com/old"));
        saved.setUrl("https://hooks.test.com/new");
        saved.setEnabled(false);
        repository.save(saved);

        var updated = repository.findById(saved.getId());
        assertTrue(updated.isPresent());
        assertEquals("https://hooks.test.com/new", updated.get().getUrl());
        assertFalse(updated.get().isEnabled());
    }

    @Test
    void shouldDeleteEndpoint() {
        var saved = repository.save(createEndpoint("delete-test", "https://hooks.test.com/delete"));
        repository.delete(saved);

        assertTrue(repository.findById(saved.getId()).isEmpty());
    }

    @Test
    void shouldSaveWithEventTypes() {
        var entity = createEndpoint("event-test", "https://hooks.test.com/events");
        entity.setEventTypes(List.of("provider.updated", "config.changed"));
        entity.setRetryMax(5);
        entity.setTimeoutMs(10000);
        var saved = repository.save(entity);

        var found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals(2, found.get().getEventTypes().size());
        assertEquals("provider.updated", found.get().getEventTypes().get(0));
        assertEquals(5, found.get().getRetryMax());
        assertEquals(10000, found.get().getTimeoutMs());
    }

    private static WebhookEndpointEntity createEndpoint(String name, String url) {
        var e = new WebhookEndpointEntity();
        e.setName(name);
        e.setUrl(url);
        e.setEnabled(true);
        e.setRetryMax(3);
        e.setTimeoutMs(5000);
        return e;
    }
}
