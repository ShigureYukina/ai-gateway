package io.gateway.oss.core.web;

import io.gateway.oss.core.config.Backend;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.SharedStateConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayHealthIndicatorTest {

    private GatewayProperties properties;
    private SharedStateConfig sharedStateConfig;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        sharedStateConfig = properties.getSharedState();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<DataSource> emptyDataSourceProvider() {
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<DataSource> dataSourceProvider(DataSource ds) {
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ds);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void health_noDataSourceNoRedis_returnsUp() {
        sharedStateConfig.setBackend(Backend.IN_MEMORY);
        GatewayHealthIndicator indicator = new GatewayHealthIndicator(
                emptyDataSourceProvider(), emptyProvider(), properties);

        Health health = indicator.health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("NOT_CONFIGURED", health.getDetails().get("postgresql"));
        assertEquals("NOT_CONFIGURED (in-memory backend)", health.getDetails().get("redis"));
    }

    @Test
    void health_dataSourceUp_returnsUp() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.isValid(2)).thenReturn(true);

        sharedStateConfig.setBackend(Backend.IN_MEMORY);
        GatewayHealthIndicator indicator = new GatewayHealthIndicator(
                dataSourceProvider(ds), emptyProvider(), properties);

        Health health = indicator.health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("UP", health.getDetails().get("postgresql"));
    }

    @Test
    void health_dataSourceDown_returnsDown() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.isValid(2)).thenReturn(false);

        sharedStateConfig.setBackend(Backend.IN_MEMORY);
        GatewayHealthIndicator indicator = new GatewayHealthIndicator(
                dataSourceProvider(ds), emptyProvider(), properties);

        Health health = indicator.health();
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("DOWN", health.getDetails().get("postgresql"));
    }

    @Test
    void health_dataSourceException_returnsDown() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new RuntimeException("Connection refused"));

        sharedStateConfig.setBackend(Backend.IN_MEMORY);
        GatewayHealthIndicator indicator = new GatewayHealthIndicator(
                dataSourceProvider(ds), emptyProvider(), properties);

        Health health = indicator.health();
        assertEquals(Status.DOWN, health.getStatus());
        String pgDetail = (String) health.getDetails().get("postgresql");
        assertTrue(pgDetail.startsWith("DOWN"));
    }

    @Test
    void health_redisBackend_noConnectionFactory_returnsDown() {
        sharedStateConfig.setBackend(Backend.REDIS);
        GatewayHealthIndicator indicator = new GatewayHealthIndicator(
                emptyDataSourceProvider(), emptyProvider(), properties);

        Health health = indicator.health();
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("DOWN: no connection factory", health.getDetails().get("redis"));
    }

    @Test
    void health_inMemoryBackend_skipsRedisCheck() {
        sharedStateConfig.setBackend(Backend.IN_MEMORY);
        GatewayHealthIndicator indicator = new GatewayHealthIndicator(
                emptyDataSourceProvider(), emptyProvider(), properties);

        Health health = indicator.health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("NOT_CONFIGURED (in-memory backend)", health.getDetails().get("redis"));
    }
}