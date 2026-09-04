package com.mcpgateway.repository;

import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.ToolCallRecord;
import com.mcpgateway.domain.ToolCallSummary;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.Reader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    // ------------------------------------------------------ 查询（需求 FR-06.5）

    /**
     * 列表投影用的列。
     *
     * 刻意不含 request_json / response_json —— 它们按 FR-06.4 原样保存且不截断，
     * 装的是知识库返回的正文。列表一次几十条，把 CLOB 一起捞出来既拖垮响应体，
     * 也让一次请求就能成批捞走业务内容。正文只能按 callId 单条取。
     */
    private static final String SUMMARY_COLUMNS = """
            call_id, trace_id, gateway_id, downstream_mcp_id, exposed_tool_name, original_tool_name,
            status, error_code, error_message, started_at, finished_at, duration_ms
            """;

    private static final RowMapper<ToolCallSummary> SUMMARY_MAPPER = (rs, rowNum) -> {
        long rawDuration = rs.getLong("duration_ms");
        Long durationMs = rs.wasNull() ? null : rawDuration;
        return new ToolCallSummary(
                rs.getString("call_id"),
                rs.getString("trace_id"),
                rs.getString("gateway_id"),
                rs.getString("downstream_mcp_id"),
                rs.getString("exposed_tool_name"),
                rs.getString("original_tool_name"),
                CallStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                Timestamps.fromDb(rs, "started_at"),
                Timestamps.fromDb(rs, "finished_at"),
                durationMs);
    };

    /**
     * 一次列表查询的条件。
     *
     * gatewayId 是必填且永远参与过滤 —— 调用记录按网关隔离，不允许跨网关查询。
     */
    public record CallRecordQuery(
            String gatewayId,
            String downstreamMcpId,
            String toolName,
            CallStatus status,
            String traceId,
            Instant from,
            Instant to,
            int offset,
            int limit) {
    }

    /**
     * 按条件分页查询，最近的在前。
     *
     * 排序带上 call_id 做次级键：同一毫秒内可能有多条记录，只按 started_at 排序时
     * 它们的相对顺序不确定，翻页会看到重复或漏掉的行。
     */
    public List<ToolCallSummary> search(CallRecordQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(query, params, true);
        params.put("limit", query.limit());
        params.put("offset", query.offset());

        return this.jdbcClient.sql("""
                SELECT %s FROM tool_call_record
                %s
                 ORDER BY started_at DESC, call_id DESC
                 LIMIT :limit OFFSET :offset
                """.formatted(SUMMARY_COLUMNS, where))
                .params(params)
                .query(SUMMARY_MAPPER)
                .list();
    }

    /** 满足条件的总条数，供分页使用。 */
    public int count(CallRecordQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(query, params, true);

        Integer total = this.jdbcClient
                .sql("SELECT COUNT(*) FROM tool_call_record\n" + where)
                .params(params)
                .query(Integer.class)
                .single();
        return total == null ? 0 : total;
    }

    /**
     * 各终态的条数分布。
     *
     * 刻意**不**套用 status 这个条件：这是给界面做分面筛选用的，
     * 已经筛了 ERROR 还只显示 ERROR 的数量就没有意义了。其余条件照常生效。
     */
    public Map<CallStatus, Integer> countByStatus(CallRecordQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(query, params, false);

        Map<CallStatus, Integer> counts = new EnumMap<>(CallStatus.class);
        this.jdbcClient.sql("SELECT status, COUNT(*) AS total FROM tool_call_record\n"
                        + where + "\n GROUP BY status")
                .params(params)
                .query((rs, rowNum) ->
                        counts.put(CallStatus.valueOf(rs.getString("status")), rs.getInt("total")))
                .list();
        return counts;
    }

    /**
     * 拼 WHERE 子句。
     *
     * 只有列名是拼进 SQL 的字面量，所有用户输入一律走命名参数 —— 这里绝不能改成
     * 字符串拼接，那就是一个注入点。
     *
     * @param includeStatus 是否把 status 条件算进去，见 {@link #countByStatus}
     */
    private static String whereClause(CallRecordQuery query, Map<String, Object> params,
            boolean includeStatus) {
        List<String> conditions = new ArrayList<>();

        conditions.add("gateway_id = :gatewayId");
        params.put("gatewayId", query.gatewayId());

        if (query.downstreamMcpId() != null) {
            conditions.add("downstream_mcp_id = :downstreamMcpId");
            params.put("downstreamMcpId", query.downstreamMcpId());
        }
        if (query.toolName() != null && !query.toolName().isBlank()) {
            // 工具名做包含匹配，方便按子 MCP 前缀（kb_a__）一次筛出一组
            conditions.add("LOWER(exposed_tool_name) LIKE :toolName");
            params.put("toolName", "%" + query.toolName().trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (includeStatus && query.status() != null) {
            conditions.add("status = :status");
            params.put("status", query.status().name());
        }
        if (query.traceId() != null && !query.traceId().isBlank()) {
            conditions.add("trace_id = :traceId");
            params.put("traceId", query.traceId().trim());
        }
        if (query.from() != null) {
            conditions.add("started_at >= :from");
            params.put("from", Timestamps.toDb(query.from()));
        }
        if (query.to() != null) {
            conditions.add("started_at < :to");
            params.put("to", Timestamps.toDb(query.to()));
        }

        return " WHERE " + String.join("\n   AND ", conditions);
    }

    // ------------------------------------------------ 抽取列（需求 FR-06.5）

    /**
     * 列表抽取列要用的正文切片。
     *
     * <p>正文<b>不会</b>整条读进来：读到上限就停，只留一个"太大了"的结论。
     * 所以这个 record 的内存占用有明确的天花板，见
     * {@link com.mcpgateway.service.CallPayloadExtractor#MAX_PAYLOAD_CHARS}。
     *
     * @param requestTooLarge  入参超过上限，没有读进来
     * @param responseTooLarge 返回超过上限，没有读进来
     */
    public record PayloadSlice(String callId, String requestJson, boolean requestTooLarge,
            String responseJson, boolean responseTooLarge) {
    }

    /**
     * 取一页记录的正文，供抽取列使用。
     *
     * <p>刻意做成<b>独立的第二次查询</b>而不是往 {@link #SUMMARY_COLUMNS} 里加两列：
     * 列表投影里没有正文这条约束依旧成立（SECURITY.md），要读正文就必须显式走这里，
     * 而且带着 gateway_id 一起过滤 —— 抽取列不能成为绕开网关隔离的旁路。
     *
     * @param maxChars 单个字段参与解析的字符上限，超过就只回一个 tooLarge 标记
     */
    public Map<String, PayloadSlice> payloadsForExtraction(String gatewayId, List<String> callIds,
            int maxChars) {
        if (callIds.isEmpty()) {
            return Map.of();
        }

        Map<String, PayloadSlice> slices = new LinkedHashMap<>();
        this.jdbcClient.sql("""
                SELECT call_id, request_json, response_json
                  FROM tool_call_record
                 WHERE gateway_id = :gatewayId AND call_id IN (:callIds)
                """)
                .param("gatewayId", gatewayId)
                .param("callIds", callIds)
                .query((rs, rowNum) -> {
                    // 流必须按列的先后顺序读：有些驱动在读了后面的列之后就不让回头读前面的流了
                    Capped request = readCapped(rs, "request_json", maxChars);
                    Capped response = readCapped(rs, "response_json", maxChars);
                    return slices.put(rs.getString("call_id"), new PayloadSlice(
                            rs.getString("call_id"),
                            request.text(), request.tooLarge(),
                            response.text(), response.tooLarge()));
                })
                .list();
        return slices;
    }

    /** 读到上限就停下的结果。text 在 tooLarge 时为 null —— 超限的内容一个字符都不留。 */
    private record Capped(String text, boolean tooLarge) {

        static final Capped ABSENT = new Capped(null, false);
    }

    /**
     * 按字符流读一列，最多读 maxChars 个字符。
     *
     * <p>不用 {@code rs.getString}：那会把整条 CLOB 拉进内存，而单条正文可能接近 1 MiB，
     * 一页 100 条就是几百兆。多读一个字符是为了区分"正好到上限"和"还有更多"。
     */
    private static Capped readCapped(ResultSet rs, String column, int maxChars) throws SQLException {
        try (Reader reader = rs.getCharacterStream(column)) {
            if (reader == null) {
                return Capped.ABSENT;
            }
            char[] buffer = new char[maxChars + 1];
            int total = 0;
            while (total < buffer.length) {
                int read = reader.read(buffer, total, buffer.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            return total > maxChars ? new Capped(null, true) : new Capped(new String(buffer, 0, total), false);
        }
        catch (IOException ex) {
            throw new SQLException("failed to read " + column, ex);
        }
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
