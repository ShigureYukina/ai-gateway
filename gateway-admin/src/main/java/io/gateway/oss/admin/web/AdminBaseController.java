package io.gateway.oss.admin.web;

import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import org.springframework.web.server.ServerWebExchange;

import java.util.ArrayList;
import java.util.List;

public abstract class AdminBaseController {

    protected final ClientAuthService clientAuthService;

    protected AdminBaseController(ClientAuthService clientAuthService) {
        this.clientAuthService = clientAuthService;
    }

    protected ClientPrincipal requireAdminAccess(String authorizationHeader) {
        ClientPrincipal principal = clientAuthService.authenticate(authorizationHeader);
        clientAuthService.requireAdmin(principal);
        return principal;
    }

    protected ClientPrincipal requireAdminAccess(ServerWebExchange exchange) {
        ClientPrincipal principal = InternalEndpointAuthFilter.requiredPrincipal(exchange);
        clientAuthService.requireAdmin(principal);
        return principal;
    }

    protected static String mask(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    protected static List<String> maskKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(keys.size());
        for (String key : keys) {
            result.add(mask(key));
        }
        return result;
    }

    protected static String maskClientKey(String clientKey) {
        return mask(clientKey);
    }
}
