package io.gateway.oss.core.contract.routing;

import java.util.List;

/**
 * Abstraction for selecting routes from a candidate pool.
 * Decouples upstream layer from routing load-balancer implementation.
 */
public interface RouteSelector {

    boolean isEnabled();

    ResolvedRoute select(List<ResolvedRoute> candidateRoutes);

    List<ResolvedRoute> orderCandidatesByWrr(List<ResolvedRoute> healthyCandidates, String groupKey);
}
