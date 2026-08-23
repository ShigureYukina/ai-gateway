package io.gateway.oss.admin.web;

import io.gateway.oss.admin.config.audit.ConfigAuditService;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.SceneConfigView;
import io.gateway.oss.admin.config.audit.ConfigVersionService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

record AuditResponse(
        Instant generatedAt,
        List<ConfigAuditService.AuditEntry> entries
) {
}

record VersionsResponse(
        Instant generatedAt,
        String configType,
        String configKey,
        List<ConfigVersionService.ConfigVersion> versions
) {
}

record RollbackResponse(
        String configType,
        String configKey,
        int versionNumber,
        Instant rolledBackAt
) {
}

record SnapshotResponse(
        Instant generatedAt,
        Map<String, ProviderSnapshot> providers,
        Map<String, RouteConfigView> routes,
        Map<String, SceneConfigView> scenes,
        Map<String, ClientView> clients,
        Map<String, Object> system
) {
}

record UnifiedAuditCenterResponse(
        Instant generatedAt,
        List<UnifiedAuditEntry> entries
) {
}

record UnifiedAuditEntry(
        String eventType,
        Instant timestamp,
        String actor,
        String resourceType,
        String resourceId,
        String action,
        String result,
        String reason,
        String requestId,
        String clientId,
        String model,
        String provider,
        String routeId,
        String scene,
        Integer status,
        Long latencyMs
) {
}

record ProviderSnapshot(
        String type,
        String baseUrl,
        String apiKey,
        List<String> keys,
        List<Integer> keyWeights,
        Duration timeout,
        boolean enabled
) {
}

record ClientView(
        boolean enabled,
        Set<String> allowedModels,
        Set<String> allowedScenes,
        Map<String, String> modelScenes,
        ClientDefaultsView defaults,
        ClientCapabilitiesView capabilities,
        ClientLimitsView limits
) {
}

record ClientDefaultsView(
        String scene,
        Double temperature,
        Integer maxTokens
) {
}

record ClientCapabilitiesView(
        boolean streaming
) {
}

record ClientLimitsView(
        Integer maxTokens,
        Long dailyTokens,
        BigDecimal dailyCost,
        Long monthlyTokens,
        BigDecimal monthlyCost
) {
}
