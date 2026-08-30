package io.gateway.oss.core.security;

import io.gateway.oss.core.error.GatewayException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Shared validator for provider baseUrl values to prevent SSRF attacks.
 * Rejects internal/private/loopback addresses and DNS-rebinding attempts.
 */
@Component
public class BaseUrlValidator {

    private final boolean blockInternalUrls;

    public BaseUrlValidator(
            @Value("${gateway.security.block-internal-urls:true}") boolean blockInternalUrls) {
        this.blockInternalUrls = blockInternalUrls;
    }

    /**
     * Validates that the given baseUrl does not point to an internal/private address.
     * Throws GatewayException if validation fails.
     *
     * @param baseUrl the URL to validate, may be null or blank (skipped)
     */
    public void validate(String baseUrl) {
        if (!blockInternalUrls) return;
        if (baseUrl == null || baseUrl.isBlank()) return;

        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url", "Provider baseUrl is not a valid URI: " + baseUrl);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equals(scheme.toLowerCase(Locale.ROOT))
                && !"https".equals(scheme.toLowerCase(Locale.ROOT)))) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url", "Only http/https URLs are allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) return;
        String normalizedHost = normalizeHost(host);

        // Check hostname patterns first (fast, no DNS lookup)
        String lower = normalizedHost.toLowerCase(Locale.ROOT);
        checkHostname(lower);

        // Resolve DNS and check the resulting IP address (prevents DNS rebinding)
        try {
            InetAddress resolved = InetAddress.getByName(normalizedHost);
            checkResolvedAddress(resolved, normalizedHost);
        } catch (UnknownHostException e) {
            // Unresolvable hostname is not an SSRF risk by itself
            // (the actual HTTP call will fail anyway)
        }
    }

    private void checkHostname(String lower) {
        if (lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("::1")
                || lower.equals("0:0:0:0:0:0:0:1")
                || lower.equals("::ffff:127.0.0.1")
                || lower.equals("0.0.0.0")
                || lower.startsWith("10.")
                || lower.startsWith("192.168.")
                || is172Private(lower)
                || lower.startsWith("169.254.")
                || lower.startsWith("fe80:")
                || lower.startsWith("fc")
                || lower.startsWith("fd")
                || lower.endsWith(".internal")
                || lower.endsWith(".local")
                || lower.endsWith(".localhost")) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url", "Provider baseUrl must not point to internal/private addresses");
        }
    }

    private void checkResolvedAddress(InetAddress resolved, String originalHost) {
        byte[] bytes = resolved.getAddress();
        if (bytes == null) return;

        if (resolved.isAnyLocalAddress()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url",
                    "Provider baseUrl resolves to wildcard address (host=" + originalHost + ")");
        }

        if (resolved.isLoopbackAddress()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url",
                    "Provider baseUrl resolves to loopback address (host=" + originalHost + ")");
        }

        // Loopback: 127.x.x.x
        if (bytes.length == 4 && bytes[0] == 127) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url",
                    "Provider baseUrl resolves to loopback address (host=" + originalHost + ")");
        }

        // IPv6 loopback
        if (bytes.length == 16 && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0
                && bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0 && bytes[7] == 0
                && bytes[8] == 0 && bytes[9] == 0 && bytes[10] == 0 && bytes[11] == 0
                && bytes[12] == 0 && bytes[13] == 0 && bytes[14] == 0 && bytes[15] == 1) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url",
                    "Provider baseUrl resolves to IPv6 loopback address (host=" + originalHost + ")");
        }

        if (isIpv4MappedAddress(bytes) && isBlockedIpv4(bytes[12], bytes[13], bytes[14], bytes[15])) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url",
                    "Provider baseUrl resolves to blocked IPv4-mapped IPv6 address (host=" + originalHost + ")");
        }

        // Private: 10.x.x.x
        if (bytes.length == 4 && bytes[0] == 10) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url",
                    "Provider baseUrl resolves to private address (host=" + originalHost + ")");
        }

        // Private: 172.16-31.x.x
        if (bytes.length == 4 && bytes[0] == (byte) 172 && (bytes[1] & 0xFF) >= 16 && (bytes[1] & 0xFF) <= 31) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url",
                    "Provider baseUrl resolves to private address (host=" + originalHost + ")");
        }

        // Private: 192.168.x.x
        if (bytes.length == 4 && bytes[0] == (byte) 192 && bytes[1] == (byte) 168) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url",
                    "Provider baseUrl resolves to private address (host=" + originalHost + ")");
        }

        // Link-local: 169.254.x.x
        if (bytes.length == 4 && bytes[0] == (byte) 169 && bytes[1] == (byte) 254) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url",
                    "Provider baseUrl resolves to link-local address (host=" + originalHost + ")");
        }

        // 补充拦截 InetAddress 自带的 site-local / link-local 判定，覆盖更多 IPv4/IPv6 私网场景。
        if (resolved.isSiteLocalAddress()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url",
                    "Site-local addresses are not allowed (host=" + originalHost + ")");
        }
        if (resolved.isLinkLocalAddress()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST,
                    "invalid_base_url",
                    "Link-local addresses are not allowed (host=" + originalHost + ")");
        }

        // InetAddress#isSiteLocalAddress 对 IPv6 ULA(fc00::/7) 覆盖不可靠，因此显式补充范围判断。
        if (resolved instanceof Inet6Address) {
            if ((bytes[0] & 0xFE) == 0xFC) {
                throw new GatewayException(HttpStatus.BAD_REQUEST,
                        "invalid_base_url",
                        "ULA addresses are not allowed (host=" + originalHost + ")");
            }
        }
    }

    private boolean is172Private(String lower) {
        if (!lower.startsWith("172.")) return false;
        String[] parts = lower.split("\\.");
        if (parts.length < 2) return false;
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String normalizeHost(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private boolean isIpv4MappedAddress(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    private boolean isBlockedIpv4(byte b0, byte b1, byte b2, byte b3) {
        return b0 == 127
                || b0 == 10
                || b0 == 0
                || (b0 == 100 && (b1 & 0xFF) >= 64 && (b1 & 0xFF) <= 127)
                || (b0 == (byte) 172 && (b1 & 0xFF) >= 16 && (b1 & 0xFF) <= 31)
                || (b0 == (byte) 192 && b1 == (byte) 168)
                || (b0 == (byte) 169 && b1 == (byte) 254);
    }
}
