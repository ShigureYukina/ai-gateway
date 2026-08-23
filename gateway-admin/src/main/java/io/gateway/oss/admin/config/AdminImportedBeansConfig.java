package io.gateway.oss.admin.config;

import io.gateway.oss.admin.limit.ClientTpmService;
import io.gateway.oss.admin.limit.TpmStoreConfig;
import io.gateway.oss.admin.observability.AdminObservabilityStoreConfig;
import io.gateway.oss.admin.observability.AggregateMetricRecorderImpl;
import io.gateway.oss.admin.observability.AggregateReportingService;
import io.gateway.oss.admin.upstream.ProviderHealthScheduler;
import io.gateway.oss.admin.web.AdminClientController;
import io.gateway.oss.admin.web.AdminConfigController;
import io.gateway.oss.admin.web.AdminConfigExportSupport;
import io.gateway.oss.admin.web.AdminConfigImportSupport;
import io.gateway.oss.admin.web.AdminDashboardController;
import io.gateway.oss.admin.web.AdminDashboardOverviewService;
import io.gateway.oss.admin.web.AdminOperationsController;
import io.gateway.oss.admin.web.AdminProviderController;
import io.gateway.oss.admin.web.AdminRequestLogController;
import io.gateway.oss.admin.web.AdminRouteController;
import io.gateway.oss.admin.web.AdminSystemConfigController;
import io.gateway.oss.admin.web.AdminUserController;
import io.gateway.oss.admin.web.AdminWebhookController;
import io.gateway.oss.admin.web.AuditQueryService;
import io.gateway.oss.admin.web.ConfigAuditController;
import io.gateway.oss.admin.web.InternalEndpointAuthFilter;
import io.gateway.oss.admin.web.InternalModelsSnapshotController;
import io.gateway.oss.admin.web.InternalProviderStateController;
import io.gateway.oss.admin.web.InternalRequestLogController;
import io.gateway.oss.admin.web.InternalSystemStatusController;
import io.gateway.oss.admin.web.InternalUsageSummaryController;
import io.gateway.oss.admin.web.InternalUsageSummaryReadService;
import io.gateway.oss.admin.web.ModelGroupController;
import io.gateway.oss.admin.web.ModelPublicationController;
import io.gateway.oss.admin.web.ModelPublicationService;
import io.gateway.oss.admin.web.RequestLogQueryService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * admin 显式导入 Bean 分组配置。
 */
@Configuration
@Import({
        ClientTpmService.class,
        TpmStoreConfig.class,
        AggregateReportingService.class,
        AggregateMetricRecorderImpl.class,
        AdminObservabilityStoreConfig.class,
        ProviderHealthScheduler.class,
        InternalEndpointAuthFilter.class,
        AdminDashboardOverviewService.class,
        AdminClientController.class,
        AdminConfigExportSupport.class,
        AdminConfigController.class,
        AdminConfigImportSupport.class,
        AdminDashboardController.class,
        AdminProviderController.class,
        AdminOperationsController.class,
        AdminRequestLogController.class,
        AdminRouteController.class,
        AdminSystemConfigController.class,
        AdminUserController.class,
        AdminWebhookController.class,
        ConfigAuditController.class,
        InternalModelsSnapshotController.class,
        InternalProviderStateController.class,
        InternalRequestLogController.class,
        InternalSystemStatusController.class,
        InternalUsageSummaryReadService.class,
        InternalUsageSummaryController.class,
        ModelPublicationController.class,
        ModelPublicationService.class,
        ModelGroupController.class,
        RequestLogQueryService.class,
        AuditQueryService.class
})
public class AdminImportedBeansConfig {
}
