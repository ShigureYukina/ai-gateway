package io.gateway.oss.admin.sync;

import io.gateway.oss.admin.entity.ProviderRegistryEntity;
import io.gateway.oss.admin.entity.PublicModelEntity;
import io.gateway.oss.admin.entity.PublicModelMappingEntity;
import io.gateway.oss.admin.repository.ProviderRegistryRepository;
import io.gateway.oss.admin.repository.PublicModelMappingRepository;
import io.gateway.oss.admin.repository.PublicModelRepository;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.SceneConfigView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ProviderModelPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ProviderModelPersistenceService.class);

    private final ProviderRegistryRepository providerRegistryRepo;
    private final PublicModelRepository publicModelRepo;
    private final PublicModelMappingRepository publicModelMappingRepo;
    private final ObjectMapper objectMapper;
    private final GatewayConfigView configView;

    public ProviderModelPersistenceService(ProviderRegistryRepository providerRegistryRepo,
                                           PublicModelRepository publicModelRepo,
                                           @Autowired(required = false) PublicModelMappingRepository publicModelMappingRepo,
                                           ObjectMapper objectMapper,
                                           GatewayConfigView configView) {
        this.providerRegistryRepo = providerRegistryRepo;
        this.publicModelRepo = publicModelRepo;
        this.publicModelMappingRepo = publicModelMappingRepo;
        this.objectMapper = objectMapper;
        this.configView = configView;
    }

    @Transactional
    public void persistFromSnapshot(ModelsDevClient.ModelsDevSnapshot snapshot) {
        // 1. Upsert providers and models
        for (Map.Entry<String, Set<String>> entry : snapshot.providerModels().entrySet()) {
            String providerName = entry.getKey();
            Set<String> models = entry.getValue();

            ProviderRegistryEntity providerEntity = upsertProvider(providerName);
            for (String modelName : models) {
                upsertModel(providerEntity, modelName, snapshot);
            }
        }

        // 2. Upsert model mappings from scenes
        if (publicModelMappingRepo != null) {
            upsertModelMappings(snapshot);
        }
    }

    private ProviderRegistryEntity upsertProvider(String providerName) {
        Optional<ProviderRegistryEntity> existing = providerRegistryRepo.findByName(providerName);
        ProviderRegistryEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
        } else {
            entity = new ProviderRegistryEntity();
            entity.setName(providerName);
            entity.setType("openai-compatible");
            entity.setStatus("active");
        }
        return providerRegistryRepo.save(entity);
    }

    private void upsertModel(ProviderRegistryEntity providerEntity, String modelName,
                             ModelsDevClient.ModelsDevSnapshot snapshot) {
        Optional<PublicModelEntity> existing = publicModelRepo.findByProviderIdAndModelId(
                providerEntity.getId(), modelName);

        PublicModelEntity entity;
        boolean isNew = existing.isEmpty();
        if (existing.isPresent()) {
            entity = existing.get();
        } else {
            entity = new PublicModelEntity();
            entity.setModelId(modelName);
            entity.setProviderId(providerEntity.getId());
            entity.setStatus("active");
        }

        // Build pricing map
        Map<String, Object> pricing = new HashMap<>();
        BigDecimal inputPrice = snapshot.modelPrices().get(modelName);
        if (inputPrice != null) {
            pricing.put("input", inputPrice);
        }
        Map<String, Object> metadata = snapshot.modelMetadata().get(modelName);
        if (metadata != null && metadata.containsKey("output_price")) {
            pricing.put("output", metadata.get("output_price"));
        }
        if (!pricing.isEmpty()) {
            entity.setPricing(pricing);
        }

        // Build capabilities map and displayName from metadata
        if (metadata != null) {
            Map<String, Object> capabilities = new HashMap<>();
            if (metadata.containsKey("context_length")) {
                capabilities.put("context_length", metadata.get("context_length"));
            }
            if (metadata.containsKey("max_tokens")) {
                capabilities.put("max_tokens", metadata.get("max_tokens"));
            }
            if (!capabilities.isEmpty()) {
                entity.setCapabilities(capabilities);
            }
            if (metadata.containsKey("display_name")) {
                entity.setDisplayName(String.valueOf(metadata.get("display_name")));
            }
        }

        publicModelRepo.save(entity);
    }

    private void upsertModelMappings(ModelsDevClient.ModelsDevSnapshot snapshot) {
        Map<String, ? extends SceneConfigView> scenes = configView.getScenes();
        Map<String, ? extends RouteConfigView> routes = configView.getRoutes();

        // 遍历 routes 找有 scene 的别名（即模型组 alias），而非遍历 scenes
        for (Map.Entry<String, ? extends RouteConfigView> routeEntry : routes.entrySet()) {
            String groupAlias = routeEntry.getKey();
            RouteConfigView aliasRoute = routeEntry.getValue();

            String sceneId = aliasRoute.getScene();
            if (sceneId == null || sceneId.isBlank()) {
                continue;
            }
            SceneConfigView scene = scenes.get(sceneId);
            if (scene == null) {
                continue;
            }

            String primaryRouteName = scene.getPrimaryRoute();
            if (primaryRouteName == null || primaryRouteName.isBlank()) {
                continue;
            }
            RouteConfigView primaryRoute = routes.get(primaryRouteName);
            if (primaryRoute == null) {
                continue;
            }
            String providerName = primaryRoute.getProvider();
            if (providerName == null || providerName.isBlank()) {
                continue;
            }
            String upstreamModel = primaryRoute.getUpstreamModel();
            if (upstreamModel == null || upstreamModel.isBlank()) {
                continue;
            }

            Optional<ProviderRegistryEntity> providerEntity = providerRegistryRepo.findByName(providerName);
            if (providerEntity.isEmpty()) {
                continue;
            }

            Optional<PublicModelEntity> foundModel = resolveModelEntity(providerEntity.get().getId(), upstreamModel);
            if (foundModel.isEmpty()) {
                continue;
            }

            PublicModelMappingEntity mapping = resolveMappingEntity(groupAlias);
            mapping.setPublicModelId(foundModel.get().getId());
            publicModelMappingRepo.save(mapping);
        }
    }

    private Optional<PublicModelEntity> resolveModelEntity(Long providerId, String modelId) {
        List<PublicModelEntity> models = new ArrayList<>(
                publicModelRepo.findAllByProviderIdAndModelIdOrderByIdAsc(providerId, modelId));
        if (models.isEmpty()) {
            return Optional.empty();
        }

        PublicModelEntity canonical = models.get(0);
        if (models.size() > 1) {
            List<PublicModelEntity> duplicates = models.subList(1, models.size());
            publicModelRepo.deleteAllInBatch(duplicates);
            log.warn("public_model_duplicates_cleaned providerId={} modelId={} removed={} keptId={}",
                    providerId, modelId, duplicates.size(), canonical.getId());
        }
        return Optional.of(canonical);
    }

    private PublicModelMappingEntity resolveMappingEntity(String groupAlias) {
        List<PublicModelMappingEntity> mappings = new ArrayList<>(publicModelMappingRepo.findAllByAliasOrderByIdAsc(groupAlias));
        if (mappings.isEmpty()) {
            PublicModelMappingEntity mapping = new PublicModelMappingEntity();
            mapping.setAlias(groupAlias);
            return mapping;
        }

        PublicModelMappingEntity canonical = mappings.get(0);
        if (mappings.size() > 1) {
            List<PublicModelMappingEntity> duplicates = mappings.subList(1, mappings.size());
            publicModelMappingRepo.deleteAllInBatch(duplicates);
            log.warn("mapping_duplicates_cleaned alias={} removed={} keptId={}",
                    groupAlias, duplicates.size(), canonical.getId());
        }
        return canonical;
    }
}
