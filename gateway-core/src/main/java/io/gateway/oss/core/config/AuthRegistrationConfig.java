package io.gateway.oss.core.config;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

public class AuthRegistrationConfig {

    private Set<String> allowedModels = new HashSet<>(Set.of("gpt-4o-mini"));
    private Set<String> allowedScenes = new HashSet<>(Set.of("default-chat"));
    private ClientDefaults defaults = defaultDefaults();
    private ClientCapabilities capabilities = defaultCapabilities();
    private ClientLimits limits = defaultLimits();

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

    public ClientConfig toClientConfig() {
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setEnabled(true);
        clientConfig.setAllowedModels(allowedModels == null ? new HashSet<>() : new HashSet<>(allowedModels));
        clientConfig.setAllowedScenes(allowedScenes == null ? new HashSet<>() : new HashSet<>(allowedScenes));

        ClientDefaults clonedDefaults = new ClientDefaults();
        if (defaults != null) {
            clonedDefaults.setScene(defaults.getScene());
            clonedDefaults.setTemperature(defaults.getTemperature());
            clonedDefaults.setMaxTokens(defaults.getMaxTokens());
        }
        clientConfig.setDefaults(clonedDefaults);

        ClientCapabilities clonedCapabilities = new ClientCapabilities();
        if (capabilities != null) {
            clonedCapabilities.setStreaming(capabilities.isStreaming());
        }
        clientConfig.setCapabilities(clonedCapabilities);

        ClientLimits clonedLimits = new ClientLimits();
        if (limits != null) {
            clonedLimits.setMaxTokens(limits.getMaxTokens());
            clonedLimits.setDailyTokens(limits.getDailyTokens());
            clonedLimits.setMonthlyTokens(limits.getMonthlyTokens());
            clonedLimits.setTokensPerMinute(limits.getTokensPerMinute());
            clonedLimits.setDailyCost(limits.getDailyCost());
            clonedLimits.setMonthlyCost(limits.getMonthlyCost());
            clonedLimits.setRequestsPerWindow(limits.getRequestsPerWindow());
            clonedLimits.setWindow(limits.getWindow());
        }
        clientConfig.setLimits(clonedLimits);

        return clientConfig;
    }

    private static ClientDefaults defaultDefaults() {
        ClientDefaults defaults = new ClientDefaults();
        defaults.setScene("default-chat");
        defaults.setTemperature(0.7d);
        defaults.setMaxTokens(256);
        return defaults;
    }

    private static ClientCapabilities defaultCapabilities() {
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setStreaming(true);
        return capabilities;
    }

    private static ClientLimits defaultLimits() {
        ClientLimits limits = new ClientLimits();
        limits.setMaxTokens(512);
        limits.setDailyTokens(10_000L);
        limits.setMonthlyTokens(300_000L);
        limits.setTokensPerMinute(10_000L);
        limits.setDailyCost(new BigDecimal("5.0"));
        limits.setMonthlyCost(new BigDecimal("100.0"));
        return limits;
    }
}
