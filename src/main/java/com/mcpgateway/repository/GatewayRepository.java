package com.mcpgateway.repository;

import com.mcpgateway.domain.Gateway;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** mcp_gateway 表访问（需求 §9.1）。 */
@Repository
public class GatewayRepository {

    private static final String COLUMNS =
            "id, name, slug, description, access_token_hash, created_at, updated_at";

    private static final RowMapper<Gateway> ROW_MAPPER = (rs, rowNum) -> new Gateway(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("slug"),
            rs.getString("description"),
            rs.getString("access_token_hash"),
            Timestamps.fromDb(rs, "created_at"),
            Timestamps.fromDb(rs, "updated_at"));

    private final JdbcClient jdbcClient;

    public GatewayRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(Gateway gateway) {
        this.jdbcClient.sql("""
                INSERT INTO mcp_gateway (id, name, slug, description, access_token_hash, created_at, updated_at)
                VALUES (:id, :name, :slug, :description, :accessTokenHash, :createdAt, :updatedAt)
                """)
                .param("id", gateway.id())
                .param("name", gateway.name())
                .param("slug", gateway.slug())
                .param("description", gateway.description())
                .param("accessTokenHash", gateway.accessTokenHash())
                .param("createdAt", Timestamps.toDb(gateway.createdAt()))
                .param("updatedAt", Timestamps.toDb(gateway.updatedAt()))
                .update();
    }

    /** 只更新操作人可编辑的字段。令牌哈希走 {@link #updateAccessTokenHash}。 */
    public int update(String id, String name, String slug, String description, Instant updatedAt) {
        return this.jdbcClient.sql("""
                UPDATE mcp_gateway
                   SET name = :name, slug = :slug, description = :description, updated_at = :updatedAt
                 WHERE id = :id
                """)
                .param("id", id)
                .param("name", name)
                .param("slug", slug)
                .param("description", description)
                .param("updatedAt", Timestamps.toDb(updatedAt))
                .update();
    }

    /** 需求 FR-05.3：令牌轮换。旧哈希被直接覆盖，没有过渡期。 */
    public int updateAccessTokenHash(String id, String accessTokenHash, Instant updatedAt) {
        return this.jdbcClient.sql("""
                UPDATE mcp_gateway
                   SET access_token_hash = :accessTokenHash, updated_at = :updatedAt
                 WHERE id = :id
                """)
                .param("id", id)
                .param("accessTokenHash", accessTokenHash)
                .param("updatedAt", Timestamps.toDb(updatedAt))
                .update();
    }

    public Optional<Gateway> findById(String id) {
        return this.jdbcClient.sql("SELECT " + COLUMNS + " FROM mcp_gateway WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    /** MCP 端点按 slug 定位网关，这是热路径。 */
    public Optional<Gateway> findBySlug(String slug) {
        return this.jdbcClient.sql("SELECT " + COLUMNS + " FROM mcp_gateway WHERE slug = :slug")
                .param("slug", slug)
                .query(ROW_MAPPER)
                .optional();
    }

    public List<Gateway> findAll() {
        return this.jdbcClient.sql("SELECT " + COLUMNS + " FROM mcp_gateway ORDER BY created_at DESC")
                .query(ROW_MAPPER)
                .list();
    }

    public boolean existsBySlug(String slug) {
        Integer count = this.jdbcClient.sql("SELECT COUNT(*) FROM mcp_gateway WHERE slug = :slug")
                .param("slug", slug)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /** 判断 slug 是否被**别的**网关占用，供更新时校验。 */
    public boolean existsBySlugAndIdNot(String slug, String id) {
        Integer count = this.jdbcClient
                .sql("SELECT COUNT(*) FROM mcp_gateway WHERE slug = :slug AND id <> :id")
                .param("slug", slug)
                .param("id", id)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /** 需求 6.1.7：级联删除子 MCP、工具快照和调用记录，由外键 ON DELETE CASCADE 完成。 */
    public int deleteById(String id) {
        return this.jdbcClient.sql("DELETE FROM mcp_gateway WHERE id = :id")
                .param("id", id)
                .update();
    }
}
