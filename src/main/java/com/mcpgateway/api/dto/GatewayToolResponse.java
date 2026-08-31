package com.mcpgateway.api.dto;

import com.mcpgateway.domain.GatewayTool;

import java.time.Instant;

/** 需求 FR-03：网关详情页每个工具至少要显示的字段。 */
public record GatewayToolResponse(
        String id,
        String downstreamMcpId,
        String exposedName,
        String originalName,
        String originalDescription,
        String customDescription,
        String effectiveDescription,
        boolean enabled,
        Instant lastSyncedAt) {

    public static GatewayToolResponse from(GatewayTool tool) {
        return new GatewayToolResponse(tool.id(), tool.downstreamMcpId(), tool.exposedName(),
                tool.originalName(), tool.originalDescription(), tool.customDescription(),
                tool.effectiveDescription(), tool.enabled(), tool.lastSyncedAt());
    }
}
