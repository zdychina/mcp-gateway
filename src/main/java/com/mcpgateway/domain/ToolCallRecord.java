package com.mcpgateway.domain;

import java.time.Instant;

/**
 * 调用打点（表 tool_call_record，需求 FR-06）。
 *
 * 硬性约束：requestJson / responseJson 之外的任何字段都不得携带 header、
 * 网关访问令牌或子 MCP 凭证；errorMessage 必须是已脱敏的摘要（FR-06.3）。
 *
 * downstreamMcpId 与 originalToolName 可空 —— 未知工具或停用工具的调用无法确定目标。
 */
public record ToolCallRecord(
        String callId,
        String traceId,
        String gatewayId,
        String downstreamMcpId,
        String exposedToolName,
        String originalToolName,
        String requestJson,
        String responseJson,
        CallStatus status,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs) {

    public static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    /** 建立一条 STARTED 记录（需求 FR-06.1：调用开始时先写入）。 */
    public static ToolCallRecord started(String callId, String traceId, String gatewayId, String downstreamMcpId,
            String exposedToolName, String originalToolName, String requestJson, Instant startedAt) {
        return new ToolCallRecord(callId, traceId, gatewayId, downstreamMcpId, exposedToolName, originalToolName,
                requestJson, null, CallStatus.STARTED, null, null, startedAt, null, null);
    }
}
