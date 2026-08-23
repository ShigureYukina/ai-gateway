package io.gateway.oss.core.upstream;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryProviderRuntimeStateStore implements ProviderRuntimeStateStore {

    private final ConcurrentHashMap<String, ProviderRuntimeState> states = new ConcurrentHashMap<>();

    @Override
    public ProviderRuntimeState get(String provider) {
        return states.getOrDefault(provider, ProviderRuntimeState.unknown());
    }

    @Override
    public void save(String provider, ProviderRuntimeState state) {
        states.put(provider, state);
    }

    @Override
    public Map<String, ProviderRuntimeState> getAll() {
        return new LinkedHashMap<>(states);
    }

    public void resetForTests() {
        states.clear();
    }
}
