package com.mcpgateway.api.dto;

/**
 * 创建网关的响应。
 *
 * 需求 FR-05.3：访问令牌只在这里完整返回一次，之后任何接口都只能拿到占位符。
 */
public record CreatedGatewayResponse(GatewayDetailResponse gateway, String accessToken) {
}
