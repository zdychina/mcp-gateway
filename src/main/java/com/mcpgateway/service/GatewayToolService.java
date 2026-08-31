package com.mcpgateway.service;

import com.mcpgateway.api.dto.GatewayToolResponse;
import com.mcpgateway.api.dto.UpdateToolRequest;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.repository.GatewayToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 工具启停与描述覆盖（需求 6.5）。
 *
 * 需求 6.5.6：前端不得修改工具名称、输入/输出 Schema、annotations 或执行语义。
 * 这个类只暴露 enabled 和 customDescription 两个可写点，别的字段没有入口。
 */
@Service
public class GatewayToolService {

    private static final Logger log = LoggerFactory.getLogger(GatewayToolService.class);

    private final GatewayToolRepository tools;

    private final GatewayService gatewayService;

    public GatewayToolService(GatewayToolRepository tools, GatewayService gatewayService) {
        this.tools = tools;
        this.gatewayService = gatewayService;
    }

    @Transactional
    public GatewayToolResponse update(String gatewayId, String toolId, UpdateToolRequest request) {
        this.gatewayService.requireGateway(gatewayId);
        GatewayTool tool = requireTool(gatewayId, toolId);
        Instant now = Instant.now();

        if (request.enabled() != null && request.enabled() != tool.enabled()) {
            this.tools.updateEnabled(toolId, request.enabled(), now);
            log.info("tool {} on gateway {} is now {}", tool.exposedName(), gatewayId,
                    request.enabled() ? "enabled" : "disabled");
        }

        // 需求 6.5.5：空白串等同于清除，回退到原始描述。
        // 用 customDescriptionPresent 区分"没传这个字段"和"传了空串要清除"。
        if (request.customDescriptionPresent()) {
            String value = request.customDescription();
            String normalized = (value == null || value.isBlank()) ? null : value.trim();
            this.tools.updateCustomDescription(toolId, normalized, now);
        }

        return GatewayToolResponse.from(this.tools.findById(toolId).orElseThrow());
    }

    /** 同时校验归属，避免用别的网关的 id 改到这个工具。 */
    private GatewayTool requireTool(String gatewayId, String toolId) {
        GatewayTool tool = this.tools.findById(toolId)
                .orElseThrow(() -> GatewayException.of(ErrorCode.TOOL_NOT_FOUND, "no such tool"));
        if (!tool.gatewayId().equals(gatewayId)) {
            throw GatewayException.of(ErrorCode.TOOL_NOT_FOUND, "no such tool");
        }
        return tool;
    }
}
