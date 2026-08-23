package io.gateway.oss.core.contract.routing;

import java.util.List;

public record ProviderApiKeyPool(
        List<String> keys,
        List<Integer> weights
) {
    public ProviderApiKeyPool {
        keys = keys == null ? List.of() : List.copyOf(keys);
        weights = weights == null ? List.of() : List.copyOf(weights);
    }
}
