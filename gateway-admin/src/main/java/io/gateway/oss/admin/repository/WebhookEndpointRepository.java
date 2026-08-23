package io.gateway.oss.admin.repository;

import io.gateway.oss.admin.entity.WebhookEndpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpointEntity, Long> {
}
