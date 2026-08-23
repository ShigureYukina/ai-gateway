package io.gateway.oss.core.config;

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

public class AuthConfig {

    private JwtConfig jwt = new JwtConfig();
    private Map<String, UserConfig> users = new HashMap<>();
    private boolean enabled = false;
    private String registrationMode = "restricted";
    private AuthRegistrationConfig registration = new AuthRegistrationConfig();

    public JwtConfig getJwt() {
        return jwt;
    }

    public void setJwt(JwtConfig jwt) {
        this.jwt = jwt;
    }

    public Map<String, UserConfig> getUsers() {
        return users;
    }

    public void setUsers(Map<String, UserConfig> users) {
        this.users = users;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRegistrationMode() {
        return registrationMode;
    }

    public void setRegistrationMode(String registrationMode) {
        if (registrationMode == null || registrationMode.isBlank()) {
            this.registrationMode = "restricted";
            return;
        }
        this.registrationMode = registrationMode.trim().toLowerCase(Locale.ROOT);
    }

    public AuthRegistrationConfig getRegistration() {
        return registration;
    }

    public void setRegistration(AuthRegistrationConfig registration) {
        this.registration = registration;
    }

    public RegistrationMode registrationMode() {
        return RegistrationMode.from(registrationMode);
    }

    public boolean isRegistrationRestricted() {
        return registrationMode() == RegistrationMode.RESTRICTED;
    }
}
