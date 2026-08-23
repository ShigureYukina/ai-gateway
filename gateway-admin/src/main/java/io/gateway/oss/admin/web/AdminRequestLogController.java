package io.gateway.oss.admin.web;

import io.gateway.oss.core.security.ClientAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/admin")
public class AdminRequestLogController extends AdminBaseController {

    private final RequestLogQueryService requestLogQueryService;

    public AdminRequestLogController(ClientAuthService clientAuthService,
                                     RequestLogQueryService requestLogQueryService) {
        super(clientAuthService);
        this.requestLogQueryService = requestLogQueryService;
    }

    @GetMapping("/requests/recent")
    public AdminRecentRequestsResponse recentRequests(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "client", required = false) String client,
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to) {
        requireAdminAccess(authorizationHeader);
        RequestLogQueryService.RequestLogRecentResult result =
                requestLogQueryService.recent(offset, limit, model, client, status, from, to);
        return new AdminRecentRequestsResponse(Instant.now(), result.total(), result.offset(), result.requests());
    }

    public record AdminRecentRequestsResponse(
            Instant generatedAt,
            int total,
            int offset,
            List<RequestLogQueryService.RequestLogEntryView> requests
    ) {
    }
}
