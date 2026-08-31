package com.mcpgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpgateway.api.dto.ImportResponse;
import com.mcpgateway.api.dto.SyncResponse;
import com.mcpgateway.downstream.ToolSyncService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 导入子 MCP 并立即同步一次（需求 6.4.2）。
 *
 * 单独一个类而不是塞进 {@link DownstreamMcpService}，是为了让"写配置"这一步真正跑在
 * 它自己的事务里。如果在同一个 bean 内部调用 @Transactional 方法，Spring 的代理会被绕开，
 * 配置写入就会和随后的网络调用挤在同一个事务中 —— 一个慢下游会把数据库连接占满 30 秒，
 * 而且同步失败会把已经写好的配置一起回滚，违反需求 6.2.9。
 */
@Service
public class DownstreamImportOrchestrator {

    private final DownstreamMcpService downstreamService;

    private final ToolSyncService syncService;

    private final GatewayService gatewayService;

    public DownstreamImportOrchestrator(DownstreamMcpService downstreamService, ToolSyncService syncService,
            GatewayService gatewayService) {
        this.downstreamService = downstreamService;
        this.syncService = syncService;
        this.gatewayService = gatewayService;
    }

    public ImportResponse importAndSync(String gatewayId, JsonNode body) {
        // 第一步：配置整批落库，事务在这个调用内部开始和结束。
        List<String> createdIds = this.downstreamService.importServers(gatewayId, body);

        // 第二步：逐个同步。任何一个失败都只标记它自己，不影响其他子 MCP，也不回滚配置。
        List<SyncResponse> results = createdIds.stream()
                .map(this.syncService::sync)
                .map(DownstreamImportOrchestrator::toResponse)
                .toList();

        return new ImportResponse(this.gatewayService.detail(gatewayId), results);
    }

    public SyncResponse syncOne(String gatewayId, String downstreamId) {
        this.gatewayService.requireGateway(gatewayId);
        // 校验归属，避免用别的网关的 id 触发同步。
        this.downstreamService.requireDownstream(gatewayId, downstreamId);
        return toResponse(this.syncService.sync(downstreamId));
    }

    static SyncResponse toResponse(ToolSyncService.SyncReport report) {
        if (report.succeeded()) {
            var outcome = report.outcome();
            return new SyncResponse(report.downstreamId(), report.downstreamName(), true,
                    outcome.added(), outcome.updated(), outcome.unchanged(), outcome.removed(), null, null);
        }
        return new SyncResponse(report.downstreamId(), report.downstreamName(), false,
                0, 0, 0, 0, report.errorCode().name(), report.errorMessage());
    }
}
