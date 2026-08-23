package io.gateway.oss.core.security;

import io.gateway.oss.core.error.GatewayException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaseUrlValidatorTest {

    private final BaseUrlValidator validator = new BaseUrlValidator(true);
    private final BaseUrlValidator permissiveValidator = new BaseUrlValidator(false);

    // ─── blocked hostnames ───

    @Test
    void shouldRejectLocalhost() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> validator.validate("http://localhost:8080/api"));
        assertEquals("invalid_base_url", ex.getCode());
    }

    @Test
    void shouldReject127() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://127.0.0.1/v1/models"));
    }

    @Test
    void shouldRejectIpv6Loopback() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://[::1]/v1/models"));
    }

    @Test
    void shouldRejectExpandedIpv6Loopback() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://[0:0:0:0:0:0:0:1]/v1/models"));
    }

    @Test
    void shouldRejectIpv4MappedIpv6Loopback() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://[::ffff:127.0.0.1]/v1/models"));
    }

    @Test
    void shouldRejectIpv6LinkLocal() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://[fe80::1]/v1/models"));
    }

    @Test
    void shouldRejectIpv6Ula() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://[fd00::1]/v1/models"));
    }

    @Test
    void shouldReject0000() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://0.0.0.0/v1/models"));
    }

    @Test
    void shouldReject10x() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://10.0.0.1/v1/models"));
    }

    @Test
    void shouldReject192_168() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://192.168.1.1/v1/models"));
    }

    @Test
    void shouldReject172_16_to_31() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://172.16.0.1/v1/models"));
        assertThrows(GatewayException.class,
                () -> validator.validate("http://172.20.5.1/v1/models"));
        assertThrows(GatewayException.class,
                () -> validator.validate("http://172.31.255.255/v1/models"));
    }

    @Test
    void shouldReject169_254() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void shouldRejectInternalSuffix() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://service.internal/v1/models"));
    }

    @Test
    void shouldRejectLocalSuffix() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://myhost.local/v1/models"));
    }

    @Test
    void shouldRejectLocalhostSuffix() {
        assertThrows(GatewayException.class,
                () -> validator.validate("http://sub.localhost/v1/models"));
    }

    // ─── allowed hostnames ───

    @Test
    void shouldAllowPublicUrl() {
        assertDoesNotThrow(() -> validator.validate("https://api.openai.com/v1/models"));
    }

    @Test
    void shouldAllowPublicUrl2() {
        assertDoesNotThrow(() -> validator.validate("https://your-qwen-endpoint.example.com/v1/models"));
    }

    @Test
    void shouldAllow172_1_to_15() {
        // 172.1-15 are NOT private per RFC 1918
        assertDoesNotThrow(() -> validator.validate("http://172.1.0.1/v1/models"));
    }

    // ─── null/blank ───

    @Test
    void shouldPassWhenNull() {
        assertDoesNotThrow(() -> validator.validate(null));
    }

    @Test
    void shouldPassWhenBlank() {
        assertDoesNotThrow(() -> validator.validate(""));
        assertDoesNotThrow(() -> validator.validate("   "));
    }

    // ─── permissive mode ───

    @Test
    void shouldPassInternalWhenBlockDisabled() {
        assertDoesNotThrow(() -> permissiveValidator.validate("http://localhost:8080/api"));
        assertDoesNotThrow(() -> permissiveValidator.validate("http://127.0.0.1/v1/models"));
        assertDoesNotThrow(() -> permissiveValidator.validate("http://10.0.0.1/v1/models"));
    }

    // ─── invalid URI ───

    @Test
    void shouldRejectInvalidUri() {
        assertThrows(GatewayException.class,
                () -> validator.validate("not a valid uri ::::"));
    }
}
