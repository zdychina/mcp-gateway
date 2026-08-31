package com.mcpgateway.api;

import com.mcpgateway.api.dto.GatewayToolResponse;
import com.mcpgateway.api.dto.UpdateToolRequest;
import com.mcpgateway.service.GatewayToolService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具启停与描述覆盖（需求 §8 / 6.5）。
 *
 * 只有 PATCH，没有其他动词：工具行由同步流程创建和删除，操作人不能手工增删，
 * 也不能改名称或 Schema（需求 6.5.6）。
 */
@RestController
@RequestMapping("/api/gateways/{gatewayId}/tools")
public class GatewayToolController {

    private final GatewayToolService toolService;

    public GatewayToolController(GatewayToolService toolService) {
        this.toolService = toolService;
    }

    @PatchMapping("/{toolId}")
    public ApiResponse<GatewayToolResponse> update(@PathVariable String gatewayId, @PathVariable String toolId,
            @Valid @RequestBody UpdateToolRequest request) {
        return ApiResponse.ok(this.toolService.update(gatewayId, toolId, request));
    }
}
