package io.gateway.oss.admin.web;

import io.gateway.oss.admin.pricing.BillingPriceResolver;
import io.gateway.oss.admin.sync.ModelListService;
import io.gateway.oss.admin.sync.ProviderModelCatalogService;
import io.gateway.oss.core.config.ProviderConfig;
import io.gateway.oss.core.config.PricingConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.config.SceneConfig;
import io.gateway.oss.core.contract.ModelPublicationConfigWriter;
import io.gateway.oss.core.contract.PricingPublicationConfigView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelPublicationServiceTest {

    @Mock
    private PricingPublicationConfigView gatewayConfigView;

    @Mock
    private ModelPublicationConfigWriter writer;

    @Mock
    private ProviderModelCatalogService providerModelCatalogService;

    @Mock
    private BillingPriceResolver billingPriceResolver;

    @Mock
    private ModelListService modelListService;

    private ModelPublicationService service;

    @BeforeEach
    void setUp() {
        service = new ModelPublicationService(gatewayConfigView, writer,
                providerModelCatalogService, billingPriceResolver, modelListService);
    }

    @Test
    void shouldNotRunAnyRollbackWhenFirstStepFails() {
        stubView(Map.of(), Map.of(), new PricingConfig());
        // 装配期即会调用全部 writer 方法生成 Mono，因此需先给通用路径打桩
        when(writer.saveRoute(anyString(), any(RouteConfig.class))).thenReturn(Mono.empty());
        when(writer.saveScene(anyString(), any(SceneConfig.class))).thenReturn(Mono.empty());
        when(writer.saveSystemPricing(any())).thenReturn(Mono.empty());
        when(writer.saveRoute(eq("support-bot-primary"), any(RouteConfig.class)))
                .thenReturn(Mono.error(new IllegalStateException("store down")));

        StepVerifier.create(publish())
                .verifyError(IllegalStateException.class);

        verify(writer, never()).deleteRoute(anyString());
        verify(writer, never()).deleteScene(anyString());
    }

    @Test
    void shouldRollBackCreatedPrimaryRouteWhenSceneSaveFails() {
        stubView(Map.of(), Map.of(), new PricingConfig());
        when(writer.saveRoute(anyString(), any(RouteConfig.class))).thenReturn(Mono.empty());
        when(writer.saveScene(eq("support-bot-scene"), any(SceneConfig.class)))
                .thenReturn(Mono.error(new IllegalStateException("store down")));
        when(writer.saveSystemPricing(any())).thenReturn(Mono.empty());
        when(writer.deleteRoute("support-bot-primary")).thenReturn(Mono.empty());

        StepVerifier.create(publish())
                .verifyError(IllegalStateException.class);

        // scene 保存失败后，第一步已创建的 primary route 应被回滚删除
        verify(writer).deleteRoute("support-bot-primary");
    }

    @Test
    void shouldRestorePreviousPublicationWhenAliasSaveFailsOnRepublish() {
        RouteConfig previousAlias = new RouteConfig();
        previousAlias.setScene("support-bot-scene");
        previousAlias.setEnabled(true);

        RouteConfig previousPrimary = new RouteConfig();
        previousPrimary.setProvider("legacy-provider");
        previousPrimary.setUpstreamModel("legacy-model");
        previousPrimary.setWeight(1);
        previousPrimary.setEnabled(true);

        SceneConfig previousScene = new SceneConfig();
        previousScene.setPrimaryRoute("support-bot-primary");
        previousScene.setFallbackRoutes(List.of("legacy-fallback"));

        stubView(
                Map.of("support-bot", previousAlias, "support-bot-primary", previousPrimary),
                Map.of("support-bot-scene", previousScene),
                new PricingConfig());

        when(writer.saveRoute(anyString(), any(RouteConfig.class))).thenReturn(Mono.empty());
        when(writer.saveScene(eq("support-bot-scene"), any(SceneConfig.class))).thenReturn(Mono.empty());
        when(writer.saveSystemPricing(any())).thenReturn(Mono.empty());
        // 旧 scene 的 fallback 会进入 obsolete 清理链，装配期即被调用
        when(writer.deleteRoute(anyString())).thenReturn(Mono.empty());
        when(writer.saveRoute(eq("support-bot"), any(RouteConfig.class)))
                .thenReturn(Mono.error(new IllegalStateException("store down")));

        StepVerifier.create(publish())
                .verifyError(IllegalStateException.class);

        // 回滚按完成逆序：先恢复旧 scene，再恢复旧 primary route
        InOrder rollbackOrder = inOrder(writer);
        rollbackOrder.verify(writer).saveScene(eq("support-bot-scene"), argThat(scene ->
                scene.getFallbackRoutes().contains("legacy-fallback")));
        rollbackOrder.verify(writer).saveRoute(eq("support-bot-primary"), argThat(route ->
                "legacy-provider".equals(route.getProvider())));
    }

    @Test
    void shouldRestoreObsoleteRoutesAndOldSceneWhenCleanupFails() {
        RouteConfig previousAlias = new RouteConfig();
        previousAlias.setScene("legacy-scene");
        previousAlias.setEnabled(true);

        RouteConfig legacyPrimary = new RouteConfig();
        legacyPrimary.setProvider("legacy-provider");
        legacyPrimary.setUpstreamModel("legacy-model");
        legacyPrimary.setEnabled(true);

        SceneConfig legacyScene = new SceneConfig();
        legacyScene.setPrimaryRoute("legacy-primary");

        PricingConfig previousPricing = new PricingConfig();
        previousPricing.setExactMatches(Map.of("support-bot", "legacy-model"));

        stubView(
                Map.of("support-bot", previousAlias, "legacy-primary", legacyPrimary),
                Map.of("legacy-scene", legacyScene),
                previousPricing);

        when(writer.saveRoute(anyString(), any(RouteConfig.class))).thenReturn(Mono.empty());
        when(writer.saveScene(anyString(), any(SceneConfig.class))).thenReturn(Mono.empty());
        when(writer.deleteRoute(anyString())).thenReturn(Mono.empty());
        when(writer.deleteScene("support-bot-scene")).thenReturn(Mono.empty());
        when(writer.saveSystemPricing(any())).thenReturn(Mono.empty());
        when(writer.deleteScene("legacy-scene")).thenReturn(Mono.error(new IllegalStateException("store down")));

        StepVerifier.create(publish())
                .verifyError(IllegalStateException.class);

        // 回滚按完成逆序：恢复被删除的 obsolete route → 恢复旧 alias → 删除新 scene → 删除新 primary。
        // 旧 scene 的 deleteScene 本身失败，旧数据未被删除，因此无需也无法恢复。
        InOrder rollbackOrder = inOrder(writer);
        rollbackOrder.verify(writer).saveRoute(eq("legacy-primary"), argThat(route ->
                "legacy-provider".equals(route.getProvider())));
        rollbackOrder.verify(writer).saveRoute(eq("support-bot"), argThat(route ->
                "legacy-scene".equals(route.getScene())));
        rollbackOrder.verify(writer).deleteScene("support-bot-scene");
        rollbackOrder.verify(writer).deleteRoute("support-bot-primary");

        // pricing 前向写入 merged 值，回滚恢复为发布前的旧值
        ArgumentCaptor<PricingConfig> pricingCaptor = ArgumentCaptor.forClass(PricingConfig.class);
        verify(writer, times(2)).saveSystemPricing(pricingCaptor.capture());
        assertEquals("gpt-4o-mini", pricingCaptor.getAllValues().get(0).getExactMatches().get("support-bot"));
        assertEquals("legacy-model", pricingCaptor.getAllValues().get(1).getExactMatches().get("support-bot"));
    }

    @Test
    void shouldNotRollBackWhenPublishSucceeds() {
        stubView(Map.of(), Map.of(), new PricingConfig());
        when(writer.saveRoute(anyString(), any(RouteConfig.class))).thenReturn(Mono.empty());
        when(writer.saveScene(anyString(), any(SceneConfig.class))).thenReturn(Mono.empty());
        when(writer.saveSystemPricing(any())).thenReturn(Mono.empty());
        when(billingPriceResolver.preview("support-bot", "gpt-4o-mini", "openai"))
                .thenReturn(Map.<String, Object>of("source", "synced_pricing"));
        when(modelListService.buildModels(null, "support-bot")).thenReturn(List.of());

        StepVerifier.create(publish())
                .assertNext(outcome -> {
                    assertTrue(outcome.created());
                    assertEquals("support-bot", outcome.response().alias());
                    assertEquals("openai", outcome.response().provider());
                })
                .verifyComplete();

        verify(writer, never()).deleteRoute(anyString());
        verify(writer, never()).deleteScene(anyString());
        verify(writer).saveRoute(eq("support-bot-primary"), argThat(route ->
                "openai".equals(route.getProvider()) && route.getWeight() == 1));
        verify(writer).saveRoute(eq("support-bot"), argThat(route ->
                "support-bot-scene".equals(route.getScene())));
    }

    @Test
    void shouldSkipPricingUndoWhenNoPreviousPricingExists() {
        stubView(Map.of(), Map.of(), null);
        when(writer.saveRoute(anyString(), any(RouteConfig.class))).thenReturn(Mono.empty());
        when(writer.saveScene(anyString(), any(SceneConfig.class))).thenReturn(Mono.empty());
        when(writer.saveSystemPricing(any())).thenReturn(Mono.error(new IllegalStateException("store down")));
        when(writer.deleteRoute(anyString())).thenReturn(Mono.empty());
        when(writer.deleteScene("support-bot-scene")).thenReturn(Mono.empty());

        StepVerifier.create(publish())
                .verifyError(IllegalStateException.class);

        verify(writer, times(1)).saveSystemPricing(any());
        verify(writer).deleteRoute("support-bot");
        verify(writer).deleteScene("support-bot-scene");
        verify(writer).deleteRoute("support-bot-primary");
    }

    private Mono<ModelPublicationService.PublishOutcome> publish() {
        return service.publish("support-bot",
                new ModelPublicationService.PublishRequest("openai", "gpt-4o-mini"));
    }

    private ProviderConfig providerWithModels() {
        ProviderConfig provider = new ProviderConfig();
        provider.setEnabled(true);
        provider.setModels(List.of("gpt-4o-mini"));
        return provider;
    }

    private void stubView(Map<String, RouteConfig> routes,
                          Map<String, SceneConfig> scenes,
                          PricingConfig pricing) {
        // 视口接口返回 Map<String, ? extends XxxView>，用 doReturn 绕过通配符泛型检查
        doReturn(Map.of("openai", providerWithModels())).when(gatewayConfigView).getProviders();
        doReturn(routes).when(gatewayConfigView).getRoutes();
        doReturn(scenes).when(gatewayConfigView).getScenes();
        doReturn(pricing).when(gatewayConfigView).getPricing();
    }
}
