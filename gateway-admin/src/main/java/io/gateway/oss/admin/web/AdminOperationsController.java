package io.gateway.oss.admin.web;

import io.gateway.oss.admin.sync.ModelsDevSyncService;
import io.gateway.oss.admin.web.alerts.AdminAlertsService;
import io.gateway.oss.admin.webhook.WebhookDispatcherService;
import io.gateway.oss.core.security.ClientAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;

@RestController
@RequestMapping("/admin")
public class AdminOperationsController extends AdminBaseController {

    private final ModelsDevSyncService syncService;
    private final AdminAlertsService adminAlertsService;
    private final WebhookDispatcherService webhookDispatcherService;

    public AdminOperationsController(ClientAuthService clientAuthService,
                                     ModelsDevSyncService syncService,
                                     AdminAlertsService adminAlertsService,
                                     WebhookDispatcherService webhookDispatcherService) {
        super(clientAuthService);
        this.syncService = syncService;
        this.adminAlertsService = adminAlertsService;
        this.webhookDispatcherService = webhookDispatcherService;
    }

    @PostMapping("/sync/models-dev")
    public Mono<SyncTriggerResponse> syncModelsDev(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        Instant triggeredAt = Instant.now();
        return syncService.syncOnceReactive("admin-api", true)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(success -> new SyncTriggerResponse(
                        triggeredAt,
                        Instant.now(),
                        success ? "success" : "failed",
                        success
                ));
    }

    @GetMapping("/alerts")
    public Mono<AdminAlertsService.AlertsView> alerts(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        return adminAlertsService.getAlerts()
                .doOnSuccess(webhookDispatcherService::triggerAlertTriggered);
    }

    public record SyncTriggerResponse(
            Instant triggeredAt,
            Instant completedAt,
            String status,
            boolean success
    ) {
    }
}
