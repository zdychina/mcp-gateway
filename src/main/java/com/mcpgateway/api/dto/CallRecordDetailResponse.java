package com.mcpgateway.api.dto;

import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.ToolCallRecord;

import java.time.Instant;

/**
 * 单条调用记录的完整内容（需求 FR-06.5）。
 *
 * <p><b>这是整个管理 API 里唯一会返回知识库正文的接口。</b>requestJson 和 responseJson
 * 按需求 FR-06.4 原样保存、不截断，里面就是子 MCP 返回的业务内容。需要登录才能读到，
 * 所以这个接口和数据库文件一样，需要按部署要求保护（见 SECURITY.md）。
 *
 * <p>反过来说，这里**不会**有凭证：需求 FR-06.3 规定任何 header、网关访问令牌和
 * 子 MCP 凭证都不得进入调用记录，打点服务本身就拿不到它们。
 *
 * <p>两个 JSON 字段以字符串原样返回，不在服务端解析成对象：库里存的可能是
 * 打点时的占位符（序列化失败会写 {@code {"_unserializable":true}}），
 * 也可能是历史遗留的坏数据，解析失败不该让整条记录取不出来。
 */
public record CallRecordDetailResponse(
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

    public static CallRecordDetailResponse from(ToolCallRecord record) {
        return new CallRecordDetailResponse(
                record.callId(), record.traceId(), record.gatewayId(), record.downstreamMcpId(),
                record.exposedToolName(), record.originalToolName(),
                record.requestJson(), record.responseJson(),
                record.status(), record.errorCode(), record.errorMessage(),
                record.startedAt(), record.finishedAt(), record.durationMs());
    }
}
