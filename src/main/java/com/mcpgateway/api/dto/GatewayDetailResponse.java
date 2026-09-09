package com.mcpgateway.api.dto;

import com.mcpgateway.domain.GatewayStatus;

import java.time.Instant;
import java.util.List;

/**
 * 需求 10.2 网关详情页所需字段。
 *
 * @param insecureDownstreamTls 部署是否关掉了下游 TLS 校验。这是**部署级**的开关，
 *                              和具体哪个网关无关 —— 放在这里是因为需要看见它的人正好在这个页面上：
 *                              配子 MCP 的时候必须知道这条连接不验证对方身份。
 *                              它只随已认证的接口下发，不进公开的 /api/auth/session。
 */
public record GatewayDetailResponse(
        String id,
        String name,
        String slug,
        String description,
        GatewayStatus status,
        String mcpUrl,
        List<DownstreamMcpResponse> downstreams,
        boolean insecureDownstreamTls,
        Instant createdAt,
        Instant updatedAt) {
}
