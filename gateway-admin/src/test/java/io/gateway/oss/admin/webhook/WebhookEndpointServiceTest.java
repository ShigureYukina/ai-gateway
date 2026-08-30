package io.gateway.oss.admin.webhook;

import io.gateway.oss.admin.entity.WebhookEndpointEntity;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.security.BaseUrlValidator;
import io.gateway.oss.admin.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookEndpointServiceTest {

    @Mock
    private WebhookEndpointRepository webhookEndpointRepository;

    private WebhookEndpointService webhookEndpointService;

    @BeforeEach
    void setUp() {
        // 存量用例关注实体/视图映射，使用宽松校验器（blockInternalUrls=false 直接放行，无 DNS 查询）
        webhookEndpointService = new WebhookEndpointService(webhookEndpointRepository, new BaseUrlValidator(false));
    }

    @Test
    void listShouldReturnViewsFromRepository() {
        Instant now = Instant.now();
        WebhookEndpointEntity entity = endpointEntity(1L, "endpoint-a", "https://example.com/hook", "secret-a",
                true, List.of("alert.triggered"), 3, 5000, now, now);
        when(webhookEndpointRepository.findAll()).thenReturn(List.of(entity));

        List<?> result = webhookEndpointService.list();

        assertEquals(1, result.size());
        assertEquals(1L, invokeAccessor(result.get(0), "id"));
        assertEquals("endpoint-a", invokeAccessor(result.get(0), "name"));
        assertEquals("https://example.com/hook", invokeAccessor(result.get(0), "url"));
        assertEquals("secret-a", invokeAccessor(result.get(0), "secret"));
        assertEquals(List.of("alert.triggered"), invokeAccessor(result.get(0), "eventTypes"));
        verify(webhookEndpointRepository).findAll();
    }

    @Test
    void createShouldCreateEntityAndReturnViewWithSecrets() {
        WebhookEndpointService.UpsertWebhookEndpointCommand command = command(
                "endpoint-a", "https://example.com/hook", "secret-a", true,
                List.of("alert.triggered", " alert.triggered ", "alert.failed"), 5, 3000
        );
        when(webhookEndpointRepository.save(any(WebhookEndpointEntity.class))).thenAnswer(invocation -> {
            WebhookEndpointEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            return entity;
        });

        Object result = webhookEndpointService.create(command);

        assertEquals(10L, invokeAccessor(result, "id"));
        assertEquals("secret-a", invokeAccessor(result, "secret"));
        assertEquals(List.of("alert.triggered", "alert.failed"), invokeAccessor(result, "eventTypes"));
        assertEquals(5, invokeAccessor(result, "retryMax"));
        assertEquals(3000, invokeAccessor(result, "timeoutMs"));
        verify(webhookEndpointRepository).save(any(WebhookEndpointEntity.class));
    }

    @Test
    void updateShouldUpdateExistingEntity() {
        Instant createdAt = Instant.now().minusSeconds(60);
        Instant oldUpdatedAt = Instant.now().minusSeconds(30);
        WebhookEndpointEntity entity = endpointEntity(7L, "old-name", "https://old.example.com", "old-secret",
                false, List.of("old.event"), 2, 1000, createdAt, oldUpdatedAt);
        when(webhookEndpointRepository.findById(7L)).thenReturn(Optional.of(entity));
        when(webhookEndpointRepository.save(any(WebhookEndpointEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WebhookEndpointService.UpsertWebhookEndpointCommand command = command(
                "new-name", "https://new.example.com", "new-secret", true,
                List.of("alert.triggered"), 4, 4000
        );

        Object result = webhookEndpointService.update(7L, command);

        assertEquals("new-name", invokeAccessor(result, "name"));
        assertEquals("https://new.example.com", invokeAccessor(result, "url"));
        assertEquals("new-secret", invokeAccessor(result, "secret"));
        assertEquals(true, invokeAccessor(result, "enabled"));
        assertEquals(List.of("alert.triggered"), invokeAccessor(result, "eventTypes"));
        assertEquals(4, invokeAccessor(result, "retryMax"));
        assertEquals(4000, invokeAccessor(result, "timeoutMs"));
        Instant updatedAt = (Instant) invokeAccessor(result, "updatedAt");
        assertNotNull(updatedAt);
        assertTrue(updatedAt.isAfter(oldUpdatedAt) || updatedAt.equals(oldUpdatedAt));
        verify(webhookEndpointRepository).findById(7L);
        verify(webhookEndpointRepository).save(entity);
    }

    @Test
    void updateShouldThrowGatewayExceptionWhenMissing() {
        when(webhookEndpointRepository.findById(99L)).thenReturn(Optional.empty());

        GatewayException exception = assertThrows(GatewayException.class,
                () -> webhookEndpointService.update(99L, command(
                        "name", "https://example.com", null, true, List.of("*"), 3, 5000
                )));

        assertEquals(404, exception.getStatus().value());
        assertEquals("webhook_not_found", exception.getCode());
        verify(webhookEndpointRepository).findById(99L);
    }

    @Test
    void deleteShouldReturnTrueForExistingAndFalseForMissing() {
        when(webhookEndpointRepository.existsById(1L)).thenReturn(true);
        when(webhookEndpointRepository.existsById(2L)).thenReturn(false);

        boolean deletedExisting = webhookEndpointService.delete(1L);
        boolean deletedMissing = webhookEndpointService.delete(2L);

        assertTrue(deletedExisting);
        assertFalse(deletedMissing);
        verify(webhookEndpointRepository).deleteById(1L);
        verify(webhookEndpointRepository).existsById(1L);
        verify(webhookEndpointRepository).existsById(2L);
    }

    @Test
    void normalizeEventTypesShouldUseWildcardForNullOrEmptyAndRemoveDuplicates() throws Exception {
        Method method = WebhookEndpointService.class.getDeclaredMethod("normalizeEventTypes", List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> nullResult = (List<String>) method.invoke(webhookEndpointService, new Object[]{null});
        @SuppressWarnings("unchecked")
        List<String> emptyResult = (List<String>) method.invoke(webhookEndpointService, List.of());
        @SuppressWarnings("unchecked")
        List<String> distinctResult = (List<String>) method.invoke(webhookEndpointService,
                java.util.Arrays.asList("alert.triggered", " alert.triggered ", "", "  ", null, "alert.failed"));

        assertEquals(List.of("*"), nullResult);
        assertEquals(List.of("*"), emptyResult);
        assertEquals(List.of("alert.triggered", "alert.failed"), distinctResult);
    }

    @Test
    void createShouldRejectInternalUrlWhenBlockingEnabled() {
        // 审查 F9：webhook URL 与 provider baseUrl 同权责面，SSRF 校验默认开启
        WebhookEndpointService strictService = new WebhookEndpointService(
                webhookEndpointRepository, new BaseUrlValidator(true));

        GatewayException exception = assertThrows(GatewayException.class,
                () -> strictService.create(command(
                        "endpoint-ssrf", "http://169.254.169.254/hook", "secret-a", true,
                        List.of("alert.triggered"), 3, 5000
                )));

        assertEquals(400, exception.getStatus().value());
        assertEquals("invalid_base_url", exception.getCode());
        verify(webhookEndpointRepository, never()).save(any(WebhookEndpointEntity.class));
    }

    @Test
    void createShouldAcceptPublicIpUrlWhenBlockingEnabled() {
        // 公网 IP 字面量不经 DNS，确定性通过严格校验
        WebhookEndpointService strictService = new WebhookEndpointService(
                webhookEndpointRepository, new BaseUrlValidator(true));
        when(webhookEndpointRepository.save(any(WebhookEndpointEntity.class))).thenAnswer(invocation -> {
            WebhookEndpointEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            return entity;
        });

        Object result = strictService.create(command(
                "endpoint-public", "https://8.8.8.8/hook", "secret-a", true,
                List.of("alert.triggered"), 3, 5000
        ));

        assertEquals(11L, invokeAccessor(result, "id"));
        assertEquals("https://8.8.8.8/hook", invokeAccessor(result, "url"));
        verify(webhookEndpointRepository).save(any(WebhookEndpointEntity.class));
    }

    @Test
    void listReactiveShouldWrapListCorrectly() {
        Instant now = Instant.now();
        WebhookEndpointEntity entity = endpointEntity(3L, "endpoint-b", "https://example.com/b", null,
                true, List.of(), 3, 5000, now, now);
        when(webhookEndpointRepository.findAll()).thenReturn(List.of(entity));

        List<?> result = webhookEndpointService.listReactive().block();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(List.of("*"), invokeAccessor(result.get(0), "eventTypes"));
        verify(webhookEndpointRepository).findAll();
    }

    private static Object invokeAccessor(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static WebhookEndpointService.UpsertWebhookEndpointCommand command(
            String name,
            String url,
            String secret,
            boolean enabled,
            List<String> eventTypes,
            int retryMax,
            int timeoutMs
    ) {
        return new WebhookEndpointService.UpsertWebhookEndpointCommand(
                name, url, secret, enabled, eventTypes, retryMax, timeoutMs
        );
    }

    private static WebhookEndpointEntity endpointEntity(
            Long id,
            String name,
            String url,
            String secret,
            boolean enabled,
            List<String> eventTypes,
            int retryMax,
            int timeoutMs,
            Instant createdAt,
            Instant updatedAt
    ) {
        WebhookEndpointEntity entity = new WebhookEndpointEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setUrl(url);
        entity.setSecret(secret);
        entity.setEnabled(enabled);
        entity.setEventTypes(eventTypes);
        entity.setRetryMax(retryMax);
        entity.setTimeoutMs(timeoutMs);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);
        return entity;
    }
}
