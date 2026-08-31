package com.mcpgateway.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.ToolCallRecord;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.repository.ToolCallRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 需求 FR-06.2 的核心保证：打点写入失败不得改变一次已经成功的下游调用结果。
 *
 * 这条属性成立的机制就在这个类里 —— recorder 把自身的异常全部吞掉，只留错误日志和指标。
 * 上层的 {@code GatewayMcpHandler} 并不捕获 recorder 的异常，所以一旦这里漏出异常，
 * 一次成功的 tools/call 就会变成失败。因此这里逐个方法钉住"不抛出"。
 */
class ToolCallRecorderTest {

    private static final Instant STARTED = Instant.parse("2026-08-31T00:00:00Z");

    private ToolCallRecordRepository repository;

    private SimpleMeterRegistry meterRegistry;

    private ToolCallRecorder recorder;

    @BeforeEach
    void setUp() {
        this.repository = mock(ToolCallRecordRepository.class);
        this.meterRegistry = new SimpleMeterRegistry();
        this.recorder = new ToolCallRecorder(this.repository, new ObjectMapper(), this.meterRegistry);
    }

    private double recordFailureCount(String phase) {
        var counter = this.meterRegistry.find("mcp.gateway.call.record.failures").tag("phase", phase).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---------------------------------------------------------- 正常写入

    @Test
    @DisplayName("需求 FR-06.1：先写 STARTED，参数按原始 JSON 保存")
    void writesStartedRecordWithSerialisedArguments() {
        this.recorder.recordStarted("call-1", "trace-1", "gw-1", "ds-1", "kb_a__search", "search",
                Map.of("q", "hello"), STARTED);

        ArgumentCaptor<ToolCallRecord> captor = ArgumentCaptor.forClass(ToolCallRecord.class);
        verify(this.repository).insertStarted(captor.capture());
        ToolCallRecord record = captor.getValue();
        assertThat(record.callId()).isEqualTo("call-1");
        assertThat(record.traceId()).isEqualTo("trace-1");
        assertThat(record.status()).isEqualTo(CallStatus.STARTED);
        assertThat(record.requestJson()).isEqualTo("{\"q\":\"hello\"}");
        assertThat(record.startedAt()).isEqualTo(STARTED);
    }

    @Test
    @DisplayName("未知工具的记录允许目标信息为空")
    void allowsNullRouteDetails() {
        this.recorder.recordStarted("call-1", "trace-1", "gw-1", null, "kb_a__nope", null,
                Map.of(), STARTED);

        ArgumentCaptor<ToolCallRecord> captor = ArgumentCaptor.forClass(ToolCallRecord.class);
        verify(this.repository).insertStarted(captor.capture());
        assertThat(captor.getValue().downstreamMcpId()).isNull();
        assertThat(captor.getValue().originalToolName()).isNull();
    }

    @Test
    @DisplayName("成功终态写入结果 JSON，不写错误码")
    void writesSuccessTerminalState() {
        when(this.repository.complete(anyString(), any(), anyString(), isNull(), isNull(), any(), anyLong()))
                .thenReturn(1);

        this.recorder.recordSuccess("call-1", Map.of("content", "ok"), STARTED.plusMillis(12), 12);

        verify(this.repository).complete("call-1", CallStatus.SUCCESS, "{\"content\":\"ok\"}", null, null,
                STARTED.plusMillis(12), 12);
    }

    @Test
    @DisplayName("需求 FR-06：超时单独记 TIMEOUT，其余失败记 ERROR")
    void mapsErrorCodesToTerminalStates() {
        assertThat(ToolCallRecorder.statusFor(ErrorCode.DOWNSTREAM_TIMEOUT)).isEqualTo(CallStatus.TIMEOUT);
        assertThat(ToolCallRecorder.statusFor(ErrorCode.DOWNSTREAM_ERROR)).isEqualTo(CallStatus.ERROR);
        assertThat(ToolCallRecorder.statusFor(ErrorCode.TOOL_NOT_FOUND)).isEqualTo(CallStatus.ERROR);
        assertThat(ToolCallRecorder.statusFor(ErrorCode.TOOL_DISABLED)).isEqualTo(CallStatus.ERROR);
        assertThat(ToolCallRecorder.statusFor(ErrorCode.INTERNAL_ERROR)).isEqualTo(CallStatus.ERROR);

        this.recorder.recordFailure("call-1", ErrorCode.DOWNSTREAM_TIMEOUT, "kb_a: downstream call timed out",
                STARTED.plusSeconds(30), 30_000);

        verify(this.repository).complete("call-1", CallStatus.TIMEOUT, null, "DOWNSTREAM_TIMEOUT",
                "kb_a: downstream call timed out", STARTED.plusSeconds(30), 30_000);
    }

    // ------------------------------------------------- FR-06.2 失败不外溢

    @Test
    @DisplayName("STARTED 写入失败被吞掉，只记指标和日志")
    void swallowsStartedWriteFailure() {
        doThrow(new DataAccessResourceFailureException("db is gone"))
                .when(this.repository).insertStarted(any());

        assertThatCode(() -> this.recorder.recordStarted("call-1", "trace-1", "gw-1", "ds-1",
                "kb_a__search", "search", Map.of(), STARTED)).doesNotThrowAnyException();
        assertThat(recordFailureCount("started")).isEqualTo(1);
    }

    @Test
    @DisplayName("成功终态写入失败被吞掉 —— 否则一次已经成功的调用会变成失败")
    void swallowsSuccessWriteFailure() {
        when(this.repository.complete(anyString(), any(), any(), any(), any(), any(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("db is gone"));

        assertThatCode(() -> this.recorder.recordSuccess("call-1", Map.of(), STARTED, 1))
                .doesNotThrowAnyException();
        assertThat(recordFailureCount("success")).isEqualTo(1);
    }

    @Test
    @DisplayName("失败终态写入失败同样被吞掉")
    void swallowsFailureWriteFailure() {
        when(this.repository.complete(anyString(), any(), any(), any(), any(), any(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("db is gone"));

        assertThatCode(() -> this.recorder.recordFailure("call-1", ErrorCode.DOWNSTREAM_ERROR, "boom",
                STARTED, 1)).doesNotThrowAnyException();
        assertThat(recordFailureCount("failure")).isEqualTo(1);
    }

    @Test
    @DisplayName("参数序列化失败也不外溢，留一个可识别的占位")
    void unserialisablePayloadDoesNotBreakRecording() {
        Object unserialisable = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                throw new IllegalStateException("nope");
            }
        };

        assertThatCode(() -> this.recorder.recordStarted("call-1", "trace-1", "gw-1", "ds-1",
                "kb_a__search", "search", unserialisable, STARTED)).doesNotThrowAnyException();

        ArgumentCaptor<ToolCallRecord> captor = ArgumentCaptor.forClass(ToolCallRecord.class);
        verify(this.repository).insertStarted(captor.capture());
        assertThat(captor.getValue().requestJson()).isEqualTo("{\"_unserializable\":true}");
    }

    // ---------------------------------------------------------------- 指标

    @Test
    @DisplayName("调用计数按状态和错误码打标签，便于按类型排查")
    void countsCallsByStatusAndErrorCode() {
        this.recorder.recordSuccess("call-1", Map.of(), STARTED, 1);
        this.recorder.recordFailure("call-2", ErrorCode.TOOL_DISABLED, "disabled", STARTED, 1);
        this.recorder.recordFailure("call-3", ErrorCode.DOWNSTREAM_TIMEOUT, "timeout", STARTED, 1);

        assertThat(this.meterRegistry.find("mcp.gateway.tool.calls")
                .tag("status", "SUCCESS").tag("errorCode", "NONE").counter().count()).isEqualTo(1);
        assertThat(this.meterRegistry.find("mcp.gateway.tool.calls")
                .tag("status", "ERROR").tag("errorCode", "TOOL_DISABLED").counter().count()).isEqualTo(1);
        assertThat(this.meterRegistry.find("mcp.gateway.tool.calls")
                .tag("status", "TIMEOUT").tag("errorCode", "DOWNSTREAM_TIMEOUT").counter().count())
                .isEqualTo(1);
    }
}
