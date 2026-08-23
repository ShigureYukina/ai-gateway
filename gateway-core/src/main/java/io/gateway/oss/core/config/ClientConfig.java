package io.gateway.oss.core.config;

import io.gateway.oss.core.contract.ClientConfigView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ClientConfig implements ClientConfigView {

    private boolean enabled = true;
    private Set<String> allowedModels = new HashSet<>();
    private Set<String> allowedScenes = new HashSet<>();
    private Map<String, String> modelScenes = new HashMap<>();
    private ClientDefaults defaults = new ClientDefaults();
    private ClientCapabilities capabilities = new ClientCapabilities();
    private ClientLimits limits = new ClientLimits();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> getAllowedModels() {
        return allowedModels;
    }

    public void setAllowedModels(Set<String> allowedModels) {
        this.allowedModels = allowedModels;
    }

    public Set<String> getAllowedScenes() {
        return allowedScenes;
    }

    public void setAllowedScenes(Set<String> allowedScenes) {
        this.allowedScenes = allowedScenes;
    }

    public Map<String, String> getModelScenes() {
        return modelScenes;
    }

    public void setModelScenes(Map<String, String> modelScenes) {
        this.modelScenes = modelScenes;
    }

    public ClientDefaults getDefaults() {
        return defaults;
    }

    public void setDefaults(ClientDefaults defaults) {
        this.defaults = defaults;
    }

    public ClientCapabilities getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(ClientCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    public ClientLimits getLimits() {
        return limits;
    }

    public void setLimits(ClientLimits limits) {
        this.limits = limits;
    }
}
