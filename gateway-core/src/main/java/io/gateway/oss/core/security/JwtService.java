package io.gateway.oss.core.security;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.JwtConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JWT 生成与验证服务。
 *
 * <p>access token 包含 claims: {@code sub}（username）、可选 {@code clientId}、{@code scope}、
 * {@code typ}={@code "access"}、{@code role}（admin/user）、{@code iat}/{@code exp}；</p>
 * <p>refresh token 含 {@code sub}（username）、可选 {@code clientId} 和 {@code typ=refresh}。</p>
 */
@Service
public class JwtService {

    private final JwtConfig jwtConfig;
    private final SecretKey signingKey;

    /**
     * Lightweight token→Claims cache. Reduces repeated HMAC verification on hot-path.
     * Caffeine cache with bounded size and TTL; entries expire naturally with token TTL;
     * revocation caught by tokenVersion check.
     */
    private final Cache<String, Claims> tokenCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    public JwtService(GatewayProperties properties) {
        this.jwtConfig = properties.getAuth().getJwt();
        String secret = jwtConfig.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured. Set gateway.auth.jwt.secret or GATEWAY_JWT_SECRET env var. "
                    + "Application cannot start without a secure JWT signing key.");
        }
        this.signingKey = resolveKey(secret);
    }

    /**
     * 生成 access token（带 role claim）。
     */
    public String generateAccessToken(String username, List<String> scopes, String role) {
        return generateAccessToken(username, username, scopes, role, 0);
    }

    /**
     * 生成 access token（带 role claim 与 tokenVersion）。
     */
    public String generateAccessToken(String username, List<String> scopes, String role, int tokenVersion) {
        return generateAccessToken(username, username, scopes, role, tokenVersion);
    }

    public String generateAccessToken(String username, String clientId, List<String> scopes, String role) {
        return generateAccessToken(username, clientId, scopes, role, 0);
    }

    public String generateAccessToken(String username, String clientId, List<String> scopes, String role, int tokenVersion) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtConfig.getAccessExpiration().toMillis());
        var builder = Jwts.builder()
                .subject(username)
                .claim("scope", scopes)
                .claim("typ", "access")
                .claim("role", role != null ? role : "user")
                .claim("tokenVersion", tokenVersion)
                .issuedAt(now)
                .expiration(expiry);
        if (clientId != null && !clientId.isBlank() && !clientId.equals(username)) {
            builder.claim("clientId", clientId);
        }
        return builder.signWith(signingKey).compact();
    }

    /**
     * 生成 access token（向后兼容：无 role 时默认 "user"）。
     */
    public String generateAccessToken(String username, List<String> scopes) {
        return generateAccessToken(username, username, scopes, "user", 0);
    }

    /**
     * 生成 refresh token。
     */
    public String generateRefreshToken(String username, int tokenVersion) {
        return generateRefreshToken(username, username, tokenVersion);
    }

    // refresh token 绑定签发时的 tokenVersion（审查 F4）：改密/重置/降权会
    // 递增账户 tokenVersion，旧 refresh token 随之失效，"改密踢出所有会话"成立。
    public String generateRefreshToken(String username, String clientId, int tokenVersion) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtConfig.getRefreshExpiration().toMillis());
        var builder = Jwts.builder()
                .subject(username)
                .claim("typ", "refresh")
                .claim("tokenVersion", tokenVersion)
                .issuedAt(now)
                .expiration(expiry);
        if (clientId != null && !clientId.isBlank() && !clientId.equals(username)) {
            builder.claim("clientId", clientId);
        }
        return builder.signWith(signingKey).compact();
    }

    /**
     * 解析并验证 token，返回 claims。无效或过期抛出 {@link JwtValidationException}。
     */
    public Claims parseToken(String token) {
        // Fast path: check cache first
        Claims cached = tokenCache.getIfPresent(token);
        if (cached != null && cached.getExpiration().after(new Date())) {
            return cached;
        }
        // Slow path: parse + cache
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // Only cache if token has sufficient remaining lifetime
            long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remaining > 10_000) {
                tokenCache.put(token, claims);
            }
            return claims;
        } catch (ExpiredJwtException e) {
            tokenCache.invalidate(token);
            throw new JwtValidationException("token_expired", "Token has expired");
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtValidationException("invalid_token", "Invalid token: " + e.getMessage());
        }
    }

    /**
     * Invalidate cached token. Called when account tokenVersion changes or
     * account is frozen/deleted.
     */
    public void invalidateToken(String token) {
        tokenCache.invalidate(token);
    }

    /**
     * Invalidate all cached tokens for a specific username.
     */
    public void invalidateUserTokens(String username) {
        tokenCache.asMap().entrySet().removeIf(e -> username.equals(e.getValue().getSubject()));
    }

    /**
     * 检查 token 类型是否为 refresh token。
     */
    public boolean isRefreshToken(Claims claims) {
        return "refresh".equals(claims.get("typ", String.class));
    }

    /**
     * 从 claims 中提取 clientId。优先读取显式 claim，缺失时回退到 sub（向后兼容）。
     */
    public String extractClientId(Claims claims) {
        String clientId = claims.get("clientId", String.class);
        return clientId != null && !clientId.isBlank() ? clientId : claims.getSubject();
    }

    /**
     * 从 claims 中提取用户名（即 sub）。
     */
    public String extractUsername(Claims claims) {
        return claims.getSubject();
    }

    /**
     * 从 claims 中提取 role。缺失时默认 "user"（向后兼容）。
     */
    public String extractRole(Claims claims) {
        String role = claims.get("role", String.class);
        return role != null ? role : "user";
    }

    public int extractTokenVersion(Claims claims) {
        Integer tokenVersion = claims.get("tokenVersion", Integer.class);
        return tokenVersion != null ? tokenVersion : 0;
    }

    private SecretKey resolveKey(String secret) {
        try {
            byte[] keyBytes = Decoders.BASE64.decode(secret);
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (io.jsonwebtoken.io.DecodingException e) {
            // Not valid Base64 — treat as raw UTF-8 string
            return Keys.hmacShaKeyFor(secret.getBytes());
        }
    }
}
