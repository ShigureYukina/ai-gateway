package io.gateway.oss.admin.sync;

import io.gateway.oss.admin.entity.PublicModelEntity;
import io.gateway.oss.admin.repository.PublicModelMappingRepository;
import io.gateway.oss.admin.repository.PublicModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PublicModelMetadataService {

    private static final Logger log = LoggerFactory.getLogger(PublicModelMetadataService.class);

    private final PublicModelMappingRepository publicModelMappingRepo;
    private final PublicModelRepository publicModelRepo;

    public PublicModelMetadataService(PublicModelMappingRepository publicModelMappingRepo,
                                      PublicModelRepository publicModelRepo) {
        this.publicModelMappingRepo = publicModelMappingRepo;
        this.publicModelRepo = publicModelRepo;
    }

    public ModelMetadata findByAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return ModelMetadata.empty();
        }
        try {
            return publicModelMappingRepo.findFirstByAliasOrderByIdAsc(alias)
                    .flatMap(mapping -> publicModelRepo.findById(mapping.getPublicModelId()))
                    .map(this::toMetadata)
                    .orElseGet(ModelMetadata::empty);
        } catch (DataAccessException e) {
            log.debug("public_model_metadata_unavailable alias={} reason={}", alias, e.getMessage());
            return ModelMetadata.empty();
        }
    }

    private ModelMetadata toMetadata(PublicModelEntity entity) {
        Map<String, Object> capabilities = entity.getCapabilities() == null
                ? Map.of()
                : entity.getCapabilities();
        Map<String, Object> pricing = entity.getPricing() == null
                ? Map.of()
                : entity.getPricing();
        return new ModelMetadata(capabilities, pricing);
    }

    public record ModelMetadata(Map<String, Object> capabilities, Map<String, Object> pricing) {
        static ModelMetadata empty() {
            return new ModelMetadata(Map.of(), Map.of());
        }
    }
}
