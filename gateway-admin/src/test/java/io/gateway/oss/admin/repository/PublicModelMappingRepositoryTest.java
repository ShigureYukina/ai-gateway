package io.gateway.oss.admin.repository;

import io.gateway.oss.admin.entity.ProviderRegistryEntity;
import io.gateway.oss.admin.entity.PublicModelEntity;
import io.gateway.oss.admin.entity.PublicModelMappingEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PublicModelMappingRepositoryTest extends RepositoryDataJpaTestSupport {

    @Autowired
    private PublicModelMappingRepository mappingRepository;

    @Autowired
    private PublicModelRepository modelRepository;

    @Autowired
    private ProviderRegistryRepository providerRepository;

    private Long modelId;

    @BeforeEach
    void setUp() {
        var provider = new ProviderRegistryEntity();
        provider.setName("mapping-test-provider");
        provider.setType("openai");
        provider.setBaseUrl("https://api.test.com");
        provider.setStatus("active");
        Long pid = providerRepository.save(provider).getId();

        var model = new PublicModelEntity();
        model.setProviderId(pid);
        model.setModelId("gpt-4o");
        model.setStatus("active");
        modelId = modelRepository.save(model).getId();
    }

    @Test
    void shouldSaveAndFindByAlias() {
        createMapping("gpt4", modelId);

        Optional<PublicModelMappingEntity> found = mappingRepository.findByAlias("gpt4");
        assertTrue(found.isPresent());
        assertEquals(modelId, found.get().getPublicModelId());
    }

    @Test
    void shouldReturnEmptyForUnknownAlias() {
        assertTrue(mappingRepository.findByAlias("non-existent").isEmpty());
    }

    @Test
    void shouldFindFirstByAliasOrdered() {
        createMapping("first-test-alias", modelId);

        Optional<PublicModelMappingEntity> first = mappingRepository.findFirstByAliasOrderByIdAsc("first-test-alias");
        assertTrue(first.isPresent());
        assertEquals("first-test-alias", first.get().getAlias());
    }

    @Test
    void shouldFindAllByAliasOrdered() {
        createMapping("single-alias", modelId);

        var results = mappingRepository.findAllByAliasOrderByIdAsc("single-alias");
        assertEquals(1, results.size());
        assertEquals("single-alias", results.getFirst().getAlias());
    }

    @Test
    void shouldFindAllByOrderByAliasAsc() {
        createMapping("z-alias", modelId);
        createMapping("a-alias", modelId);
        createMapping("m-alias", modelId);

        var all = mappingRepository.findAllByOrderByAliasAsc();
        assertEquals(3, all.size());
        assertTrue(all.get(0).getAlias().compareTo(all.get(1).getAlias()) <= 0);
    }

    @Test
    void shouldEnforceUniqueAlias() {
        createMapping("unique-test", modelId);
        assertThrows(Exception.class, () -> createMapping("unique-test", modelId));
    }

    private PublicModelMappingEntity createMapping(String alias, Long publicModelId) {
        var e = new PublicModelMappingEntity();
        e.setAlias(alias);
        e.setPublicModelId(publicModelId);
        return mappingRepository.save(e);
    }
}
