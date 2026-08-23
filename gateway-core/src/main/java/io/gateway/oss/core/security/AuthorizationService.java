package io.gateway.oss.core.security;

import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.error.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class AuthorizationService {

    private static final Map<String, Set<Role>> PERMISSION_ROLES = Map.of(
            "admin_full", Set.of(Role.ADMIN),
            "manage_system", Set.of(Role.ADMIN),
            "view_system", Set.of(Role.ADMIN, Role.OPERATOR, Role.VIEWER)
    );

    public void requireRole(ClientPrincipal principal, String... allowedRoles) {
        if (principal == null) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "forbidden", "Authentication required");
        }
        for (String allowed : allowedRoles) {
            if (allowed.equals(principal.role())) {
                return;
            }
        }
        throw new GatewayException(HttpStatus.FORBIDDEN, "forbidden",
                "Requires one of roles: " + String.join(", ", allowedRoles));
    }

    public void requireRole(ClientPrincipal principal, Role... allowedRoles) {
        if (principal == null) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "forbidden", "Authentication required");
        }
        Role actual = Role.fromString(principal.role());
        for (Role allowed : allowedRoles) {
            if (allowed == actual) {
                return;
            }
        }
        throw new GatewayException(HttpStatus.FORBIDDEN, "forbidden",
                "Requires one of roles: " + java.util.Arrays.toString(allowedRoles));
    }

    public void requirePermission(ClientPrincipal principal, String permission) {
        if (principal == null) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "forbidden", "Authentication required");
        }
        Set<Role> allowedRoles = PERMISSION_ROLES.get(permission);
        if (allowedRoles == null) {
            throw new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                    "Unknown permission: " + permission);
        }
        Role actual = Role.fromString(principal.role());
        if (!allowedRoles.contains(actual)) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "forbidden",
                    "Permission '" + permission + "' requires one of: " + allowedRoles);
        }
    }

    public void requireSystemView(ClientPrincipal principal) {
        requirePermission(principal, "view_system");
    }

    public void requireSystemManage(ClientPrincipal principal) {
        requirePermission(principal, "manage_system");
    }

    public void requireAdminFull(ClientPrincipal principal) {
        requirePermission(principal, "admin_full");
    }

    public boolean hasRole(ClientPrincipal principal, String role) {
        if (principal == null) {
            return false;
        }
        return role.equals(principal.role());
    }

    public boolean isAdminOrOperator(ClientPrincipal principal) {
        if (principal == null) {
            return false;
        }
        Role actual = Role.fromString(principal.role());
        return actual == Role.ADMIN || actual == Role.OPERATOR;
    }
}
