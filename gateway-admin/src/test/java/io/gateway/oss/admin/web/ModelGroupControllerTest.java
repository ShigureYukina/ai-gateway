package io.gateway.oss.admin.web;

import io.gateway.oss.admin.sync.PublicModelMetadataService;
import io.gateway.oss.core.contract.RouteCatalogView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.RouteConfigWriter;
import io.gateway.oss.core.contract.SceneCatalogView;
import io.gateway.oss.core.contract.SceneConfigView;
import io.gateway.oss.core.contract.SceneConfigWriter;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(useDefaultFilters = false)
@Import({ModelGroupController.class, GlobalExceptionHandler.class})
class ModelGroupControllerTest {

    private static final String ADMIN_BEARER_TOKEN = "Bearer admin-token";
    private static final String USER_BEARER_TOKEN = "Bearer user-token";
    private static final ClientPrincipal ADMIN_PRINCIPAL = new ClientPrincipal("admin", null, "admin");
    private static final ClientPrincipal USER_PRINCIPAL = new ClientPrincipal("user1", null, "user");

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private RouteCatalogView routeCatalogView;

    @MockBean
    private SceneCatalogView sceneCatalogView;

    @MockBean
    private RouteConfigWriter routeConfigWriter;

    @MockBean
    private SceneConfigWriter sceneConfigWriter;

    @MockBean
    private ClientAuthService clientAuthService;

    @MockBean
    private PublicModelMetadataService publicModelMetadataService;

    @MockBean
    private GatewayMetricsRecorder gatewayMetricsRecorder;

    @MockBean
    private WebTestCleanupSupport webTestCleanupSupport;

    @BeforeEach
    void setUp() {
        when(clientAuthService.authenticate(ADMIN_BEARER_TOKEN)).thenReturn(ADMIN_PRINCIPAL);
        when(clientAuthService.authenticate(USER_BEARER_TOKEN)).thenReturn(USER_PRINCIPAL);
        when(clientAuthService.authenticate(null))
                .thenThrow(new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Missing or invalid Authorization header"));
        doThrow(new GatewayException(HttpStatus.FORBIDDEN, "forbidden", "Admin access required"))
                .when(clientAuthService).requireAdmin(USER_PRINCIPAL);

        when(routeCatalogView.getRoutes()).thenAnswer(invocation -> modelGroupRoutes());
        when(sceneCatalogView.getScenes()).thenAnswer(invocation -> modelGroupScenes());
        when(publicModelMetadataService.findByAlias("gpt-4o-mini"))
                .thenReturn(new PublicModelMetadataService.ModelMetadata(Map.of("streaming", true), Map.of("unitPrice", 0.00012)));

        when(routeConfigWriter.saveRoute(any(), any())).thenReturn(Mono.empty());
        when(sceneConfigWriter.saveScene(any(), any())).thenReturn(Mono.empty());
        when(routeConfigWriter.deleteRoute(any())).thenReturn(Mono.empty());
        when(sceneConfigWriter.deleteScene(any())).thenReturn(Mono.empty());
    }

    @Test
    void shouldListModelGroups() {
        webTestClient.get()
                .uri("/admin/model-groups")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.groups.gpt-4o-mini.alias").isEqualTo("gpt-4o-mini")
                .jsonPath("$.groups.gpt-4o-mini.scene").isEqualTo("default-chat")
                .jsonPath("$.groups.gpt-4o-mini.members[0].routeId").isEqualTo("openai-primary")
                .jsonPath("$.groups.gpt-4o-mini.fallbackOrder[0]").isEqualTo("openai-fallback");
    }

    @Test
    void shouldCreateModelGroupViaPut() {
        webTestClient.put()
                .uri("/admin/model-groups/new-model")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("members", List.of(
                        Map.of("provider", "openai", "upstreamModel", "gpt-4o-mini", "weight", 3),
                        Map.of("provider", "anthropic", "upstreamModel", "claude-3-haiku", "weight", 1)
                )))
                .exchange()
                .expectStatus().isCreated();

        verify(routeConfigWriter, times(3)).saveRoute(any(), any());
        verify(sceneConfigWriter).saveScene(eq("new-model-scene"), any());
    }

    @Test
    void shouldUpdateModelGroupViaPut() {
        webTestClient.put()
                .uri("/admin/model-groups/gpt-4o-mini")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("members", List.of(
                        Map.of("provider", "openai", "upstreamModel", "gpt-4o-mini", "weight", 2),
                        Map.of("provider", "openai", "upstreamModel", "gpt-4o-mini", "weight", 1)
                )))
                .exchange()
                .expectStatus().isOk();

        verify(routeConfigWriter).saveRoute(eq("gpt-4o-mini"), any());
        verify(sceneConfigWriter).saveScene(eq("gpt-4o-mini-scene"), any());
    }

    @Test
    void shouldDeleteModelGroup() {
        webTestClient.delete()
                .uri("/admin/model-groups/gpt-4o-mini")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isNoContent();

        verify(routeConfigWriter).deleteRoute("openai-primary");
        verify(routeConfigWriter).deleteRoute("openai-fallback");
        verify(sceneConfigWriter).deleteScene("default-chat");
        verify(routeConfigWriter).deleteRoute("gpt-4o-mini");
    }

    @Test
    void shouldReturn404WhenDeletingNonExistentGroup() {
        webTestClient.delete()
                .uri("/admin/model-groups/not-exists")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldReturn403ForUserTokenOnModelGroups() {
        webTestClient.get()
                .uri("/admin/model-groups")
                .header("Authorization", USER_BEARER_TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("forbidden");
    }

    @Test
    void shouldReturn401WhenAuthMissingOnModelGroups() {
        webTestClient.get()
                .uri("/admin/model-groups")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("unauthorized");
    }

    private Map<String, ? extends RouteConfigView> modelGroupRoutes() {
        return Map.of(
                "gpt-4o-mini", aliasRoute("default-chat"),
                "openai-primary", memberRoute("openai", "gpt-4o-mini", 1),
                "openai-fallback", memberRoute("openai", "gpt-4o-mini", 1)
        );
    }

    private Map<String, ? extends SceneConfigView> modelGroupScenes() {
        return Map.of("default-chat", sceneRoute("openai-primary", List.of("openai-fallback")));
    }

    private RouteConfigView aliasRoute(String sceneId) {
        RouteConfigView route = mock(RouteConfigView.class);
        when(route.getScene()).thenReturn(sceneId);
        return route;
    }

    private RouteConfigView memberRoute(String provider, String upstreamModel, int weight) {
        RouteConfigView route = mock(RouteConfigView.class);
        when(route.getProvider()).thenReturn(provider);
        when(route.getUpstreamModel()).thenReturn(upstreamModel);
        when(route.getWeight()).thenReturn(weight);
        return route;
    }

    private SceneConfigView sceneRoute(String primaryRoute, List<String> fallbackRoutes) {
        SceneConfigView scene = mock(SceneConfigView.class);
        when(scene.getPrimaryRoute()).thenReturn(primaryRoute);
        when(scene.getFallbackRoutes()).thenReturn(fallbackRoutes);
        return scene;
    }
}
