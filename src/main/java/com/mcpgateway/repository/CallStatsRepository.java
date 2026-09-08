package com.mcpgateway.repository;

import com.mcpgateway.domain.CallStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * tool_call_record 上的聚合查询（统计页）。
 *
 * <p>和 {@link ToolCallRecordRepository} 读的是同一张表，但刻意分开：那个类负责打点写入和
 * 单条/分页读取，每个方法都映射出完整的记录；这里只做 GROUP BY 和计数，<b>一行业务内容都不取</b>
 * —— request_json / response_json 在这里连出现在 SELECT 里的机会都没有，
 * 统计接口因此完全不在 SECURITY.md 那条"正文出口"的讨论范围内。
 *
 * <p>时间桶用 UTC 切（库里存的就是 UTC），所以整点桶在任何整小时时区里都还是整点。
 * 印度这类半小时偏移的时区看到的桶边界会落在半点上，是已知且可接受的偏差。
 */
@Repository
public class CallStatsRepository {

    /** 时间桶粒度。取值直接拼进 SQL，所以必须是枚举而不是字符串。 */
    public enum Bucket {
        HOUR,
        DAY
    }

    /**
     * 一次统计查询的范围。
     *
     * @param gatewayId 为空表示跨全部网关
     * @param from      起始时间，含
     * @param to        结束时间，不含
     */
    public record Window(String gatewayId, Instant from, Instant to) {
    }

    /**
     * 一个分组的聚合结果。key 是 gateway_id / downstream_mcp_id / 工具名 / 错误码。
     *
     * successes 是真正的 SUCCESS 条数，不能用 calls - failures 去推 ——
     * 那样会把还没结束的 STARTED 算成成功，同一页上的成功率就会自相矛盾。
     */
    public record Grouped(String key, int calls, int successes, int failures,
            Double avgDurationMs, Double p95DurationMs) {
    }

    /** 时间序列上的一个点：某个桶里某个状态的条数。 */
    public record SeriesRow(Instant at, CallStatus status, int count) {
    }

    /*
     * 每个分组都要的四个指标。
     *
     * PERCENTILE_CONT 在 H2 2.x 里是标准聚合函数；duration_ms 为 NULL 的记录（还没结束的
     * STARTED）会被 AVG 和 PERCENTILE_CONT 一起忽略，这正是想要的 —— 把"还没跑完"算成 0 毫秒
     * 会把 P95 拉低到没法看。
     */
    private static final String METRICS = """
            COUNT(*) AS calls,
            COUNT(CASE WHEN status = 'SUCCESS' THEN 1 END) AS successes,
            COUNT(CASE WHEN status IN ('ERROR', 'TIMEOUT') THEN 1 END) AS failures,
            AVG(duration_ms) AS avg_ms,
            PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95_ms
            """;

    private final JdbcClient jdbcClient;

    public CallStatsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 各状态的条数。 */
    public Map<CallStatus, Integer> countByStatus(Window window) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(window, params);

