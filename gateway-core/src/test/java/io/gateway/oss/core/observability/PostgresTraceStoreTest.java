package io.gateway.oss.core.observability;

import io.gateway.oss.core.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresTraceStoreTest {

    @Mock
    private JdbcTemplate jdbc;

    private PostgresTraceStore store;
    private String namespace;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSharedState().setKeyPrefix("gateway");
        namespace = "gateway";
        store = new PostgresTraceStore(jdbc, properties);
    }

    @Test
    void save_executesInsertOnConflict() {
        Instant now = Instant.now();
        TraceRecord record = new TraceRecord("req-1", "cli***45", "gpt-4o-mini", "openai", "route-1", "default", 200, "non-streaming", 123L, null, "masked-[REDACTED]-req", "masked-[REDACTED]-res", now);

        store.save(record);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq(namespace), eq("req-1"), eq("cli***45"), eq("gpt-4o-mini"), eq("openai"), eq("route-1"), eq("default"), eq(200), eq("non-streaming"), eq(123L), eq(Timestamp.from(now)));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO request_trace"), "SQL should contain INSERT INTO request_trace");
        assertTrue(sql.contains("ON CONFLICT"), "SQL should contain ON CONFLICT");
        assertFalse(sql.contains("request_body"), "Successful trace SQL should not persist bodies");
    }

    @Test
    void save_persistsFailureBodiesAsProvided_withoutReadTimeMasking() {
        Instant now = Instant.now();
        TraceRecord record = new TraceRecord("req-masked", "cli***45", "gpt-4o-mini", "openai", "route-1", "default", 500, "non-streaming", 123L, "upstream_error",
                "{\"messages\":[{\"content\":\"[REDACTED]\"}]}",
                "{\"output\":\"[REDACTED]\"}", now);

        store.save(record);

        verify(jdbc).update(anyString(), eq(namespace), eq("req-masked"), eq("cli***45"), eq("gpt-4o-mini"), eq("openai"), eq("route-1"), eq("default"), eq(500), eq("non-streaming"), eq(123L), eq("upstream_error"),
                eq("{\"messages\":[{\"content\":\"[REDACTED]\"}]}"),
                eq("{\"output\":\"[REDACTED]\"}"), eq(Timestamp.from(now)));
    }

    @Test
    void save_whenTimestampNull_usesCurrentInstant() {
        TraceRecord record = new TraceRecord("req-2", null, null, null, null, null, null, null, null, null, "body", "resp", null);

        store.save(record);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object> paramCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), eq(namespace), eq("req-2"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq("body"), eq("resp"), paramCaptor.capture());
        Object tsParam = paramCaptor.getValue();
        assertInstanceOf(Timestamp.class, tsParam, "Should pass a Timestamp for null instant");
        // The timestamp should be close to now
        Instant captured = ((Timestamp) tsParam).toInstant();
        assertTrue(captured.isBefore(Instant.now().plusSeconds(1)), "Timestamp should be near current time");
    }

    @Test
    void getByRequestId_returnsRecordWhenFound() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString("request_id")).thenReturn("req-1");
        when(rs.getString("client_id")).thenReturn("cli***45");
        when(rs.getString("model")).thenReturn("gpt-4o-mini");
        when(rs.getString("provider")).thenReturn("openai");
        when(rs.getString("route_id")).thenReturn("route-1");
        when(rs.getString("scene")).thenReturn("default");
        when(rs.getObject("status")).thenReturn(200);
        when(rs.getString("stream_mode")).thenReturn("non-streaming");
        when(rs.getObject("latency_ms")).thenReturn(123L);
        when(rs.getString("error_message")).thenReturn(null);
        when(rs.getString("request_body")).thenReturn("body-req");
        when(rs.getString("response_body")).thenReturn("body-res");
        Instant ts = Instant.parse("2025-01-01T00:00:00Z");
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(ts));

        when(jdbc.query(argThat(sql -> ((String) sql).contains("FROM request_trace WHERE namespace = ? AND request_id = ?")), any(ResultSetExtractor.class), eq(namespace), eq("req-1")))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<TraceRecord> extractor = invocation.getArgument(1);
                    return extractor.extractData(rs);
                });

        TraceRecord result = store.getByRequestId("req-1");

        assertNotNull(result, "Should return a record when found");
        assertEquals("req-1", result.requestId());
        assertEquals("cli***45", result.clientId());
        assertEquals("gpt-4o-mini", result.model());
        assertEquals("openai", result.provider());
        assertEquals("route-1", result.routeId());
        assertEquals("default", result.scene());
        assertEquals(200, result.status());
        assertEquals("non-streaming", result.streamMode());
        assertEquals(123L, result.latencyMs());
        assertEquals("body-req", result.requestBody());
        assertEquals("body-res", result.responseBody());
        assertEquals(ts, result.timestamp());
    }

    @Test
    void getByRequestId_returnsNullWhenNotFound() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);

        when(jdbc.query(argThat(sql -> ((String) sql).contains("FROM request_trace WHERE namespace = ? AND request_id = ?")), any(ResultSetExtractor.class), eq(namespace), eq("missing")))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<TraceRecord> extractor = invocation.getArgument(1);
                    return extractor.extractData(rs);
                });

        TraceRecord result = store.getByRequestId("missing");

        assertNull(result, "Should return null when no record found");
    }

    @Test
    void getRecent_returnsRecordsInOrder() {
        Instant ts1 = Instant.parse("2025-01-02T00:00:00Z");
        Instant ts2 = Instant.parse("2025-01-01T00:00:00Z");

        List<TraceRecord> expected = List.of(
                new TraceRecord("req-1", "c1", "m1", "p1", "r1", "s1", 200, "non-streaming", 10L, null, "b1", "r1", ts1),
                new TraceRecord("req-2", "c2", "m2", "p2", "r2", "s2", 500, "streaming", 20L, "boom", "b2", "r2", ts2)
        );

        when(jdbc.query(argThat(sql -> ((String) sql).contains("FROM request_trace WHERE namespace = ? ORDER BY created_at DESC LIMIT ?")), any(RowMapper.class), eq(namespace), eq(10)))
                .thenReturn(expected);

        List<TraceRecord> result = store.getRecent(10);

        assertEquals(2, result.size(), "Should return 2 records");
        assertEquals("req-1", result.get(0).requestId());
        assertEquals("req-2", result.get(1).requestId());
    }

    @Test
    void getRecent_respectsLimitParam() {
        when(jdbc.query(argThat(sql -> ((String) sql).contains("FROM request_trace WHERE namespace = ? ORDER BY created_at DESC LIMIT ?")), any(RowMapper.class), eq(namespace), eq(5)))
                .thenReturn(Collections.emptyList());

        store.getRecent(5);

        verify(jdbc).query(contains("LIMIT ?"), any(RowMapper.class), eq(namespace), eq(5));
    }

    @Test
    void getRecent_returnsEmptyList() {
        when(jdbc.query(argThat(sql -> ((String) sql).contains("FROM request_trace WHERE namespace = ? ORDER BY created_at DESC LIMIT ?")), any(RowMapper.class), eq(namespace), anyInt()))
                .thenReturn(Collections.emptyList());

        List<TraceRecord> result = store.getRecent(10);

        assertNotNull(result, "Should return a list, not null");
        assertTrue(result.isEmpty(), "Should return empty list when no records");
    }
}
