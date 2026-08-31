package com.mcpgateway.repository;

import com.mcpgateway.domain.GatewayTool;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** gateway_tool 表访问（需求 §9.3）。 */
@Repository
public class GatewayToolRepository {

    private static final String COLUMNS = """
            id, gateway_id, downstream_mcp_id, original_name, exposed_name,
            original_description, custom_description, input_schema_json, output_schema_json,
            annotations_json, enabled, definition_hash, last_synced_at, created_at, updated_at
            """;

    private static final RowMapper<GatewayTool> ROW_MAPPER = (rs, rowNum) -> new GatewayTool(
            rs.getString("id"),
            rs.getString("gateway_id"),
            rs.getString("downstream_mcp_id"),
            rs.getString("original_name"),
            rs.getString("exposed_name"),
            rs.getString("original_description"),
            rs.getString("custom_description"),
            rs.getString("input_schema_json"),
            rs.getString("output_schema_json"),
            rs.getString("annotations_json"),
            rs.getBoolean("enabled"),
            rs.getString("definition_hash"),
            Timestamps.fromDb(rs, "last_synced_at"),
            Timestamps.fromDb(rs, "created_at"),
            Timestamps.fromDb(rs, "updated_at"));

    private final JdbcClient jdbcClient;

    public GatewayToolRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(GatewayTool tool) {
        this.jdbcClient.sql("""
                INSERT INTO gateway_tool (id, gateway_id, downstream_mcp_id, original_name, exposed_name,
                                          original_description, custom_description, input_schema_json,
                                          output_schema_json, annotations_json, enabled, definition_hash,
                                          last_synced_at, created_at, updated_at)
                VALUES (:id, :gatewayId, :downstreamMcpId, :originalName, :exposedName,
                        :originalDescription, :customDescription, :inputSchemaJson,
                        :outputSchemaJson, :annotationsJson, :enabled, :definitionHash,
                        :lastSyncedAt, :createdAt, :updatedAt)
                """)
                .param("id", tool.id())
                .param("gatewayId", tool.gatewayId())
                .param("downstreamMcpId", tool.downstreamMcpId())
                .param("originalName", tool.originalName())
                .param("exposedName", tool.exposedName())
                .param("originalDescription", tool.originalDescription())
                .param("customDescription", tool.customDescription())
                .param("inputSchemaJson", tool.inputSchemaJson())
                .param("outputSchemaJson", tool.outputSchemaJson())
                .param("annotationsJson", tool.annotationsJson())
                .param("enabled", tool.enabled())
                .param("definitionHash", tool.definitionHash())
                .param("lastSyncedAt", Timestamps.toDb(tool.lastSyncedAt()))
                .param("createdAt", Timestamps.toDb(tool.createdAt()))
                .param("updatedAt", Timestamps.toDb(tool.updatedAt()))
                .update();
    }

    /**
     * 需求 6.4.5：重新同步时用最新结果覆盖协议字段。
     *
     * 这里刻意**不**触碰 enabled 和 custom_description —— 那两列是操作人的配置，
     * 需求 6.4.4 要求同步时保留。把它们排除在 SQL 之外，比靠调用方记得传原值更可靠。
     */
    public int updateProtocolFields(String id, String originalDescription, String inputSchemaJson,
            String outputSchemaJson, String annotationsJson, String definitionHash, Instant lastSyncedAt,
            Instant updatedAt) {
        return this.jdbcClient.sql("""
                UPDATE gateway_tool
                   SET original_description = :originalDescription,
                       input_schema_json = :inputSchemaJson,
                       output_schema_json = :outputSchemaJson,
                       annotations_json = :annotationsJson,
                       definition_hash = :definitionHash,
                       last_synced_at = :lastSyncedAt,
                       updated_at = :updatedAt
                 WHERE id = :id
                """)
                .param("id", id)
                .param("originalDescription", originalDescription)
                .param("inputSchemaJson", inputSchemaJson)
                .param("outputSchemaJson", outputSchemaJson)
                .param("annotationsJson", annotationsJson)
                .param("definitionHash", definitionHash)
                .param("lastSyncedAt", Timestamps.toDb(lastSyncedAt))
                .param("updatedAt", Timestamps.toDb(updatedAt))
                .update();
    }

    /**
     * 定义没有变化时只推进同步时间。
     *
     * 不复用 updateProtocolFields 是因为那会把几个 CLOB 原样重写一遍，还会推高 updated_at，
     * 让前端把一次什么都没变的同步显示成一次修改。
     */
    public int touchLastSyncedAt(String id, Instant lastSyncedAt) {
        return this.jdbcClient.sql("""
                UPDATE gateway_tool SET last_synced_at = :lastSyncedAt WHERE id = :id
                """)
                .param("id", id)
                .param("lastSyncedAt", Timestamps.toDb(lastSyncedAt))
                .update();
    }

