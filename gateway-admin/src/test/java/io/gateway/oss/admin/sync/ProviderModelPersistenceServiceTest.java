package io.gateway.oss.admin.sync;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.config.SceneConfig;
import io.gateway.oss.admin.entity.ProviderRegistryEntity;
import io.gateway.oss.admin.entity.PublicModelEntity;
import io.gateway.oss.admin.entity.PublicModelMappingEntity;
import io.gateway.oss.admin.repository.ProviderRegistryRepository;
import io.gateway.oss.admin.repository.PublicModelMappingRepository;
import io.gateway.oss.admin.repository.PublicModelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderModelPersistenceServiceTest {

    @Mock
    private ProviderRegistryRepository providerRegistryRepo;

    @Mock
    private PublicModelRepository publicModelRepo;

    @Mock
    private PublicModelMappingRepository publicModelMappingRepo;

    @InjectMocks
    private ProviderModelPersistenceService service;

    @Test
    void persistFromSnapshot_shouldCleanDuplicateMappingsAndKeepCanonicalRecord() {
        GatewayProperties properties = gatewayPropertiesForAlias("gpt-4o-mini");
        service = new ProviderModelPersistenceService(
                providerRegistryRepo,
                publicModelRepo,
                publicModelMappingRepo,
                new ObjectMapper(),
                properties
        );

        PublicModelEntity model = new PublicModelEntity();
        model.setId(42L);
        model.setModelId("gpt-4o-mini");

        ProviderRegistryEntity provider = provider(5L, "openai");

        PublicModelMappingEntity canonical = mapping(10L, "gpt-4o-mini");
        PublicModelMappingEntity duplicateA = mapping(11L, "gpt-4o-mini");
        PublicModelMappingEntity duplicateB = mapping(12L, "gpt-4o-mini");

        when(providerRegistryRepo.findByName("openai")).thenReturn(Optional.of(provider));
        when(providerRegistryRepo.save(any(ProviderRegistryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(publicModelRepo.findByProviderIdAndModelId(5L, "gpt-4o-mini")).thenReturn(Optional.of(model));
        when(publicModelRepo.findAllByProviderIdAndModelIdOrderByIdAsc(5L, "gpt-4o-mini"))
                .thenReturn(List.of(model));
        when(publicModelMappingRepo.findAllByAliasOrderByIdAsc("gpt-4o-mini"))
                .thenReturn(List.of(canonical, duplicateA, duplicateB));
        when(publicModelMappingRepo.save(any(PublicModelMappingEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.persistFromSnapshot(snapshotForProviderModel("openai", "gpt-4o-mini"));

        ArgumentCaptor<List<PublicModelMappingEntity>> deleteCaptor = ArgumentCaptor.forClass(List.class);
        verify(publicModelMappingRepo).deleteAllInBatch(deleteCaptor.capture());
        assertEquals(List.of(duplicateA, duplicateB), deleteCaptor.getValue());

        ArgumentCaptor<PublicModelMappingEntity> saveCaptor = ArgumentCaptor.forClass(PublicModelMappingEntity.class);
        verify(publicModelMappingRepo).save(saveCaptor.capture());
        assertSame(canonical, saveCaptor.getValue());
        assertEquals(42L, saveCaptor.getValue().getPublicModelId());
    }

    @Test
    void persistFromSnapshot_shouldCreateMappingWhenAliasDoesNotExist() {
        GatewayProperties properties = gatewayPropertiesForAlias("gpt-4o-mini");
        service = new ProviderModelPersistenceService(
                providerRegistryRepo,
                publicModelRepo,
                publicModelMappingRepo,
                new ObjectMapper(),
                properties
        );

        PublicModelEntity model = new PublicModelEntity();
        model.setId(7L);
        model.setModelId("gpt-4o-mini");

        ProviderRegistryEntity provider = provider(5L, "openai");

        when(providerRegistryRepo.findByName("openai")).thenReturn(Optional.of(provider));
        when(providerRegistryRepo.save(any(ProviderRegistryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(publicModelRepo.findByProviderIdAndModelId(5L, "gpt-4o-mini")).thenReturn(Optional.of(model));
        when(publicModelRepo.findAllByProviderIdAndModelIdOrderByIdAsc(5L, "gpt-4o-mini"))
                .thenReturn(List.of(model));
        when(publicModelMappingRepo.findAllByAliasOrderByIdAsc("gpt-4o-mini")).thenReturn(List.of());
        when(publicModelMappingRepo.save(any(PublicModelMappingEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.persistFromSnapshot(snapshotForProviderModel("openai", "gpt-4o-mini"));

        verify(publicModelMappingRepo, never()).deleteAllInBatch(any());

        ArgumentCaptor<PublicModelMappingEntity> saveCaptor = ArgumentCaptor.forClass(PublicModelMappingEntity.class);
        verify(publicModelMappingRepo).save(saveCaptor.capture());
        assertEquals("gpt-4o-mini", saveCaptor.getValue().getAlias());
        assertEquals(7L, saveCaptor.getValue().getPublicModelId());
    }

    @Test
    void persistFromSnapshot_shouldCleanDuplicatePublicModelsBeforeSavingMapping() {
        GatewayProperties properties = gatewayPropertiesForAlias("gpt-4o-mini");
        service = new ProviderModelPersistenceService(
                providerRegistryRepo,
                publicModelRepo,
                publicModelMappingRepo,
                new ObjectMapper(),
                properties
        );

        ProviderRegistryEntity provider = provider(5L, "openai");
        PublicModelEntity canonicalModel = publicModel(42L, 5L, "gpt-4o-mini");
        PublicModelEntity duplicateModel = publicModel(43L, 5L, "gpt-4o-mini");
        PublicModelMappingEntity mapping = mapping(10L, "gpt-4o-mini");

        when(providerRegistryRepo.findByName("openai")).thenReturn(Optional.of(provider));
        when(providerRegistryRepo.save(any(ProviderRegistryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(publicModelRepo.findByProviderIdAndModelId(5L, "gpt-4o-mini")).thenReturn(Optional.of(canonicalModel));
        when(publicModelRepo.findAllByProviderIdAndModelIdOrderByIdAsc(5L, "gpt-4o-mini"))
                .thenReturn(List.of(canonicalModel, duplicateModel));
        when(publicModelMappingRepo.findAllByAliasOrderByIdAsc("gpt-4o-mini")).thenReturn(List.of(mapping));
        when(publicModelMappingRepo.save(any(PublicModelMappingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.persistFromSnapshot(snapshotForProviderModel("openai", "gpt-4o-mini"));

        ArgumentCaptor<List<PublicModelEntity>> duplicateModelCaptor = ArgumentCaptor.forClass(List.class);
        verify(publicModelRepo).deleteAllInBatch(duplicateModelCaptor.capture());
        assertEquals(List.of(duplicateModel), duplicateModelCaptor.getValue());

        ArgumentCaptor<PublicModelMappingEntity> saveCaptor = ArgumentCaptor.forClass(PublicModelMappingEntity.class);
        verify(publicModelMappingRepo, atLeastOnce()).save(saveCaptor.capture());
        assertSame(mapping, saveCaptor.getValue());
        assertEquals(42L, saveCaptor.getValue().getPublicModelId());
    }

    // ─── Optional.empty() path tests ───

    @Test
    void persistFromSnapshot_shouldCreateProviderWhenNotFound() {
        GatewayProperties properties = gatewayPropertiesForAlias("gpt-4o-mini");
        service = new ProviderModelPersistenceService(
                providerRegistryRepo,
                publicModelRepo,
                publicModelMappingRepo,
                new ObjectMapper(),
                properties
        );

        // findByName returns empty — should trigger provider creation
        when(providerRegistryRepo.save(any(ProviderRegistryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // Model doesn't exist either — should trigger model creation
        when(publicModelRepo.save(any(PublicModelEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.persistFromSnapshot(snapshotForProviderModel("openai", "gpt-4o-mini"));

        ArgumentCaptor<ProviderRegistryEntity> providerCaptor = ArgumentCaptor.forClass(ProviderRegistryEntity.class);
        verify(providerRegistryRepo).save(providerCaptor.capture());
        assertEquals("openai", providerCaptor.getValue().getName());
        assertEquals("openai-compatible", providerCaptor.getValue().getType());
        assertEquals("active", providerCaptor.getValue().getStatus());
    }

    @Test
    void persistFromSnapshot_shouldCreateModelWhenNotFound() {
        GatewayProperties properties = gatewayPropertiesForAlias("gpt-4o-mini");
        service = new ProviderModelPersistenceService(
                providerRegistryRepo,
                publicModelRepo,
                publicModelMappingRepo,
                new ObjectMapper(),
                properties
        );

        ProviderRegistryEntity provider = provider(5L, "openai");
        when(providerRegistryRepo.findByName("openai")).thenReturn(Optional.of(provider));
        when(providerRegistryRepo.save(any(ProviderRegistryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // findByProviderIdAndModelId returns empty — should trigger model creation
        when(publicModelRepo.findByProviderIdAndModelId(5L, "gpt-4o-mini")).thenReturn(Optional.empty());
        when(publicModelRepo.findAllByProviderIdAndModelIdOrderByIdAsc(5L, "gpt-4o-mini")).thenReturn(List.of());
        when(publicModelRepo.save(any(PublicModelEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.persistFromSnapshot(snapshotForProviderModel("openai", "gpt-4o-mini"));

        ArgumentCaptor<PublicModelEntity> modelCaptor = ArgumentCaptor.forClass(PublicModelEntity.class);
        verify(publicModelRepo).save(modelCaptor.capture());
        assertEquals("gpt-4o-mini", modelCaptor.getValue().getModelId());
        assertEquals(5L, modelCaptor.getValue().getProviderId());
        assertEquals("active", modelCaptor.getValue().getStatus());
    }

    private static GatewayProperties gatewayPropertiesForAlias(String alias) {
        GatewayProperties properties = new GatewayProperties();

        RouteConfig aliasRoute = new RouteConfig();
        aliasRoute.setScene("default-chat");

        RouteConfig primaryRoute = new RouteConfig();
        primaryRoute.setProvider("openai");
        primaryRoute.setUpstreamModel("gpt-4o-mini");

        SceneConfig scene = new SceneConfig();
        scene.setPrimaryRoute("openai-primary");

        properties.setRoutes(Map.of(
                alias, aliasRoute,
                "openai-primary", primaryRoute
        ));
        properties.setScenes(Map.of("default-chat", scene));
        return properties;
    }

    private static ModelsDevClient.ModelsDevSnapshot snapshotForProviderModel(String provider, String model) {
        Map<String, Set<String>> providerModels = new LinkedHashMap<>();
        providerModels.put(provider, new LinkedHashSet<>(List.of(model)));
        return new ModelsDevClient.ModelsDevSnapshot(
                providerModels,
                Map.of(),
                Map.of(),
                Map.of(),
                Instant.now()
        );
    }

    private static PublicModelMappingEntity mapping(Long id, String alias) {
        PublicModelMappingEntity entity = new PublicModelMappingEntity();
        entity.setId(id);
        entity.setAlias(alias);
        return entity;
    }

    private static ProviderRegistryEntity provider(Long id, String name) {
        ProviderRegistryEntity entity = new ProviderRegistryEntity();
        entity.setId(id);
        entity.setName(name);
        return entity;
    }

    private static PublicModelEntity publicModel(Long id, Long providerId, String modelId) {
        PublicModelEntity entity = new PublicModelEntity();
        entity.setId(id);
        entity.setProviderId(providerId);
        entity.setModelId(modelId);
        return entity;
    }
}
