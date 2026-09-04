package com.mcpgateway.api;

import com.mcpgateway.api.dto.AgentConfigResponse;
import com.mcpgateway.api.dto.CreateGatewayRequest;
import com.mcpgateway.api.dto.CreatedGatewayResponse;
import com.mcpgateway.api.dto.GatewayDetailResponse;
import com.mcpgateway.api.dto.GatewaySummaryResponse;
import com.mcpgateway.api.dto.RotatedTokenResponse;
import com.mcpgateway.api.dto.UpdateGatewayRequest;
import com.mcpgateway.service.GatewayService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 网关管理 API（需求 §8）。
 *
 * 需求 12.8：本控制器下的所有接口都需要登录，由 SecurityConfig 统一收口，
 * 这里不做任何逐接口的授权判断。需求 3.2 不含权限系统，因此也没有角色可判。
 */
@RestController
@RequestMapping("/api/gateways")
public class GatewayController {

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @GetMapping
    public ApiResponse<List<GatewaySummaryResponse>> list() {
        return ApiResponse.ok(this.gatewayService.list());
    }

    /** 响应里含明文访问令牌，且仅此一次（需求 FR-05.3）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreatedGatewayResponse> create(@Valid @RequestBody CreateGatewayRequest request) {
        return ApiResponse.ok(this.gatewayService.create(request));
    }

    @GetMapping("/{gatewayId}")
    public ApiResponse<GatewayDetailResponse> detail(@PathVariable String gatewayId) {
        return ApiResponse.ok(this.gatewayService.detail(gatewayId));
    }

    @PutMapping("/{gatewayId}")
    public ApiResponse<GatewayDetailResponse> update(@PathVariable String gatewayId,
            @Valid @RequestBody UpdateGatewayRequest request) {
        return ApiResponse.ok(this.gatewayService.update(gatewayId, request));
    }

    /** 需求 6.1.7：级联删除。二次确认由前端负责（需求 10.1）。 */
    @DeleteMapping("/{gatewayId}")
    public ApiResponse<Void> delete(@PathVariable String gatewayId) {
        this.gatewayService.delete(gatewayId);
        return ApiResponse.ok();
    }

    @GetMapping("/{gatewayId}/agent-config")
    public ApiResponse<AgentConfigResponse> agentConfig(@PathVariable String gatewayId) {
        return ApiResponse.ok(this.gatewayService.agentConfig(gatewayId));
    }

    /** 明文令牌仅在此响应里出现一次；旧令牌立即失效。 */
    @PostMapping("/{gatewayId}/access-token/rotate")
    public ApiResponse<RotatedTokenResponse> rotateAccessToken(@PathVariable String gatewayId) {
        return ApiResponse.ok(this.gatewayService.rotateAccessToken(gatewayId));
    }
}
