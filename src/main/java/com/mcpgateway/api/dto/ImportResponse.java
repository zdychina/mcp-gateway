package com.mcpgateway.api.dto;

import java.util.List;

/**
 * 导入的结果：配置本身的写入结果 + 每个新子 MCP 的首次同步结果。
 *
 * 需求 6.4.2 要求子 MCP 新增后立即同步一次，但需求 6.2.9 又要求单个子 MCP 不可用不影响其他。
 * 所以配置一定会全部落库，同步则逐个尝试、各自记录成败。
 */
public record ImportResponse(GatewayDetailResponse gateway, List<SyncResponse> syncResults) {
}