        Map<CallStatus, Integer> counts = new EnumMap<>(CallStatus.class);
        this.jdbcClient.sql("SELECT status, COUNT(*) AS total FROM tool_call_record\n"
                        + where + "\n GROUP BY status")
                .params(params)
                .query((rs, rowNum) ->
                        counts.put(CallStatus.valueOf(rs.getString("status")), rs.getInt("total")))
                .list();
        return counts;
    }

    /** 整个窗口的总量与耗时分位。 */
    public Grouped totals(Window window) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(window, params);

        return this.jdbcClient.sql("SELECT " + METRICS + " FROM tool_call_record\n" + where)
                .params(params)
                .query((rs, rowNum) -> new Grouped(null, rs.getInt("calls"),
                        rs.getInt("successes"), rs.getInt("failures"),
                        nullableDouble(rs.getBigDecimal("avg_ms")),
                        nullableDouble(rs.getBigDecimal("p95_ms"))))
                .single();
    }

    /**
     * 按时间桶和状态分组。
     *
     * 只返回有数据的桶，把空桶补齐是调用方的事 —— 服务端补更省事，但那样这个方法就得知道
     * "整个窗口有多少个桶"，而那是展示层的问题。
     */
    public List<SeriesRow> series(Window window, Bucket bucket) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(window, params);

        List<SeriesRow> rows = new ArrayList<>();
        // bucket 是枚举，不是用户输入，可以安全地拼进 SQL；其余一切仍走命名参数
        this.jdbcClient.sql("""
                SELECT DATE_TRUNC('%s', started_at) AS at, status, COUNT(*) AS total
                  FROM tool_call_record
                %s
                 GROUP BY DATE_TRUNC('%s', started_at), status
                 ORDER BY at
                """.formatted(bucket.name(), where, bucket.name()))
                .params(params)
                .query((rs, rowNum) -> rows.add(new SeriesRow(Timestamps.fromDb(rs, "at"),
                        CallStatus.valueOf(rs.getString("status")), rs.getInt("total"))))
                .list();
        return rows;
    }

    /** 按网关分组。 */
    public List<Grouped> byGateway(Window window) {
        return grouped(window, "gateway_id", 0);
    }

    /** 按子 MCP 分组。未知工具和停用工具的调用没有目标，不参与分组。 */
    public List<Grouped> byDownstream(Window window) {
        return grouped(window, "downstream_mcp_id", 0);
    }

    /** 按聚合工具名分组，只取调用量最高的几个。 */
    public List<Grouped> byTool(Window window, int limit) {
        return grouped(window, "exposed_tool_name", limit);
    }

    /** 按错误码分组，只统计失败的记录。 */
    public List<Grouped> byErrorCode(Window window, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(window, params) + "\n   AND error_code IS NOT NULL";
        params.put("limit", limit);

        return this.jdbcClient.sql("""
                SELECT error_code AS k, COUNT(*) AS calls, 0 AS successes, COUNT(*) AS failures,
                       AVG(duration_ms) AS avg_ms,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95_ms
                  FROM tool_call_record
                %s
                 GROUP BY error_code
                 ORDER BY calls DESC, k
                 LIMIT :limit
                """.formatted(where))
                .params(params)
                .query(CallStatsRepository::mapGrouped)
                .list();
    }

    /**
     * 按某一列分组。
     *
     * @param column 列名，只能是本类里写死的那几个字面量，绝不接受调用方传入的字符串
     * @param limit  0 表示不限
     */
    private List<Grouped> grouped(Window window, String column, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(window, params) + "\n   AND " + column + " IS NOT NULL";
        String limitClause = "";
        if (limit > 0) {
            limitClause = "\n LIMIT :limit";
            params.put("limit", limit);
        }

        return this.jdbcClient.sql("""
                SELECT %s AS k, %s FROM tool_call_record
                %s
                 GROUP BY %s
                 ORDER BY calls DESC, k%s
                """.formatted(column, METRICS, where, column, limitClause))
                .params(params)
                .query(CallStatsRepository::mapGrouped)
                .list();
    }

    private static Grouped mapGrouped(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Grouped(rs.getString("k"), rs.getInt("calls"), rs.getInt("successes"),
                rs.getInt("failures"),
                nullableDouble(rs.getBigDecimal("avg_ms")),
                nullableDouble(rs.getBigDecimal("p95_ms")));
    }

    private static Double nullableDouble(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /**
     * 时间窗和网关过滤。
     *
     * 与 {@link ToolCallRecordRepository} 一样：只有列名是字面量，一切用户输入都走命名参数。
     */
    private static String whereClause(Window window, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        conditions.add("started_at >= :from");
        params.put("from", Timestamps.toDb(window.from()));
        conditions.add("started_at < :to");
        params.put("to", Timestamps.toDb(window.to()));

        if (window.gatewayId() != null && !window.gatewayId().isBlank()) {
            conditions.add("gateway_id = :gatewayId");
            params.put("gatewayId", window.gatewayId());
        }
        return " WHERE " + String.join("\n   AND ", conditions);
    }
}
