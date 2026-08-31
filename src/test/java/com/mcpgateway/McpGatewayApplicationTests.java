package com.mcpgateway;

import com.mcpgateway.config.GatewayProperties;
import com.mcpgateway.security.AesGcmCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class McpGatewayApplicationTests extends AbstractDataTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private GatewayProperties properties;

    @Autowired
    private AesGcmCipher cipher;

    @Test
    @DisplayName("上下文可启动，加密组件和配置都已装配")
    void contextLoads() {
        assertThat(this.cipher).isNotNull();
        assertThat(this.properties.getBaseUrl()).isNotBlank();
        assertThat(this.properties.getDownstream().getCallTimeout().toSeconds()).isEqualTo(30);
        assertThat(this.properties.getServer().getMaxDownstreamPerGateway()).isEqualTo(3);
        assertThat(this.properties.getServer().getMaxRequestSize()).isEqualTo(1024 * 1024);
    }

    @Test
    @DisplayName("Flyway 迁移建出了 §9 要求的四张表")
    void migrationCreatesAllTables() {
        for (String table : new String[] { "MCP_GATEWAY", "DOWNSTREAM_MCP", "GATEWAY_TOOL", "TOOL_CALL_RECORD" }) {
            Integer count = this.jdbcClient.sql("""
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                     WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = :table
                    """)
                    .param("table", table)
                    .query(Integer.class)
                    .single();
            assertThat(count).as("table %s should exist", table).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("需求 9.4：调用记录建了按网关和时间检索所需的索引")
    void migrationCreatesCallRecordIndexes() {
        Integer indexes = this.jdbcClient.sql("""
                SELECT COUNT(DISTINCT INDEX_NAME) FROM INFORMATION_SCHEMA.INDEXES
                 WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'TOOL_CALL_RECORD'
                   AND INDEX_NAME IN ('IX_TOOL_CALL_RECORD_GATEWAY_STARTED', 'IX_TOOL_CALL_RECORD_TRACE')
                """)
                .query(Integer.class)
                .single();
        assertThat(indexes).isEqualTo(2);
    }
}
