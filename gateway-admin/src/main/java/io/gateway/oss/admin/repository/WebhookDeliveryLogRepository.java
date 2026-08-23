package io.gateway.oss.admin.repository;

import io.gateway.oss.admin.entity.WebhookDeliveryLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLogEntity, Long> {

    List<WebhookDeliveryLogEntity> findTop100ByOrderByCreatedAtDesc();
}
