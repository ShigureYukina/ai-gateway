package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.contract.RouteCatalogView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.RouteConfigWriter;
import io.gateway.oss.core.security.ClientAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminRouteController extends AdminBaseController {

    private final RouteCatalogView routeCatalogView;
    private final RouteConfigWriter routeConfigWriter;

    public AdminRouteController(ClientAuthService clientAuthService,
                                RouteCatalogView routeCatalogView,
                                RouteConfigWriter routeConfigWriter) {
        super(clientAuthService);
        this.routeCatalogView = routeCatalogView;
        this.routeConfigWriter = routeConfigWriter;
    }

    @GetMapping("/routes")
    public RoutesResponse routes(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        Map<String, RouteView> result = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends RouteConfigView> entry : routeCatalogView.getRoutes().entrySet()) {
            RouteConfigView cfg = entry.getValue();
            result.put(entry.getKey(), new RouteView(
                    cfg.getProvider(),
                    cfg.getUpstreamModel(),
                    cfg.getUpstreamModels(),
                    cfg.getScene(),
                    cfg.getStrategy(),
                    cfg.getFallbackRoutes(),
                    cfg.getWeight(),
                    cfg.isEnabled()
            ));
        }
        return new RoutesResponse(Instant.now(), result);
    }

    @PutMapping("/routes/{id}")
    public Mono<ResponseEntity<RouteConfig>> putRoute(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String id,
            @Valid @RequestBody RouteConfig config) {
        requireAdminAccess(authorizationHeader);
        boolean isNew = !routeCatalogView.getRoutes().containsKey(id);
        return routeConfigWriter.saveRoute(id, config)
                .then(Mono.fromCallable(() -> {
                    int status = isNew ? 201 : 200;
                    return ResponseEntity.status(status).body(config);
                }));
    }

    @DeleteMapping("/routes/{id}")
    public Mono<ResponseEntity<Void>> deleteRoute(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String id) {
        requireAdminAccess(authorizationHeader);
        if (!routeCatalogView.getRoutes().containsKey(id)) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).<Void>build());
        }
        return routeConfigWriter.deleteRoute(id)
                .then(Mono.just(ResponseEntity.status(HttpStatus.NO_CONTENT).<Void>build()));
    }

    // ─── response records ───

    public record RoutesResponse(
            Instant generatedAt,
            Map<String, RouteView> routes
    ) {
    }

    public record RouteView(
            String provider,
            String upstreamModel,
            List<String> upstreamModels,
            String scene,
            String strategy,
            List<String> fallbackRoutes,
            int weight,
            boolean enabled
    ) {
    }
}
