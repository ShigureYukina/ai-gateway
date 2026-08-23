package io.gateway.oss.admin.web;

import io.gateway.oss.admin.sync.PublicModelMetadataService;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.config.SceneConfig;
import io.gateway.oss.core.contract.RouteCatalogView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.RouteConfigWriter;
import io.gateway.oss.core.contract.SceneCatalogView;
import io.gateway.oss.core.contract.SceneConfigView;
import io.gateway.oss.core.contract.SceneConfigWriter;
import io.gateway.oss.core.security.ClientAuthService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/model-groups")
public class ModelGroupController extends AdminBaseController {

    private final RouteCatalogView routeCatalogView;
    private final SceneCatalogView sceneCatalogView;
    private final RouteConfigWriter routeConfigWriter;
    private final SceneConfigWriter sceneConfigWriter;
    private final PublicModelMetadataService publicModelMetadataService;

    public ModelGroupController(RouteCatalogView routeCatalogView,
                                SceneCatalogView sceneCatalogView,
                                RouteConfigWriter routeConfigWriter,
                                SceneConfigWriter sceneConfigWriter,
                                ClientAuthService clientAuthService,
                                PublicModelMetadataService publicModelMetadataService) {
        super(clientAuthService);
        this.routeCatalogView = routeCatalogView;
        this.sceneCatalogView = sceneCatalogView;
        this.routeConfigWriter = routeConfigWriter;
        this.sceneConfigWriter = sceneConfigWriter;
        this.publicModelMetadataService = publicModelMetadataService;
    }

    @GetMapping
    public ModelGroupsResponse list(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        Map<String, ModelGroupView> groups = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends RouteConfigView> routeEntry : routeCatalogView.getRoutes().entrySet()) {
            String alias = routeEntry.getKey();
            RouteConfigView aliasRoute = routeEntry.getValue();
            String sceneId = aliasRoute.getScene();
            if (sceneId == null || sceneId.isBlank()) {
                continue;
            }
            SceneConfigView sceneConfig = sceneCatalogView.getScenes().get(sceneId);
            if (sceneConfig == null) {
                continue;
            }
            List<String> fallbackOrder = sceneConfig.getFallbackRoutes() == null ? List.of() : sceneConfig.getFallbackRoutes();
            List<String> routeIds = new ArrayList<>();
            if (sceneConfig.getPrimaryRoute() != null && !sceneConfig.getPrimaryRoute().isBlank()) {
                routeIds.add(sceneConfig.getPrimaryRoute());
            }
            routeIds.addAll(fallbackOrder);

            List<MemberView> members = new ArrayList<>();
            for (String routeId : routeIds) {
                RouteConfigView concrete = routeCatalogView.getRoutes().get(routeId);
                if (concrete == null) {
                    continue;
                }
                members.add(new MemberView(routeId, concrete.getProvider(), concrete.getUpstreamModel(), concrete.getWeight()));
            }
            groups.put(alias, buildModelGroupView(alias, sceneId, members, fallbackOrder));
        }
        return new ModelGroupsResponse(Instant.now(), groups);
    }

    @PutMapping("/{alias}")
    public Mono<ResponseEntity<ModelGroupPutRequest>> put(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String alias,
            @RequestBody ModelGroupPutRequest request) {
        requireAdminAccess(authorizationHeader);
        boolean isNew = !routeCatalogView.getRoutes().containsKey(alias);
        if (request.members() == null || request.members().isEmpty()) {
            return Mono.error(new IllegalArgumentException("members must not be empty"));
        }

        String sceneId = alias + "-scene";
        String primaryRouteId = alias + "-primary";
        List<String> fallbackRouteIds = new ArrayList<>();
        List<Mono<Void>> operations = new ArrayList<>();

        for (int i = 0; i < request.members().size(); i++) {
            PutMember member = request.members().get(i);
            String routeId = i == 0 ? primaryRouteId : alias + "-fallback-" + (i - 1);
            if (i > 0) {
                fallbackRouteIds.add(routeId);
            }
            RouteConfig routeConfig = new RouteConfig();
            routeConfig.setProvider(member.provider());
            routeConfig.setUpstreamModel(member.upstreamModel());
            routeConfig.setWeight(member.weight());
            operations.add(routeConfigWriter.saveRoute(routeId, routeConfig));
        }

        SceneConfig sceneConfig = new SceneConfig();
        sceneConfig.setPrimaryRoute(primaryRouteId);
        sceneConfig.setFallbackRoutes(fallbackRouteIds);
        operations.add(sceneConfigWriter.saveScene(sceneId, sceneConfig));

        RouteConfig aliasRoute = new RouteConfig();
        aliasRoute.setScene(sceneId);
        operations.add(routeConfigWriter.saveRoute(alias, aliasRoute));

        return Mono.when(operations)
                .then(Mono.fromCallable(() -> ResponseEntity.status(isNew ? HttpStatus.CREATED : HttpStatus.OK).body(request)));
    }

    @DeleteMapping("/{alias}")
    public Mono<ResponseEntity<Void>> delete(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String alias) {
        requireAdminAccess(authorizationHeader);
        RouteConfigView aliasRoute = routeCatalogView.getRoutes().get(alias);
        if (aliasRoute == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }
        String sceneId = aliasRoute.getScene();
        SceneConfigView sceneConfig = sceneId == null ? null : sceneCatalogView.getScenes().get(sceneId);
        List<Mono<Void>> operations = new ArrayList<>();
        if (sceneConfig != null) {
            if (sceneConfig.getPrimaryRoute() != null && !sceneConfig.getPrimaryRoute().isBlank()) {
                operations.add(routeConfigWriter.deleteRoute(sceneConfig.getPrimaryRoute()));
            }
            if (sceneConfig.getFallbackRoutes() != null) {
                for (String fallbackId : sceneConfig.getFallbackRoutes()) {
                    operations.add(routeConfigWriter.deleteRoute(fallbackId));
                }
            }
            operations.add(sceneConfigWriter.deleteScene(sceneId));
        }
        operations.add(routeConfigWriter.deleteRoute(alias));
        return Mono.when(operations).then(Mono.just(ResponseEntity.noContent().build()));
    }

    private ModelGroupView buildModelGroupView(String alias, String sceneId, List<MemberView> members,
                                               List<String> fallbackOrder) {
        PublicModelMetadataService.ModelMetadata metadata = publicModelMetadataService.findByAlias(alias);
        return new ModelGroupView(alias, sceneId, members, fallbackOrder,
                metadata.capabilities(), metadata.pricing());
    }

    public record ModelGroupsResponse(Instant generatedAt, Map<String, ModelGroupView> groups) {
    }

    public record ModelGroupView(String alias, String scene, List<MemberView> members, List<String> fallbackOrder,
                                 Map<String, Object> capabilities, Map<String, Object> pricing) {
    }

    public record MemberView(String routeId, String provider, String upstreamModel, int weight) {
    }

    public record ModelGroupPutRequest(List<PutMember> members) {
    }

    public record PutMember(String provider, String upstreamModel, int weight) {
    }
}
