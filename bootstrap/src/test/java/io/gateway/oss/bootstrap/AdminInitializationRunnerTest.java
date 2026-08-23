package io.gateway.oss.bootstrap;

import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.security.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminInitializationRunnerTest {

    @Test
    void shouldSkipWhenInitModeDisabled() {
        UserAccountService userAccountService = mock(UserAccountService.class);
        AdminInitializationRunner runner = new AdminInitializationRunner(new MockEnvironment(), userAccountService);

        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments(new String[0])));

        verifyNoInteractions(userAccountService);
    }

    @Test
    void shouldCreateDynamicAdminWhenInitModeEnabled() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(AdminInitializationRunner.INIT_ENABLED_PROPERTY, "true")
                .withProperty(AdminInitializationRunner.USERNAME_PROPERTY, "root-admin")
                .withProperty(AdminInitializationRunner.PASSWORD_PROPERTY, "Secret#123")
                .withProperty(AdminInitializationRunner.DISPLAY_NAME_PROPERTY, " Root Admin ")
                .withProperty(AdminInitializationRunner.EMAIL_PROPERTY, " Admin@Example.COM ");
        UserAccountService userAccountService = mock(UserAccountService.class);
        when(userAccountService.register(eq("root-admin"), eq("Secret#123"), eq("admin"), isNull(), isNull(), eq("Root Admin"), eq("Admin@Example.COM")))
                .thenReturn(Mono.just(UserAccount.create("root-admin", "hash", "admin", "gw-key")));

        AdminInitializationRunner runner = new AdminInitializationRunner(environment, userAccountService);

        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments(new String[0])));

        verify(userAccountService).register(eq("root-admin"), eq("Secret#123"), eq("admin"), isNull(), isNull(), eq("Root Admin"), eq("Admin@Example.COM"));
    }

    @Test
    void shouldFailWhenRequiredFieldsMissing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(AdminInitializationRunner.INIT_ENABLED_PROPERTY, "true")
                .withProperty(AdminInitializationRunner.USERNAME_PROPERTY, "root-admin");
        UserAccountService userAccountService = mock(UserAccountService.class);
        AdminInitializationRunner runner = new AdminInitializationRunner(environment, userAccountService);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments(new String[0])));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains(AdminInitializationRunner.PASSWORD_PROPERTY);
    }
}
