package com.mcpgateway.api.dto;

import com.mcpgateway.domain.GatewayStatus;

import java.time.Instant;
import java.util.List;

/** 需求 10.2 网关详情页所需字段。 */
public record GatewayDetailResponse(
        String id,
        String name,
        String slug,
        String description,
        GatewayStatus status,
        String mcpUrl,
        List<DownstreamMcpResponse> downstreams,
        Instant createdAt,
        Instant updatedAt) {
}
