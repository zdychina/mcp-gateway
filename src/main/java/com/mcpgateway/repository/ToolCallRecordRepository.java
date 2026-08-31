package com.mcpgateway.repository;

import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.ToolCallRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * tool_call_record 表访问（需求 FR-06）。
 *
 * 打点是两阶段的：调用开始写 STARTED，结束更新终态。调用方（W6 的打点服务）必须把这两次
 * 写入放在**独立事务**里 —— 否则下游调用成功但打点回滚，会违反 FR-06.2
 * "日志写入失败不得改变一次已经成功的下游调用结果"。
 */
@Repository
public class ToolCallRecordRepository {

    private static final String COLUMNS = """
            call_id, trace_id, gateway_id, downstream_mcp_id, exposed_tool_name, original_tool_name,
            request_json, response_json, status, error_code, error_message,
            started_at, finished_at, duration_ms
            """;

    private static final RowMapper<ToolCallRecord> ROW_MAPPER = (rs, rowNum) -> {
        // wasNull() 描述的是最近一次读取的列，所以必须紧挨着 getLong 取。
        long rawDuration = rs.getLong("duration_ms");
        Long durationMs = rs.wasNull() ? null : rawDuration;
        return new ToolCallRecord(
                rs.getString("call_id"),
                rs.getString("trace_id"),
                rs.getString("gateway_id"),
                rs.getString("downstream_mcp_id"),
                rs.getString("exposed_tool_name"),
                rs.getString("original_tool_name"),
                rs.getString("request_json"),
                rs.getString("response_json"),
                CallStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                Timestamps.fromDb(rs, "started_at"),
                Timestamps.fromDb(rs, "finished_at"),
                durationMs);
    };

    private final JdbcClient jdbcClient;

    public ToolCallRecordRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 需求 FR-06.1：调用开始时先写入 STARTED。 */
    public void insertStarted(ToolCallRecord record) {
        this.jdbcClient.sql("""
                INSERT INTO tool_call_record (call_id, trace_id, gateway_id, downstream_mcp_id,
                                              exposed_tool_name, original_tool_name, request_json,
                                              response_json, status, error_code, error_message,
                                              started_at, finished_at, duration_ms)
                VALUES (:callId, :traceId, :gatewayId, :downstreamMcpId,
                        :exposedToolName, :originalToolName, :requestJson,
                        NULL, :status, NULL, NULL,
                        :startedAt, NULL, NULL)
                """)
                .param("callId", record.callId())
                .param("traceId", record.traceId())
                .param("gatewayId", record.gatewayId())
                .param("downstreamMcpId", record.downstreamMcpId())
                .param("exposedToolName", record.exposedToolName())
                .param("originalToolName", record.originalToolName())
                .param("requestJson", record.requestJson())
                .param("status", CallStatus.STARTED.name())
                .param("startedAt", Timestamps.toDb(record.startedAt()))
                .update();
    }

    /**
     * 需求 FR-06.1：结束时更新最终状态。
     *
     * WHERE 带上 status = 'STARTED'，保证一条记录只会被终结一次；重复调用返回 0 行，
     * 由调用方决定是否告警。
     */
    public int complete(String callId, CallStatus status, String responseJson, String errorCode,
            String errorMessage, Instant finishedAt, long durationMs) {
        return this.jdbcClient.sql("""
                UPDATE tool_call_record
                   SET status = :status, response_json = :responseJson, error_code = :errorCode,
                       error_message = :errorMessage, finished_at = :finishedAt, duration_ms = :durationMs
                 WHERE call_id = :callId AND status = 'STARTED'
                """)
                .param("callId", callId)
                .param("status", status.name())
                .param("responseJson", responseJson)
                .param("errorCode", errorCode)
                .param("errorMessage", truncateErrorMessage(errorMessage))
                .param("finishedAt", Timestamps.toDb(finishedAt))
                .param("durationMs", durationMs)
                .update();
    }

    /**
     * 需求 13.2：服务异常退出遗留的 STARTED 记录，在下次启动时标记为 ERROR。
     *
     * @return 被修正的记录数
     */
    public int markStaleStartedAsError(String errorCode, String errorMessage, Instant finishedAt) {
        return this.jdbcClient.sql("""
                UPDATE tool_call_record
                   SET status = 'ERROR', error_code = :errorCode, error_message = :errorMessage,
                       finished_at = :finishedAt,
                       duration_ms = COALESCE(duration_ms, 0)
                 WHERE status = 'STARTED'
                """)
                .param("errorCode", errorCode)
                .param("errorMessage", truncateErrorMessage(errorMessage))
                .param("finishedAt", Timestamps.toDb(finishedAt))
                .update();
    }

    public Optional<ToolCallRecord> findByCallId(String callId) {
        return this.jdbcClient.sql("SELECT " + COLUMNS + " FROM tool_call_record WHERE call_id = :callId")
                .param("callId", callId)
                .query(ROW_MAPPER)
                .optional();
    }

    /** 需求 15.4.3：记录可以通过 call_id 和 trace_id 唯一关联。 */
    public List<ToolCallRecord> findByTraceId(String traceId) {
        return this.jdbcClient.sql("""
                SELECT %s FROM tool_call_record WHERE trace_id = :traceId ORDER BY started_at
                """.formatted(COLUMNS))
                .param("traceId", traceId)
                .query(ROW_MAPPER)
                .list();
    }

    /** error_message 列宽 1000，超长直接截断，避免一条异常摘要把整次打点写失败。 */
    private static String truncateErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        return errorMessage.length() <= ToolCallRecord.MAX_ERROR_MESSAGE_LENGTH
                ? errorMessage
                : errorMessage.substring(0, ToolCallRecord.MAX_ERROR_MESSAGE_LENGTH);
    }
}
