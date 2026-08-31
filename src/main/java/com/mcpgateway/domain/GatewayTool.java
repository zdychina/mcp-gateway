package com.mcpgateway.domain;

import java.time.Instant;

/**
 * 工具快照（表 gateway_tool，需求 §9.3）。
 *
 * 一行代表"某个子 MCP 的某个原工具，在本网关下对 Agent 暴露成什么样"。
 * 协议字段（originalDescription / inputSchemaJson / outputSchemaJson / annotationsJson）
 * 每次同步整体覆盖；enabled 和 customDescription 是操作人的配置，同步时必须保留
 * （需求 6.4.4 / 6.4.5）。
 */
public record GatewayTool(
        String id,
        String gatewayId,
        String downstreamMcpId,
        String originalName,
        String exposedName,
        String originalDescription,
        String customDescription,
        String inputSchemaJson,
        String outputSchemaJson,
        String annotationsJson,
        boolean enabled,
        String definitionHash,
        Instant lastSyncedAt,
        Instant createdAt,
        Instant updatedAt) {

    /** 需求 6.3.4：聚合工具名长度上限。 */
    public static final int MAX_EXPOSED_NAME_LENGTH = 128;

    /** 需求 6.3：聚合工具名固定为 子MCP名称__原工具名。 */
    public static final String NAME_SEPARATOR = "__";

    /** MCP 工具命名规范允许的字符集。 */
    public static final String EXPOSED_NAME_PATTERN = "^[A-Za-z0-9_.-]{1,128}$";

    /**
     * 需求 6.5.5：自定义描述非空时完全替换原始描述；为空时回退使用原始描述。
     * 这是唯一的"生效描述"计算入口，前端展示和 tools/list 都必须走它。
     */
    public String effectiveDescription() {
        return (this.customDescription == null || this.customDescription.isBlank())
                ? this.originalDescription
                : this.customDescription;
    }

    public static String buildExposedName(String downstreamName, String originalToolName) {
        return downstreamName + NAME_SEPARATOR + originalToolName;
    }
}
