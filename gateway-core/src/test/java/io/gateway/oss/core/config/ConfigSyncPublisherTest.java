package io.gateway.oss.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigSyncPublisherTest {

    @Test
    void publish_shouldIncrementVersionAndSendTypedPayload() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        GatewayProperties properties = new GatewayProperties();
        ConfigLoadService configLoadService = mock(ConfigLoadService.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("gateway:config:sync:version:providers")).thenReturn(7L);

        ConfigSyncPublisher publisher = new ConfigSyncPublisher(redisTemplate, properties, configLoadService);
        publisher.publish(DynamicConfigService.TYPE_PROVIDERS);

        verify(valueOperations).increment("gateway:config:sync:version:providers");
        verify(redisTemplate).convertAndSend("gateway:config:sync", "providers:7");
    }

    @Test
    void parseMessage_shouldHandleVersionedAndLegacyPayloads() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        GatewayProperties properties = new GatewayProperties();
        ConfigLoadService configLoadService = mock(ConfigLoadService.class);
        ConfigSyncPublisher publisher = new ConfigSyncPublisher(redisTemplate, properties, configLoadService);

        Method parseMessage = ConfigSyncPublisher.class.getDeclaredMethod("parseMessage", String.class);
        parseMessage.setAccessible(true);

        Object versioned = parseMessage.invoke(publisher, "providers:9");
        Method configType = versioned.getClass().getDeclaredMethod("configType");
        Method version = versioned.getClass().getDeclaredMethod("version");
        configType.setAccessible(true);
        version.setAccessible(true);

        assertEquals("providers", configType.invoke(versioned));
        assertEquals(9L, version.invoke(versioned));

        Object legacy = parseMessage.invoke(publisher, "system");
        assertEquals("system", configType.invoke(legacy));
        assertNull(version.invoke(legacy));
    }
}
