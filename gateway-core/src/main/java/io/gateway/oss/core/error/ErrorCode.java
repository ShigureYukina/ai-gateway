package io.gateway.oss.core.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    // Auth / Security
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "unauthorized"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),
    FORBIDDEN_MODEL(HttpStatus.FORBIDDEN, "forbidden_model"),
    FORBIDDEN_SCENE(HttpStatus.FORBIDDEN, "forbidden_scene"),
    ACCOUNT_FROZEN(HttpStatus.FORBIDDEN, "account_frozen"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "invalid_credentials"),
    INVALID_CLIENT(HttpStatus.UNAUTHORIZED, "invalid_client"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "invalid_token"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "token_expired"),
    TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "token_revoked"),
    INVALID_TOKEN_TYPE(HttpStatus.UNAUTHORIZED, "invalid_token_type"),
    AUTH_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "auth_disabled"),
    INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "invalid_password"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "username_taken"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "user_not_found"),
    OLD_PASSWORD_WRONG(HttpStatus.BAD_REQUEST, "old_password_wrong"),
    INVALID_ROLE(HttpStatus.BAD_REQUEST, "invalid_role"),
    KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "key_not_found"),
    MAINTENANCE_MODE(HttpStatus.SERVICE_UNAVAILABLE, "maintenance_mode"),

    // Request / Routing
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request"),
    UNKNOWN_MODEL(HttpStatus.BAD_REQUEST, "unknown_model"),
    STREAM_NOT_SUPPORTED(HttpStatus.NOT_IMPLEMENTED, "stream_not_supported"),
    MAX_TOKENS_EXCEEDED(HttpStatus.BAD_REQUEST, "max_tokens_exceeded"),

    // Rate Limiting / Quota
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "rate_limited"),
    CONCURRENT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "concurrent_limit_exceeded"),
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "quota_exceeded"),
    MONTHLY_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "monthly_quota_exceeded"),
    BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "budget_exceeded"),
    MONTHLY_BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "monthly_budget_exceeded"),
    TPM_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "tpm_exceeded"),
    EMERGENCY_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "emergency_rate_limited"),

    // Upstream
    CIRCUIT_BREAKER_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "circuit_breaker_open"),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout"),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "upstream_error"),

    // Config
    VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "version_not_found"),
    INVALID_CONFIG_TYPE(HttpStatus.BAD_REQUEST, "invalid_config_type"),
    INVALID_SYSTEM_KEY(HttpStatus.BAD_REQUEST, "invalid_system_key"),
    ROLLBACK_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "rollback_failed"),
    CONFLICT(HttpStatus.CONFLICT, "conflict"),

    // General
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "bad_request"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not_found");

    private final HttpStatus httpStatus;
    private final String code;

    ErrorCode(HttpStatus httpStatus, String code) {
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public HttpStatus status() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public GatewayException exception() {
        return new GatewayException(httpStatus, code, code);
    }

    public GatewayException exception(String message) {
        return new GatewayException(httpStatus, code, message);
    }

    public static ErrorCode fromCode(String code) {
        for (ErrorCode ec : values()) {
            if (ec.code.equals(code)) {
                return ec;
            }
        }
        return null;
    }
}
