package com.mcpgateway.api.dto;

import com.mcpgateway.domain.GatewayStatus;

import java.time.Instant;

/** 需求 10.1 网关列表页所需字段。 */
public record GatewaySummaryResponse(
        String id,
        String name,
        String slug,
        String description,
        GatewayStatus status,
        int downstreamCount,
        int toolCount,
        String mcpUrl,
        Instant createdAt,
        Instant updatedAt) {
}
