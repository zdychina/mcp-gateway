package com.mcpgateway.api.dto;

/** 令牌轮换的响应。明文只在这里出现一次。旧令牌立即失效，没有过渡期。 */
public record RotatedTokenResponse(String accessToken, String mcpUrl) {
}
