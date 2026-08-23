package io.gateway.oss.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class OnRedisOrHybridCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String backend = context.getEnvironment().getProperty("gateway.shared-state.backend");
        boolean match = "redis".equals(backend) || "hybrid".equals(backend);
        return new ConditionOutcome(match, "gateway.shared-state.backend=" + backend + " match=" + match);
    }
}
