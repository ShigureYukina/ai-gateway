package io.gateway.oss.core.security;

import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.error.GatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationServiceTest {

    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new AuthorizationService();
    }

    private ClientPrincipal principal(String role) {
        return new ClientPrincipal("cid", null, role);
    }

    // --- requireRole ---

    @Test
    void requireRole_nullPrincipal_shouldThrow403() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.requireRole(null, "admin"));
        assertEquals(403, ex.getStatus().value());
        assertEquals("forbidden", ex.getCode());
    }

    @Test
    void requireRole_matchingRole_shouldPass() {
        assertDoesNotThrow(() -> service.requireRole(principal("admin"), "admin"));
    }

    @Test
    void requireRole_nonMatchingRole_shouldThrow403() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.requireRole(principal("viewer"), "admin"));
        assertEquals(403, ex.getStatus().value());
    }

    // --- requirePermission ---

    @Test
    void requirePermission_nullPrincipal_shouldThrow403() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.requirePermission(null, "admin_full"));
        assertEquals(403, ex.getStatus().value());
        assertEquals("forbidden", ex.getCode());
    }

    @Test
    void requirePermission_adminFull_admin_shouldPass() {
        assertDoesNotThrow(() -> service.requirePermission(principal("admin"), "admin_full"));
    }

    @Test
    void requirePermission_adminFull_operator_shouldThrow403() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.requirePermission(principal("operator"), "admin_full"));
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    void requirePermission_viewSystem_admin_shouldPass() {
        assertDoesNotThrow(() -> service.requirePermission(principal("admin"), "view_system"));
    }

    @Test
    void requirePermission_viewSystem_operator_shouldPass() {
        assertDoesNotThrow(() -> service.requirePermission(principal("operator"), "view_system"));
    }

    @Test
    void requirePermission_manageSystem_viewer_shouldThrow403() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.requirePermission(principal("viewer"), "manage_system"));
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    void requirePermission_unknownPermission_shouldThrow500() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.requirePermission(principal("admin"), "nonexistent"));
        assertEquals(500, ex.getStatus().value());
        assertEquals("internal_error", ex.getCode());
    }

    // --- hasRole ---

    @Test
    void hasRole_nullPrincipal_shouldReturnFalse() {
        assertFalse(service.hasRole(null, "admin"));
    }

    @Test
    void hasRole_matching_shouldReturnTrue() {
        assertTrue(service.hasRole(principal("admin"), "admin"));
    }

    @Test
    void hasRole_nonMatching_shouldReturnFalse() {
        assertFalse(service.hasRole(principal("viewer"), "admin"));
    }
}
