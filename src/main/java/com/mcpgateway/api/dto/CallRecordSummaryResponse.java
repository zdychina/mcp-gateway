package com.mcpgateway.api.dto;

import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.ToolCallSummary;

import java.time.Instant;

/**
 * 调用记录列表的一行（需求 FR-06.5）。
 *
 * 不含入参和返回内容 —— 那两个字段可能各接近 1 MiB，且装的是知识库正文。
 * 要看正文得按 callId 取单条，见 {@link CallRecordDetailResponse}。
 *
 * downstreamMcpId 和 originalToolName 可空：未知工具或停用工具的调用
 * 无法确定目标子 MCP，但依然会留下记录。
 */
public record CallRecordSummaryResponse(
        String callId,
        String traceId,
        String downstreamMcpId,
        String exposedToolName,
        String originalToolName,
        CallStatus status,
        String errorCode,
        /** 已脱敏的错误摘要，与返回给 Agent 的是同一份文案。 */
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs) {

    public static CallRecordSummaryResponse from(ToolCallSummary summary) {
        return new CallRecordSummaryResponse(
                summary.callId(), summary.traceId(), summary.downstreamMcpId(),
                summary.exposedToolName(), summary.originalToolName(),
                summary.status(), summary.errorCode(), summary.errorMessage(),
                summary.startedAt(), summary.finishedAt(), summary.durationMs());
    }
}
