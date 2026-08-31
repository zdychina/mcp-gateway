package com.mcpgateway.repository;

import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.SyncStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** downstream_mcp 表访问（需求 §9.2）。 */
@Repository
public class DownstreamMcpRepository {

    private static final String COLUMNS = """
            id, gateway_id, name, type, url, encrypted_headers_json,
            sync_status, last_sync_at, last_sync_error, created_at, updated_at
            """;

    private static final RowMapper<DownstreamMcp> ROW_MAPPER = (rs, rowNum) -> new DownstreamMcp(
            rs.getString("id"),
            rs.getString("gateway_id"),
            rs.getString("name"),
            rs.getString("type"),
            rs.getString("url"),
            rs.getString("encrypted_headers_json"),
            SyncStatus.valueOf(rs.getString("sync_status")),
            Timestamps.fromDb(rs, "last_sync_at"),
            rs.getString("last_sync_error"),
            Timestamps.fromDb(rs, "created_at"),
            Timestamps.fromDb(rs, "updated_at"));

    private final JdbcClient jdbcClient;

    public DownstreamMcpRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(DownstreamMcp downstream) {
        this.jdbcClient.sql("""
                INSERT INTO downstream_mcp (id, gateway_id, name, type, url, encrypted_headers_json,
                                            sync_status, last_sync_at, last_sync_error, created_at, updated_at)
                VALUES (:id, :gatewayId, :name, :type, :url, :encryptedHeadersJson,
                        :syncStatus, :lastSyncAt, :lastSyncError, :createdAt, :updatedAt)
                """)
                .param("id", downstream.id())
                .param("gatewayId", downstream.gatewayId())
                .param("name", downstream.name())
                .param("type", downstream.type())
                .param("url", downstream.url())
                .param("encryptedHeadersJson", downstream.encryptedHeadersJson())
                .param("syncStatus", downstream.syncStatus().name())
                .param("lastSyncAt", Timestamps.toDb(downstream.lastSyncAt()))
                .param("lastSyncError", downstream.lastSyncError())
                .param("createdAt", Timestamps.toDb(downstream.createdAt()))
                .param("updatedAt", Timestamps.toDb(downstream.updatedAt()))
                .update();
    }

    /** 更新配置。同步状态由 {@link #updateSyncResult} 单独维护，避免两条语义互相覆盖。 */
    public int updateConfig(String id, String name, String url, String encryptedHeadersJson, Instant updatedAt) {
        return this.jdbcClient.sql("""
                UPDATE downstream_mcp
                   SET name = :name, url = :url, encrypted_headers_json = :encryptedHeadersJson,
                       updated_at = :updatedAt
                 WHERE id = :id
                """)
                .param("id", id)
                .param("name", name)
                .param("url", url)
                .param("encryptedHeadersJson", encryptedHeadersJson)
                .param("updatedAt", Timestamps.toDb(updatedAt))
                .update();
    }

    /**
     * 需求 6.4.7：同步失败时保留上一次成功快照，只把子 MCP 标记为异常。
     * 成功时 lastSyncError 传 null 清空上一次的错误。
     */
    public int updateSyncResult(String id, SyncStatus status, Instant lastSyncAt, String lastSyncError,
            Instant updatedAt) {
        return this.jdbcClient.sql("""
                UPDATE downstream_mcp
                   SET sync_status = :syncStatus, last_sync_at = :lastSyncAt,
                       last_sync_error = :lastSyncError, updated_at = :updatedAt
                 WHERE id = :id
                """)
                .param("id", id)
                .param("syncStatus", status.name())
                .param("lastSyncAt", Timestamps.toDb(lastSyncAt))
                .param("lastSyncError", lastSyncError)
                .param("updatedAt", Timestamps.toDb(updatedAt))
                .update();
    }

    public Optional<DownstreamMcp> findById(String id) {
        return this.jdbcClient.sql("SELECT " + COLUMNS + " FROM downstream_mcp WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    public List<DownstreamMcp> findByGatewayId(String gatewayId) {
        return this.jdbcClient
                .sql("SELECT " + COLUMNS + " FROM downstream_mcp WHERE gateway_id = :gatewayId ORDER BY name")
                .param("gatewayId", gatewayId)
                .query(ROW_MAPPER)
                .list();
    }

    /** 列表页一次取全量，避免按网关逐个查询。MVP 规模下子 MCP 总数很小。 */
    public List<DownstreamMcp> findAll() {
        return this.jdbcClient.sql("SELECT " + COLUMNS + " FROM downstream_mcp ORDER BY gateway_id, name")
                .query(ROW_MAPPER)
                .list();
    }

    /** 需求 6.2.1：导入前用它判断加上待导入的数量是否会超过上限。 */
    public int countByGatewayId(String gatewayId) {
        Integer count = this.jdbcClient
                .sql("SELECT COUNT(*) FROM downstream_mcp WHERE gateway_id = :gatewayId")
                .param("gatewayId", gatewayId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    /** 需求 6.2.2：同一网关内名称唯一且区分大小写。 */
    public boolean existsByGatewayIdAndName(String gatewayId, String name) {
        Integer count = this.jdbcClient.sql("""
                SELECT COUNT(*) FROM downstream_mcp WHERE gateway_id = :gatewayId AND name = :name
                """)
                .param("gatewayId", gatewayId)
                .param("name", name)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /** 需求 6.2.10：删除子 MCP，其工具快照由外键级联移除。 */
    public int deleteById(String id) {
        return this.jdbcClient.sql("DELETE FROM downstream_mcp WHERE id = :id")
                .param("id", id)
                .update();
    }
}
