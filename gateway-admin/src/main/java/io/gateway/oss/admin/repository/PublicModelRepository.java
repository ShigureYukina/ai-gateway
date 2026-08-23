package io.gateway.oss.admin.repository;

import io.gateway.oss.admin.entity.PublicModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PublicModelRepository extends JpaRepository<PublicModelEntity, Long> {

    Optional<PublicModelEntity> findByProviderIdAndModelId(Long providerId, String modelId);

    List<PublicModelEntity> findAllByProviderIdAndModelIdOrderByIdAsc(Long providerId, String modelId);

    List<PublicModelEntity> findByProviderId(Long providerId);

    List<PublicModelEntity> findByStatus(String status);

    @Query("SELECT pm FROM PublicModelEntity pm WHERE pm.modelId = :modelId")
    Optional<PublicModelEntity> findByModelId(@Param("modelId") String modelId);
}
