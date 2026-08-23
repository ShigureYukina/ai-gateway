package io.gateway.oss.bootstrap;

import io.gateway.oss.core.config.AuthConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.UserConfig;
import io.gateway.oss.core.security.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminInitializationStartupValidatorTest {

    @Test
    void shouldSkipWhenAuthDisabled() {
        GatewayProperties properties = gatewayProperties(false);
        UserAccountService userAccountService = mock(UserAccountService.class);
        AdminInitializationStartupValidator validator = new AdminInitializationStartupValidator(
                properties, new MockEnvironment(), userAccountService);

        assertDoesNotThrow(validator::validate);
        verifyNoInteractions(userAccountService);
    }

    @Test
    void shouldSkipWhenLocalProfileActive() {
        GatewayProperties properties = gatewayProperties(true);
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "local");
        environment.setActiveProfiles("local");
        UserAccountService userAccountService = mock(UserAccountService.class);
        AdminInitializationStartupValidator validator = new AdminInitializationStartupValidator(
                properties, environment, userAccountService);

        assertDoesNotThrow(validator::validate);
        verifyNoInteractions(userAccountService);
    }

    @Test
    void shouldSkipWhenInitModeEnabled() {
        GatewayProperties properties = gatewayProperties(true);
        MockEnvironment environment = new MockEnvironment()
                .withProperty(AdminInitializationRunner.INIT_ENABLED_PROPERTY, "true");
        UserAccountService userAccountService = mock(UserAccountService.class);
        AdminInitializationStartupValidator validator = new AdminInitializationStartupValidator(
                properties, environment, userAccountService);

        assertDoesNotThrow(validator::validate);
        verifyNoInteractions(userAccountService);
    }

    @Test
    void shouldFailInProdLikeEnvironmentWhenOnlyStaticAdminExists() {
        GatewayProperties properties = gatewayProperties(true);
        UserConfig staticAdmin = new UserConfig();
        staticAdmin.setPassword("plain-password");
        staticAdmin.setRole("admin");
        properties.getAuth().getUsers().put("admin", staticAdmin);

        UserAccountService userAccountService = mock(UserAccountService.class);
        when(userAccountService.hasDynamicAdmin()).thenReturn(Mono.just(false));

        AdminInitializationStartupValidator validator = new AdminInitializationStartupValidator(
                properties, new MockEnvironment(), userAccountService);

        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validate);
        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("不存在动态 admin 账户")
                .contains(AdminInitializationRunner.INIT_ENABLED_PROPERTY);
    }

    @Test
    void shouldPassInProdLikeEnvironmentWhenDynamicAdminExists() {
        GatewayProperties properties = gatewayProperties(true);
        UserAccountService userAccountService = mock(UserAccountService.class);
        when(userAccountService.hasDynamicAdmin()).thenReturn(Mono.just(true));

        AdminInitializationStartupValidator validator = new AdminInitializationStartupValidator(
                properties, new MockEnvironment(), userAccountService);

        assertDoesNotThrow(validator::validate);
    }

    private GatewayProperties gatewayProperties(boolean authEnabled) {
        GatewayProperties properties = new GatewayProperties();
        AuthConfig authConfig = new AuthConfig();
        authConfig.setEnabled(authEnabled);
        properties.setAuth(authConfig);
        return properties;
    }
}
