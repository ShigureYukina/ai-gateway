package io.gateway.oss.admin.web;

import io.gateway.oss.core.security.AuthorizationService;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.util.RedisStoreUtils;

import java.time.LocalDate;

public abstract class InternalBaseController {

    protected final ClientAuthService clientAuthService;

    protected final AuthorizationService authorizationService;

    protected InternalBaseController(ClientAuthService clientAuthService, AuthorizationService authorizationService) {
        this.clientAuthService = clientAuthService;
        this.authorizationService = authorizationService;
    }

    protected ClientPrincipal requireSystemAccess(String authorization) {
        var principal = clientAuthService.authenticate(authorization);
        authorizationService.requireSystemView(principal);
        return principal;
    }

    protected static String normalized(String value) {
        return RedisStoreUtils.normalized(value);
    }

    protected static LocalDate resolveDay(String day) {
        return RedisStoreUtils.resolveDay(day);
    }
}