    /** 需求 6.5：启停开关。 */
    public int updateEnabled(String id, boolean enabled, Instant updatedAt) {
        return this.jdbcClient.sql("""
                UPDATE gateway_tool SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id
                """)
                .param("id", id)
                .param("enabled", enabled)
                .param("updatedAt", Timestamps.toDb(updatedAt))
                .update();
    }

    /** 需求 6.5.5：自定义描述，传 null 表示清除并回退到原始描述。 */
    public int updateCustomDescription(String id, String customDescription, Instant updatedAt) {
        return this.jdbcClient.sql("""
                UPDATE gateway_tool
                   SET custom_description = :customDescription, updated_at = :updatedAt
                 WHERE id = :id
                """)
                .param("id", id)
                .param("customDescription", customDescription)
                .param("updatedAt", Timestamps.toDb(updatedAt))
                .update();
    }

    /** 子 MCP 改名后重算聚合名（需求 6.3.6 的破坏性变更）。 */
    public int updateExposedName(String id, String exposedName, Instant updatedAt) {
        return this.jdbcClient.sql("""
                UPDATE gateway_tool SET exposed_name = :exposedName, updated_at = :updatedAt WHERE id = :id
                """)
                .param("id", id)
                .param("exposedName", exposedName)
                .param("updatedAt", Timestamps.toDb(updatedAt))
                .update();
    }

    public Optional<GatewayTool> findById(String id) {
        return this.jdbcClient.sql("SELECT " + COLUMNS + " FROM gateway_tool WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * 需求 6.3.1 / 6.6.1：tools/call 的路由入口。
     * 按 (gateway_id, exposed_name) 精确定位，绝不在调用时拆 "__" 猜目标。
     */
    public Optional<GatewayTool> findByGatewayIdAndExposedName(String gatewayId, String exposedName) {
        return this.jdbcClient.sql("""
                SELECT %s FROM gateway_tool WHERE gateway_id = :gatewayId AND exposed_name = :exposedName
                """.formatted(COLUMNS))
                .param("gatewayId", gatewayId)
                .param("exposedName", exposedName)
                .query(ROW_MAPPER)
                .optional();
    }

    public List<GatewayTool> findByGatewayId(String gatewayId) {
        return this.jdbcClient.sql("""
                SELECT %s FROM gateway_tool WHERE gateway_id = :gatewayId ORDER BY exposed_name
                """.formatted(COLUMNS))
                .param("gatewayId", gatewayId)
                .query(ROW_MAPPER)
                .list();
    }

    /** 需求 6.4.1 / FR-04：tools/list 只返回已启用工具，直接从快照读。 */
    public List<GatewayTool> findEnabledByGatewayId(String gatewayId) {
        return this.jdbcClient.sql("""
                SELECT %s FROM gateway_tool
                 WHERE gateway_id = :gatewayId AND enabled = TRUE
                 ORDER BY exposed_name
                """.formatted(COLUMNS))
                .param("gatewayId", gatewayId)
                .query(ROW_MAPPER)
                .list();
    }

    public List<GatewayTool> findByDownstreamMcpId(String downstreamMcpId) {
        return this.jdbcClient.sql("""
                SELECT %s FROM gateway_tool WHERE downstream_mcp_id = :downstreamMcpId ORDER BY original_name
                """.formatted(COLUMNS))
                .param("downstreamMcpId", downstreamMcpId)
                .query(ROW_MAPPER)
                .list();
    }

    /** 需求 6.4.6：同步成功后移除下游已删除的工具。 */
    public int deleteByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return this.jdbcClient.sql("DELETE FROM gateway_tool WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }

    /** 列表页用：一次取回每个网关的工具总数。 */
    public Map<String, Integer> countByGatewayGrouped() {
        Map<String, Integer> counts = new HashMap<>();
        this.jdbcClient.sql("SELECT gateway_id, COUNT(*) AS total FROM gateway_tool GROUP BY gateway_id")
                .query((rs, rowNum) -> counts.put(rs.getString("gateway_id"), rs.getInt("total")))
                .list();
        return counts;
    }

    public int countByGatewayIdAndEnabled(String gatewayId, boolean enabled) {
        Integer count = this.jdbcClient.sql("""
                SELECT COUNT(*) FROM gateway_tool WHERE gateway_id = :gatewayId AND enabled = :enabled
                """)
                .param("gatewayId", gatewayId)
                .param("enabled", enabled)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }
}
