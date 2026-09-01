package com.mcpgateway.domain;

import java.time.Instant;

/**
 * 调用记录的列表投影（需求 FR-06.5 的查询接口）。
 *
 * 刻意**不含** requestJson 和 responseJson。那两列按 FR-06.4 原样保存且不截断，
 * 里面是知识库返回的正文，单条就可能接近 1 MiB（上界由请求/响应大小限制兜住）。
 * 列表一次几十条，把它们带上既拖垮响应体，也把大量业务内容摊在一个没有登录的接口上。
 * 想看正文只能按 callId 取单条 —— 那是一次明确的、针对一条记录的操作。
 */
public record ToolCallSummary(
        String callId,
        String traceId,
        String gatewayId,
        String downstreamMcpId,
        String exposedToolName,
        String originalToolName,
        CallStatus status,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs) {
}
