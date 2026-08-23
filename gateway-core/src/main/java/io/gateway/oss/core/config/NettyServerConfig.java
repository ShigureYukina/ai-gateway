package io.gateway.oss.core.config;

import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class NettyServerConfig {

    @Bean
    public NettyServerCustomizer nettyServerCustomizer() {
        return httpServer -> httpServer
                .idleTimeout(Duration.ofSeconds(60))
                .forwarded(true);
    }
}
