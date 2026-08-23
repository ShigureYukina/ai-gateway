package io.gateway.oss.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration
public class SchedulerConfig {

    @Bean(name = "boundedElasticScheduler", destroyMethod = "dispose")
    public Scheduler boundedElasticScheduler() {
        int threadCap = 10 * Runtime.getRuntime().availableProcessors();
        return Schedulers.newBoundedElastic(threadCap, 100000, "gateway-bounded");
    }
}
