package io.gateway.oss.core.security;

import io.gateway.oss.core.error.GatewayException;
import org.springframework.http.HttpStatus;

/**
 * JWT 验证异常，包装为 GatewayException 以统一错误响应格式。
 */
public class JwtValidationException extends GatewayException {

    public JwtValidationException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
