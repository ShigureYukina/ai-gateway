package io.gateway.oss.core.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SecurityStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupValidator.class);
    private static final String DEV_DEFAULT_JWT_SECRET = "dev-secret-key-at-least-32-chars-long";
    private static final Set<String> DANGEROUS_ACTUATOR_ENDPOINTS = Set.of(
            "env", "beans", "configprops", "heapdump", "threaddump", "loggers");

    private final GatewayProperties properties;
    private final Environment environment;

    public SecurityStartupValidator(GatewayProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        validateActuatorExposure();

        AuthConfig auth = properties.getAuth();
        if (auth == null || !auth.isEnabled()) {
            return;
        }
        JwtConfig jwt = auth.getJwt();
        if (jwt != null) {
            String secret = jwt.getSecret();
            if (secret != null && !secret.isBlank() && secret.length() < 32) {
                throw new IllegalStateException(
                        "GATEWAY_JWT_SECRET must be at least 32 characters long");
            }
            if (DEV_DEFAULT_JWT_SECRET.equals(secret) && !isLocalOrTestProfile()) {
                throw new IllegalStateException(
                        "GATEWAY_JWT_SECRET must not use the development default secret");
            }
            if (secret == null || secret.isBlank()) {
                log.warn("GATEWAY_JWT_SECRET is not set — using default (insecure for production)");
            }
        }
        log.info("startup_security_validation_passed");
    }

    private void validateActuatorExposure() {
        String includeRaw = environment.getProperty("management.endpoints.web.exposure.include", "");
        Set<String> included = Arrays.stream(includeRaw.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        Set<String> dangerousIncluded = included.stream()
                .filter(DANGEROUS_ACTUATOR_ENDPOINTS::contains)
                .collect(Collectors.toSet());

        if (!dangerousIncluded.isEmpty()) {
            String message = "Dangerous actuator endpoints exposed: " + dangerousIncluded;
            log.warn(message);
            if (!isTestProfile()) {
                throw new IllegalStateException(message);
            }
        }
    }

    private boolean isTestProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch("test"::equalsIgnoreCase);
    }

    private boolean isLocalOrTestProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "local".equalsIgnoreCase(profile) || "test".equalsIgnoreCase(profile));
    }
}
