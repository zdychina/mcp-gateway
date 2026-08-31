package com.mcpgateway.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.ToolCallRecord;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.repository.ToolCallRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 调用打点（需求 FR-06）。
 *
 * 两条硬约束决定了这个类的写法：
 *
 * <ol>
 *   <li>FR-06.2「日志写入失败不得改变一次已经成功的下游调用结果」—— 所以每个方法都用
 *       REQUIRES_NEW 开独立事务，并把自身的异常吞掉，只留错误日志和指标。打点失败绝不能
 *       把一次已经成功的 tools/call 变成失败。</li>
 *   <li>FR-06.3「任何 header、网关访问令牌和子 MCP 凭证都不得进入调用记录」—— 所以这里
 *       只接收工具参数和结果，不接收也拿不到任何请求头。</li>
 * </ol>
 */
@Service
public class ToolCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(ToolCallRecorder.class);

    private static final String CALLS_METRIC = "mcp.gateway.tool.calls";

    private static final String RECORD_FAILURES_METRIC = "mcp.gateway.call.record.failures";

    private final ToolCallRecordRepository records;

    private final ObjectMapper objectMapper;

    private final MeterRegistry meterRegistry;

    public ToolCallRecorder(ToolCallRecordRepository records, ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.records = records;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 需求 FR-06.1：调用开始时先写入 STARTED。
     *
     * downstreamMcpId 和 originalToolName 允许为空 —— 未知工具或停用工具的调用无法确定目标，
     * 但依然必须留下记录，否则 FR-06「每次 tools/call 均生成一条调用记录」就有缺口。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStarted(String callId, String traceId, String gatewayId, String downstreamMcpId,
            String exposedToolName, String originalToolName, Object arguments, Instant startedAt) {
        try {
            this.records.insertStarted(ToolCallRecord.started(callId, traceId, gatewayId, downstreamMcpId,
                    exposedToolName, originalToolName, toJson(arguments), startedAt));
        }
        catch (RuntimeException ex) {
            onRecordFailure("started", callId, ex);
        }
    }

    /** 下游返回了结果。工具自己标记的 isError 保留在 response_json 里，不改变这里的状态。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String callId, Object result, Instant finishedAt, long durationMs) {
        try {
            this.records.complete(callId, CallStatus.SUCCESS, toJson(result), null, null, finishedAt, durationMs);
            countCall(CallStatus.SUCCESS, null);
        }
        catch (RuntimeException ex) {
            onRecordFailure("success", callId, ex);
        }
    }

    /** 网关没能拿到结果：未知工具、停用工具、参数非法、网络失败、超时或下游报错。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String callId, ErrorCode errorCode, String safeMessage, Instant finishedAt,
            long durationMs) {
        CallStatus status = statusFor(errorCode);
        try {
            this.records.complete(callId, status, null, errorCode.name(), safeMessage, finishedAt, durationMs);
            countCall(status, errorCode);
        }
        catch (RuntimeException ex) {
            onRecordFailure("failure", callId, ex);
        }
    }

    /** 需求 FR-06：超时单独成一个终态，其余失败统一记 ERROR。 */
    static CallStatus statusFor(ErrorCode errorCode) {
        return errorCode == ErrorCode.DOWNSTREAM_TIMEOUT ? CallStatus.TIMEOUT : CallStatus.ERROR;
    }

    /**
     * 需求 FR-06.4：request_json 和 response_json 按原始 JSON 保存。
     *
     * 不做截断 —— 需求明确要求原样保存。体积上界由 transport 的 maxRequestSize 和下游客户端的
     * maxResponseSize 兜住（需求 12.9），所以单条记录不会无限增长。
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return this.objectMapper.writeValueAsString(value);
        }
        catch (Exception ex) {
            // 序列化失败不能连累打点本身，留个可识别的占位。
            log.warn("failed to serialize a tool call payload for recording", ex);
            return "{\"_unserializable\":true}";
        }
    }

    private void countCall(CallStatus status, ErrorCode errorCode) {
        Counter.builder(CALLS_METRIC)
                .tag("status", status.name())
                .tag("errorCode", errorCode == null ? "NONE" : errorCode.name())
                .register(this.meterRegistry)
                .increment();
    }

    /**
     * 需求 FR-06.2：打点失败必须写应用错误日志和指标，但不得改变调用结果。
     * 所以这里只记录，不重新抛出。
     */
    private void onRecordFailure(String phase, String callId, RuntimeException ex) {
        log.error("failed to write the {} tool call record for call {}; the call result is unaffected",
                phase, callId, ex);
        this.meterRegistry.counter(RECORD_FAILURES_METRIC, "phase", phase).increment();
    }
}
