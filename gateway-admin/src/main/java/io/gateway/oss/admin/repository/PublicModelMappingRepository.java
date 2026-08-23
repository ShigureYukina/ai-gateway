package io.gateway.oss.admin.repository;

import io.gateway.oss.admin.entity.PublicModelMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PublicModelMappingRepository extends JpaRepository<PublicModelMappingEntity, Long> {

    Optional<PublicModelMappingEntity> findByAlias(String alias);

    List<PublicModelMappingEntity> findAllByAliasOrderByIdAsc(String alias);

    Optional<PublicModelMappingEntity> findFirstByAliasOrderByIdAsc(String alias);

    List<PublicModelMappingEntity> findAllByOrderByAliasAsc();
}
