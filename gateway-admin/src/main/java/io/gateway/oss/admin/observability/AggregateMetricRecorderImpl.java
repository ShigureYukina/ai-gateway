package io.gateway.oss.admin.observability;

import io.gateway.oss.core.contract.AggregateMetricRecorder;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Bridges core's {@link AggregateMetricRecorder} to admin's {@link AggregateReportingService}.
 */
@Service
public class AggregateMetricRecorderImpl implements AggregateMetricRecorder {

    private final AggregateReportingService reportingService;

    public AggregateMetricRecorderImpl(AggregateReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @Override
    public void recordSuccess(String requestId,
                              ClientPrincipal principal,
                              ResolvedRoute route,
                              String model,
                              long usageTokens,
                              Double costUsd,
                              Instant now) {
        String user = principal.username() != null ? principal.username() : principal.clientId();
        reportingService.recordSuccess(
                requestId,
                route.provider(),
                user,
                principal.clientId(),
                principal.clientId(),
                principal.clientId(),
                model,
                usageTokens,
                costUsd,
                now
        );
    }

    @Override
    public void recordFailureStatus(String requestId, int status, Instant now) {
        reportingService.recordFailureStatus(requestId, status, now);
    }
}
