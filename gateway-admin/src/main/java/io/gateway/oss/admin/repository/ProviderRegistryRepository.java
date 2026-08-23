package io.gateway.oss.admin.repository;

import io.gateway.oss.admin.entity.ProviderRegistryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProviderRegistryRepository extends JpaRepository<ProviderRegistryEntity, Long> {

    Optional<ProviderRegistryEntity> findByName(String name);

    List<ProviderRegistryEntity> findByStatus(String status);
}
