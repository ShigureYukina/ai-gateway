package io.gateway.oss.core.config;

public class SharedStateConfig {

    private Backend backend = Backend.IN_MEMORY;
    private String keyPrefix = "gateway";

    public Backend getBackend() {
        return backend;
    }

    public void setBackend(Backend backend) {
        this.backend = backend;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
