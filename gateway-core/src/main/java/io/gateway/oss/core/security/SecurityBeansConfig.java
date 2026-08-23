package io.gateway.oss.core.security;

import io.gateway.oss.core.config.ConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityBeansConfig {
    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserAccountService userAccountService(ConfigStore configStore,
                                                 ObjectMapper objectMapper,
                                                 PasswordService passwordService,
                                                 DirtyAccountFlushBuffer dirtyAccountFlushBuffer) {
        return new UserAccountService(configStore, objectMapper, passwordService, dirtyAccountFlushBuffer);
    }
}
