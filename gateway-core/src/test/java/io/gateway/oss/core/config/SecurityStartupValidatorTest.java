package io.gateway.oss.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityStartupValidatorTest {

    @Test
    void shouldPassWithValidSecret() {
        GatewayProperties properties = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        auth.setEnabled(true);
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("super-secret-key-that-is-at-least-32-chars-long");
        auth.setJwt(jwt);
        properties.setAuth(auth);

        SecurityStartupValidator validator = new SecurityStartupValidator(properties, new MockEnvironment());
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldThrowWhenSecretTooShort() {
        GatewayProperties properties = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        auth.setEnabled(true);
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("short"); // < 32 chars
        auth.setJwt(jwt);
        properties.setAuth(auth);

        SecurityStartupValidator validator = new SecurityStartupValidator(properties, new MockEnvironment());
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void shouldPassWhenAuthDisabled() {
        GatewayProperties properties = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        auth.setEnabled(false);
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("short"); // < 32 chars but auth disabled
        auth.setJwt(jwt);
        properties.setAuth(auth);

        SecurityStartupValidator validator = new SecurityStartupValidator(properties, new MockEnvironment());
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldPassWhenSecretExactly32Chars() {
        GatewayProperties properties = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        auth.setEnabled(true);
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("a".repeat(32)); // exactly 32 chars
        auth.setJwt(jwt);
        properties.setAuth(auth);

        SecurityStartupValidator validator = new SecurityStartupValidator(properties, new MockEnvironment());
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldThrowWhenSecret31Chars() {
        GatewayProperties properties = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        auth.setEnabled(true);
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("a".repeat(31)); // 31 chars < 32
        auth.setJwt(jwt);
        properties.setAuth(auth);

        SecurityStartupValidator validator = new SecurityStartupValidator(properties, new MockEnvironment());
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void shouldPassWhenAuthConfigIsNull() {
        GatewayProperties properties = new GatewayProperties();
        properties.setAuth(null);

        SecurityStartupValidator validator = new SecurityStartupValidator(properties, new MockEnvironment());
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldThrowWhenDangerousActuatorEndpointsExposed() {
        GatewayProperties properties = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        auth.setEnabled(true);
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("super-secret-key-that-is-at-least-32-chars-long");
        auth.setJwt(jwt);
        properties.setAuth(auth);

        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "health,info,env,beans,configprops");

        SecurityStartupValidator validator = new SecurityStartupValidator(properties, environment);
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void shouldPassWhenOnlySafeActuatorEndpointsExposed() {
        GatewayProperties properties = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        auth.setEnabled(true);
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("super-secret-key-that-is-at-least-32-chars-long");
        auth.setJwt(jwt);
        properties.setAuth(auth);

        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "health,info");

        SecurityStartupValidator validator = new SecurityStartupValidator(properties, environment);
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldThrowWhenUsingDevDefaultJwtSecretWithAuthEnabled() {
        GatewayProperties properties = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        auth.setEnabled(true);
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("dev-secret-key-at-least-32-chars-long");
        auth.setJwt(jwt);
        properties.setAuth(auth);

        SecurityStartupValidator validator = new SecurityStartupValidator(properties, new MockEnvironment());
        assertThrows(IllegalStateException.class, validator::validate);
    }
}
