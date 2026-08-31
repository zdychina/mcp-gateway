package com.mcpgateway.api.dto;

import com.mcpgateway.domain.SyncStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 子 MCP 的对外视图。
 *
 * headers 只含名称和遮罩值（需求 12.4），真实值永远不出现在任何 API 响应里。
 * tools 按子 MCP 分组挂在这里，对应需求 FR-03 的详情页布局。
 */
public record DownstreamMcpResponse(
        String id,
        String name,
        String type,
        String url,
        Map<String, String> headers,
        SyncStatus syncStatus,
        Instant lastSyncAt,
        String lastSyncError,
        int toolCount,
        List<GatewayToolResponse> tools) {
}
