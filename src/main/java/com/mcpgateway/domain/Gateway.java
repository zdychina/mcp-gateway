package com.mcpgateway.domain;

import java.time.Instant;

/**
 * 总 MCP / 网关（表 mcp_gateway，需求 §9.1）。
 *
 * accessTokenHash 是不可逆哈希，明文令牌从不落库、也从不出现在这个对象里。
 */
public record Gateway(
        String id,
        String name,
        String slug,
        String description,
        String accessTokenHash,
        Instant createdAt,
        Instant updatedAt) {

    /** 需求 6.1.2：slug 仅允许 ASCII 字母、数字、短横线和下划线。 */
    public static final String SLUG_PATTERN = "^[A-Za-z0-9_-]{1,64}$";

    /** 需求 6.1.1 / 6.1.3。 */
    public static final int MAX_NAME_LENGTH = 64;

    public static final int MAX_DESCRIPTION_LENGTH = 4000;
}
