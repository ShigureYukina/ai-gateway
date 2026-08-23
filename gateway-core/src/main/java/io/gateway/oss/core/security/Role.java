package io.gateway.oss.core.security;

public enum Role {
    ADMIN("admin"),
    OPERATOR("operator"),
    VIEWER("viewer"),
    USER("user");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Role fromString(String s) {
        if (s == null) return USER;
        for (Role r : values()) {
            if (r.value.equals(s)) return r;
        }
        return USER;
    }

    public boolean isAtLeast(Role minimum) {
        return this.ordinal() <= minimum.ordinal();
    }
}
