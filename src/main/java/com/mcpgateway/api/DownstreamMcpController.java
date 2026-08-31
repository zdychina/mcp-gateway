package com.mcpgateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpgateway.api.dto.GatewayDetailResponse;
import com.mcpgateway.api.dto.ImportResponse;
import com.mcpgateway.api.dto.SyncResponse;
import com.mcpgateway.api.dto.UpdateDownstreamRequest;
import com.mcpgateway.service.DownstreamImportOrchestrator;
import com.mcpgateway.service.DownstreamMcpService;
import com.mcpgateway.service.GatewayService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 子 MCP 配置与同步 API（需求 §8 / FR-02）。 */
@RestController
@RequestMapping("/api/gateways/{gatewayId}/mcp-servers")
public class DownstreamMcpController {

    private final DownstreamMcpService downstreamService;

    private final DownstreamImportOrchestrator importOrchestrator;

    private final GatewayService gatewayService;

    public DownstreamMcpController(DownstreamMcpService downstreamService,
            DownstreamImportOrchestrator importOrchestrator, GatewayService gatewayService) {
        this.downstreamService = downstreamService;
        this.importOrchestrator = importOrchestrator;
        this.gatewayService = gatewayService;
    }

    /**
     * 粘贴 mcpServers JSON 导入，并按需求 6.4.2 立即同步一次。
     *
     * 收原始 JsonNode 而不是绑定 DTO，是为了能对 command/args/env 这类 stdio 配置
     * 明确报 UNSUPPORTED_TRANSPORT，而不是把它们当未知字段悄悄忽略掉。
     */
    @PostMapping("/import")
    public ApiResponse<ImportResponse> importServers(@PathVariable String gatewayId,
            @RequestBody JsonNode body) {
        return ApiResponse.ok(this.importOrchestrator.importAndSync(gatewayId, body));
    }

    /**
     * 需求 6.2.8：人工触发的"测试并同步"。
     *
     * 同步失败也返回 200 —— 请求本身处理成功了，失败的是与下游的交互，细节在 body 里。
     * 这样前端能统一渲染成功和失败两种结果。
     */
    @PostMapping("/{serverId}/sync")
    public ApiResponse<SyncResponse> sync(@PathVariable String gatewayId, @PathVariable String serverId) {
        return ApiResponse.ok(this.importOrchestrator.syncOne(gatewayId, serverId));
    }

    @PutMapping("/{serverId}")
    public ApiResponse<GatewayDetailResponse> update(@PathVariable String gatewayId,
            @PathVariable String serverId, @Valid @RequestBody UpdateDownstreamRequest request) {
        this.downstreamService.update(gatewayId, serverId, request);
        return ApiResponse.ok(this.gatewayService.detail(gatewayId));
    }

    @DeleteMapping("/{serverId}")
    public ApiResponse<GatewayDetailResponse> delete(@PathVariable String gatewayId,
            @PathVariable String serverId) {
        this.downstreamService.delete(gatewayId, serverId);
        return ApiResponse.ok(this.gatewayService.detail(gatewayId));
    }
}
