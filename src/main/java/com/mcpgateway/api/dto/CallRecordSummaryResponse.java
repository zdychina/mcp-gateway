package com.mcpgateway.api.dto;

import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.ToolCallSummary;

import java.time.Instant;
import java.util.Map;

/**
 * 调用记录列表的一行（需求 FR-06.5）。
 *
 * 不含入参和返回内容 —— 那两个字段可能各接近 1 MiB，且装的是知识库正文。
 * 要看正文得按 callId 取单条，见 {@link CallRecordDetailResponse}。
 *
 * <p>唯一的例外是 {@code extracted}：调用方用 {@code extract} 参数点名要的那几个字段，
 * 每个最长 200 字符。它是"列表不带正文"这条约束的受控开口，
 * 见 {@link com.mcpgateway.service.CallPayloadExtractor}。
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
        Long durationMs,
        /**
         * 按 extract 参数抽出来的字段，键就是请求里给的路径（如 {@code request:/q}）。
         * 没要求抽取时是空 map —— 字段恒在，前端不用判断有没有这个键。
         */
        Map<String, ExtractedValue> extracted) {

    public static CallRecordSummaryResponse from(ToolCallSummary summary) {
        return from(summary, Map.of());
    }

    public static CallRecordSummaryResponse from(ToolCallSummary summary,
            Map<String, ExtractedValue> extracted) {
        return new CallRecordSummaryResponse(
                summary.callId(), summary.traceId(), summary.downstreamMcpId(),
                summary.exposedToolName(), summary.originalToolName(),
                summary.status(), summary.errorCode(), summary.errorMessage(),
                summary.startedAt(), summary.finishedAt(), summary.durationMs(),
                extracted == null ? Map.of() : extracted);
    }
}
