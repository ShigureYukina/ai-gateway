package io.gateway.oss.admin.repository;

import io.gateway.oss.admin.entity.ProviderRegistryEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryRepositoryTest extends RepositoryDataJpaTestSupport {

    @Autowired
    private ProviderRegistryRepository repository;

    @Test
    void shouldSaveAndFindByName() {
        var entity = new ProviderRegistryEntity();
        entity.setName("test-provider");
        entity.setType("openai");
        entity.setBaseUrl("https://api.test.com");
        entity.setStatus("active");
        repository.save(entity);

        Optional<ProviderRegistryEntity> found = repository.findByName("test-provider");
        assertTrue(found.isPresent());
        assertEquals("openai", found.get().getType());
        assertEquals("https://api.test.com", found.get().getBaseUrl());
    }

    @Test
    void shouldReturnEmptyForUnknownName() {
        assertTrue(repository.findByName("non-existent").isEmpty());
    }

    @Test
    void shouldFindByStatus() {
        repository.save(createProvider("provider-a", "active"));
        repository.save(createProvider("provider-b", "inactive"));
        repository.save(createProvider("provider-c", "active"));

        var activeProviders = repository.findByStatus("active");
        assertEquals(2, activeProviders.size());
        assertTrue(activeProviders.stream().allMatch(p -> "active".equals(p.getStatus())));
    }

    @Test
    void shouldReturnEmptyListForNoMatchingStatus() {
        assertTrue(repository.findByStatus("nonexistent").isEmpty());
    }

    @Test
    void shouldUpdateEntity() {
        var entity = createProvider("update-test", "active");
        entity = repository.save(entity);

        entity.setStatus("inactive");
        repository.save(entity);

        var updated = repository.findByName("update-test");
        assertTrue(updated.isPresent());
        assertEquals("inactive", updated.get().getStatus());
    }

    @Test
    void shouldDeleteEntity() {
        var entity = repository.save(createProvider("delete-me", "active"));
        repository.delete(entity);

        assertTrue(repository.findByName("delete-me").isEmpty());
    }

    private static ProviderRegistryEntity createProvider(String name, String status) {
        var e = new ProviderRegistryEntity();
        e.setName(name);
        e.setType("openai-compatible");
        e.setBaseUrl("https://" + name + ".test.com");
        e.setStatus(status);
        return e;
    }
}
