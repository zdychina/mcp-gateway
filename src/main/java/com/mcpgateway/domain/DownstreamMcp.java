package com.mcpgateway.domain;

import java.time.Instant;

/**
 * 子 MCP（表 downstream_mcp，需求 §9.2）。
 *
 * encryptedHeadersJson 恒为密文。任何需要明文的地方都必须经由
 * {@code DownstreamHeaderCodec}，不允许在别处解密（需求 12.2 / 12.4）。
 */
public record DownstreamMcp(
        String id,
        String gatewayId,
        String name,
        String type,
        String url,
        String encryptedHeadersJson,
        SyncStatus syncStatus,
        Instant lastSyncAt,
        String lastSyncError,
        Instant createdAt,
        Instant updatedAt) {

    /** 需求 6.2.4：MVP 只接受 streamable-http。 */
    public static final String TYPE_STREAMABLE_HTTP = "streamable-http";

    /**
     * 需求 6.2.3：名称只能包含字母、数字、下划线、短横线和点。
     * 连续分隔符 {@code __} 的排除由 {@code containsDoubleUnderscore} 单独判断，
     * 因为它是聚合命名的分隔符，混进正则会让错误信息说不清原因。
     */
    public static final String NAME_PATTERN = "^[A-Za-z0-9_.-]{1,64}$";

    public static boolean containsDoubleUnderscore(String name) {
        return name != null && name.contains("__");
    }
}
