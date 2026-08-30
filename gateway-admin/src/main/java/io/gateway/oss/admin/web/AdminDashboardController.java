package io.gateway.oss.admin.web;

import io.gateway.oss.admin.dto.AdminDashboardOverviewResponse;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.util.DateParamParser;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@Validated
@RequestMapping("/admin/dashboard")
public class AdminDashboardController extends AdminBaseController {

    private final AdminDashboardOverviewService adminDashboardOverviewService;

    public AdminDashboardController(ClientAuthService clientAuthService,
                                    AdminDashboardOverviewService adminDashboardOverviewService) {
        super(clientAuthService);
        this.adminDashboardOverviewService = adminDashboardOverviewService;
    }

    @GetMapping("/overview")
    public Mono<AdminDashboardOverviewResponse> overview(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(name = "day", required = false)
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "day must match YYYY-MM-DD") String day) {
        requireAdminAccess(authorizationHeader);
        return Mono.fromCallable(() -> adminDashboardOverviewService.buildOverview(resolveDay(day))).subscribeOn(Schedulers.boundedElastic());
    }

    private LocalDate resolveDay(String day) {
        return DateParamParser.resolveIsoDateOrToday(day, "day date");
    }
}
