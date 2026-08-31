package com.mcpgateway.repository;

import com.mcpgateway.AbstractDataTest;
import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.ToolCallRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallRecordRepositoryTest extends AbstractDataTest {

    @Autowired
    private GatewayRepository gateways;

    @Autowired
    private DownstreamMcpRepository downstreams;

    @Autowired
    private ToolCallRecordRepository records;

    private Gateway gateway;

    private DownstreamMcp downstream;

    @BeforeEach
    void seed() {
        this.gateway = TestFixtures.gateway("call-" + System.nanoTime());
        this.gateways.insert(this.gateway);
        this.downstream = TestFixtures.downstream(this.gateway.id(), "kb_a");
        this.downstreams.insert(this.downstream);
    }

    private String insertStarted(String traceId) {
        String callId = TestFixtures.id();
        this.records.insertStarted(ToolCallRecord.started(callId, traceId, this.gateway.id(), this.downstream.id(),
                "kb_a__search", "search", "{\"q\":\"hello\"}", TestFixtures.NOW));
        return callId;
    }

    @Test
    @DisplayName("需求 FR-06.1：先写 STARTED，结束时更新为 SUCCESS 并带上输入输出和耗时")
    void twoPhaseWriteProducesSuccessRecord() {
        String callId = insertStarted("trace-success");

        ToolCallRecord started = this.records.findByCallId(callId).orElseThrow();
        assertThat(started.status()).isEqualTo(CallStatus.STARTED);
        assertThat(started.requestJson()).isEqualTo("{\"q\":\"hello\"}");
        assertThat(started.finishedAt()).isNull();
        assertThat(started.durationMs()).isNull();

        Instant finishedAt = TestFixtures.NOW.plusMillis(42);
        int rows = this.records.complete(callId, CallStatus.SUCCESS, "{\"content\":[]}", null, null, finishedAt, 42);

        assertThat(rows).isEqualTo(1);
        ToolCallRecord done = this.records.findByCallId(callId).orElseThrow();
        assertThat(done.status()).isEqualTo(CallStatus.SUCCESS);
        assertThat(done.responseJson()).isEqualTo("{\"content\":[]}");
        assertThat(done.durationMs()).isEqualTo(42L);
        assertThat(done.finishedAt()).isEqualTo(finishedAt);
        assertThat(done.errorCode()).isNull();
    }

    @Test
    @DisplayName("需求 FR-06：失败和超时分别落 ERROR 与 TIMEOUT")
    void recordsErrorAndTimeoutTerminalStates() {
        String errored = insertStarted("trace-error");
        String timedOut = insertStarted("trace-timeout");

        this.records.complete(errored, CallStatus.ERROR, null, "DOWNSTREAM_ERROR",
                "downstream returned an error", TestFixtures.NOW.plusMillis(10), 10);
        this.records.complete(timedOut, CallStatus.TIMEOUT, null, "DOWNSTREAM_TIMEOUT",
                "downstream call timed out", TestFixtures.NOW.plusSeconds(30), 30_000);

        assertThat(this.records.findByCallId(errored).orElseThrow().status()).isEqualTo(CallStatus.ERROR);
        assertThat(this.records.findByCallId(errored).orElseThrow().responseJson()).isNull();
        assertThat(this.records.findByCallId(timedOut).orElseThrow().status()).isEqualTo(CallStatus.TIMEOUT);
        assertThat(this.records.findByCallId(timedOut).orElseThrow().durationMs()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("一条记录只能被终结一次，重复 complete 返回 0 行")
    void completeIsNotIdempotentlyOverwritten() {
        String callId = insertStarted("trace-once");
        this.records.complete(callId, CallStatus.SUCCESS, "{}", null, null, TestFixtures.NOW.plusMillis(5), 5);

        int second = this.records.complete(callId, CallStatus.ERROR, null, "DOWNSTREAM_ERROR", "late",
                TestFixtures.NOW.plusMillis(9), 9);

        assertThat(second).isZero();
        assertThat(this.records.findByCallId(callId).orElseThrow().status()).isEqualTo(CallStatus.SUCCESS);
    }

    @Test
    @DisplayName("需求 13.2：启动时把遗留的 STARTED 记录标记为 ERROR")
    void marksStaleStartedRecordsAsError() {
        String stale = insertStarted("trace-stale");
        String finished = insertStarted("trace-finished");
        this.records.complete(finished, CallStatus.SUCCESS, "{}", null, null, TestFixtures.NOW.plusMillis(3), 3);

        int fixed = this.records.markStaleStartedAsError("INTERNAL_ERROR", "gateway restarted", Instant.now());

        assertThat(fixed).isEqualTo(1);
        ToolCallRecord reconciled = this.records.findByCallId(stale).orElseThrow();
        assertThat(reconciled.status()).isEqualTo(CallStatus.ERROR);
        assertThat(reconciled.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(reconciled.finishedAt()).isNotNull();
        // 已经终结的记录不受影响
        assertThat(this.records.findByCallId(finished).orElseThrow().status()).isEqualTo(CallStatus.SUCCESS);
    }

    @Test
    @DisplayName("需求 15.4.3：可按 trace_id 关联同一链路的多条记录")
    void findsRecordsByTraceId() {
        insertStarted("trace-shared");
        insertStarted("trace-shared");
        insertStarted("trace-other");

        assertThat(this.records.findByTraceId("trace-shared")).hasSize(2);
        assertThat(this.records.findByTraceId("trace-other")).hasSize(1);
    }

    @Test
    @DisplayName("超长错误摘要被截断，不会让整次打点写失败")
    void truncatesOverlongErrorMessage() {
        String callId = insertStarted("trace-long");
        String longMessage = "x".repeat(ToolCallRecord.MAX_ERROR_MESSAGE_LENGTH + 500);

        this.records.complete(callId, CallStatus.ERROR, null, "DOWNSTREAM_ERROR", longMessage,
                TestFixtures.NOW.plusMillis(1), 1);

        assertThat(this.records.findByCallId(callId).orElseThrow().errorMessage())
                .hasSize(ToolCallRecord.MAX_ERROR_MESSAGE_LENGTH);
    }

    @Test
    @DisplayName("未知工具的调用可以在 downstream_mcp_id 为空的情况下打点")
    void allowsNullDownstreamForUnknownTool() {
        String callId = TestFixtures.id();
        this.records.insertStarted(ToolCallRecord.started(callId, "trace-unknown", this.gateway.id(), null,
                "kb_a__nope", null, "{}", TestFixtures.NOW));

        this.records.complete(callId, CallStatus.ERROR, null, "TOOL_NOT_FOUND", "tool not found",
                TestFixtures.NOW.plusMillis(1), 1);

        ToolCallRecord record = this.records.findByCallId(callId).orElseThrow();
        assertThat(record.downstreamMcpId()).isNull();
        assertThat(record.originalToolName()).isNull();
        assertThat(record.errorCode()).isEqualTo("TOOL_NOT_FOUND");
    }
}
