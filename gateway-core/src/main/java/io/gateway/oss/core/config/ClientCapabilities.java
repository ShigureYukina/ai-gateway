package io.gateway.oss.core.config;

public class ClientCapabilities {

    private boolean streaming = true;

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }
}
