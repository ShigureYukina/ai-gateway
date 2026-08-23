package io.gateway.oss.core.config;

/**
 * 链路追踪配置。
 * <p>
 * 从 {@link GatewayProperties} 内部类提取为独立顶层类，
 * 供 {@link io.gateway.oss.core.contract.config.SystemConfigView} 等契约接口引用。
 * </p>
 */
public class TraceConfig {

    private boolean enabled = false;
    private int maxBodySize = 16384;
    private double sampleRate = 1.0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxBodySize() {
        return maxBodySize;
    }

    public void setMaxBodySize(int maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(double sampleRate) {
        this.sampleRate = sampleRate;
    }
}
