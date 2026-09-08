package com.mcpgateway.api.dto;

/**
 * 编辑单个子 MCP 的结果：更新后的网关 + 这次是否重新同步过。
 *
 * <p>形状刻意向 {@link ImportResponse} 看齐：这两条路都是"先落配置、再摸下游"，
 * 配置一定会落库，同步则单独报成败（需求 6.2.9）。区别只在于编辑只涉及一个子 MCP，
 * 所以这里是单数。
 *
 * @param syncResult 这次的同步结果；<b>只改名字时为 null</b> —— 聚合工具名是本地重算的，
 *                   没必要去拉下游。改了 URL 或凭证才会同步，因为那两样一变，
 *                   下游能给出的工具集本来就可能不一样了
 */
public record DownstreamUpdateResponse(GatewayDetailResponse gateway, SyncResponse syncResult) {
}
