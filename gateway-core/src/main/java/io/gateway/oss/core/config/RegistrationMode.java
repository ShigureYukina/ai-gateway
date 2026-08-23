package io.gateway.oss.core.config;

import java.util.Locale;

public enum RegistrationMode {
    OPEN,
    RESTRICTED,
    DISABLED;

    public static RegistrationMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPEN;
        }
        return RegistrationMode.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
