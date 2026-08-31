package com.mcpgateway.security;

import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 网关访问令牌（需求 4.3 / 12.3 / FR-05.3）。
 *
 * 令牌不属于用户系统，只用于避免 MCP 调用端点被匿名滥用。数据库里只存 SHA-256 哈希，
 * 明文只在创建和轮换时返回一次。
 *
 * 这里用 SHA-256 而不是 bcrypt/argon2 是刻意的：令牌是 256 位随机串，没有可猜测的低熵口令，
 * 慢哈希只会给每次 tools/call 增加无谓延迟。这一条不适用于人类口令。
 */
@Service
public class AccessTokenService {

    /** 便于人工识别的前缀，不承载任何机密。 */
    public static final String TOKEN_PREFIX = "mcpgw_";

    private static final int TOKEN_ENTROPY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /**
     * 新建令牌。
     *
     * @return 明文令牌与其哈希。调用方必须把明文交给用户一次后立即丢弃，只持久化哈希。
     */
    public GeneratedToken generate() {
        byte[] entropy = new byte[TOKEN_ENTROPY_BYTES];
        this.random.nextBytes(entropy);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        return new GeneratedToken(token, hash(token));
    }

    public String hash(String token) {
        if (token == null || token.isBlank()) {
            throw new GatewayException(ErrorCode.UNAUTHORIZED, "access token is missing");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new GatewayException(ErrorCode.INTERNAL_ERROR, "hash algorithm unavailable", ex);
        }
    }

    /**
     * 常量时间比对，避免通过响应时间逐字节猜令牌。
     *
     * @param presentedToken Agent 在 Authorization 头里带来的明文令牌
     * @param storedHash     数据库里的哈希
     */
    public boolean matches(String presentedToken, String storedHash) {
        if (presentedToken == null || presentedToken.isBlank() || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        String presentedHash;
        try {
            presentedHash = hash(presentedToken);
        }
        catch (GatewayException ex) {
            return false;
        }
        return MessageDigest.isEqual(
                presentedHash.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从 {@code Authorization: Bearer <token>} 中取出令牌。缺失或格式不对时返回 null。
     */
    public String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String trimmed = authorizationHeader.trim();
        if (trimmed.length() <= 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * @param token 明文令牌，只返回一次
     * @param hash  持久化到 mcp_gateway.access_token_hash 的值
     */
    public record GeneratedToken(String token, String hash) {
    }
}
