package io.gateway.oss.core.config;

import jakarta.validation.constraints.NotBlank;

import java.time.Duration;

public class JwtConfig {

    @NotBlank(message = "JWT secret must be configured via gateway.auth.jwt.secret or GATEWAY_JWT_SECRET env var")
    private String secret;
    private Duration accessExpiration = Duration.ofMinutes(30);
    private Duration refreshExpiration = Duration.ofHours(24);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getAccessExpiration() {
        return accessExpiration;
    }

    public void setAccessExpiration(Duration accessExpiration) {
        this.accessExpiration = accessExpiration;
    }

    public Duration getRefreshExpiration() {
        return refreshExpiration;
    }

    public void setRefreshExpiration(Duration refreshExpiration) {
        this.refreshExpiration = refreshExpiration;
    }
}
