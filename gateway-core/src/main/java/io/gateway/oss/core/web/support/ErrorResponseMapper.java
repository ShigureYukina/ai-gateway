package io.gateway.oss.core.web.support;

import io.gateway.oss.core.error.GatewayException;
import org.springframework.http.HttpStatus;

import java.util.concurrent.TimeoutException;

/**
 * 异常到 OpenAI 兼容错误响应字段的映射辅助类。
 */
public final class ErrorResponseMapper {

    private ErrorResponseMapper() {
    }

    public static int statusForError(Throwable error) {
        if (error instanceof GatewayException gatewayException) {
            return gatewayException.getStatus().value();
        }
        if (error instanceof TimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT.value();
        }
        return HttpStatus.BAD_GATEWAY.value();
    }

    public static String codeForError(Throwable error) {
        if (error instanceof GatewayException gatewayException) {
            return gatewayException.getCode();
        }
        if (error instanceof TimeoutException) {
            return "upstream_timeout";
        }
        return "upstream_error";
    }

    public static String errorMessageForError(Throwable error) {
        if (error instanceof GatewayException gatewayException) {
            return gatewayException.getMessage();
        }
        if (error instanceof TimeoutException) {
            return "Upstream timeout";
        }
        return error.getMessage() != null ? error.getMessage() : "Upstream provider error";
    }
}
