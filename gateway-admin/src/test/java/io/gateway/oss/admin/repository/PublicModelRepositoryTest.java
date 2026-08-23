package io.gateway.oss.admin.repository;

import io.gateway.oss.admin.entity.ProviderRegistryEntity;
import io.gateway.oss.admin.entity.PublicModelEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PublicModelRepositoryTest extends RepositoryDataJpaTestSupport {

    @Autowired
    private PublicModelRepository modelRepository;

    @Autowired
    private ProviderRegistryRepository providerRepository;

    private Long providerId;

    @BeforeEach
    void setUp() {
        var provider = new ProviderRegistryEntity();
        provider.setName("model-test-provider");
        provider.setType("openai");
        provider.setBaseUrl("https://api.test.com");
        provider.setStatus("active");
        providerId = providerRepository.save(provider).getId();
    }

    @Test
    void shouldSaveAndFindByProviderIdAndModelId() {
        createModel("gpt-4o", "active");

        Optional<PublicModelEntity> found = modelRepository.findByProviderIdAndModelId(providerId, "gpt-4o");
        assertTrue(found.isPresent());
        assertEquals("gpt-4o", found.get().getModelId());
        assertEquals("active", found.get().getStatus());
    }

    @Test
    void shouldFindByModelIdWithCustomQuery() {
        createModel("gpt-4o-mini", "active");

        Optional<PublicModelEntity> found = modelRepository.findByModelId("gpt-4o-mini");
        assertTrue(found.isPresent());
        assertEquals("gpt-4o-mini", found.get().getModelId());
    }

    @Test
    void shouldReturnEmptyForUnknownModel() {
        assertTrue(modelRepository.findByModelId("non-existent").isEmpty());
    }

    @Test
    void shouldFindAllByProviderIdAndModelIdOrdered() {
        createModel("model-b", "active");
        createModel("model-a", "active");

        var results = modelRepository.findAllByProviderIdAndModelIdOrderByIdAsc(providerId, "model-a");
        assertEquals(1, results.size());
        assertEquals("model-a", results.getFirst().getModelId());
    }

    @Test
    void shouldFindByProviderId() {
        createModel("model-x", "active");
        createModel("model-y", "inactive");

        var results = modelRepository.findByProviderId(providerId);
        assertEquals(2, results.size());
    }

    @Test
    void shouldFindByStatus() {
        createModel("active-model", "active");
        createModel("inactive-model", "inactive");

        var activeModels = modelRepository.findByStatus("active");
        assertTrue(activeModels.stream().allMatch(m -> "active".equals(m.getStatus())));
    }

    @Test
    void shouldUpdateModelStatus() {
        var entity = createModel("to-update", "active");
        entity.setStatus("deprecated");
        modelRepository.save(entity);

        var updated = modelRepository.findByProviderIdAndModelId(providerId, "to-update");
        assertTrue(updated.isPresent());
        assertEquals("deprecated", updated.get().getStatus());
    }

    private PublicModelEntity createModel(String modelId, String status) {
        var e = new PublicModelEntity();
        e.setProviderId(providerId);
        e.setModelId(modelId);
        e.setDisplayName("Display " + modelId);
        e.setStatus(status);
        return modelRepository.save(e);
    }
}
