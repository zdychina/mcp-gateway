package com.mcpgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 需求 FR-02 的导入校验。 */
class McpServersImportParserTest {

    private static final int MAX = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final McpServersImportParser parser = new McpServersImportParser();

    private JsonNode json(String raw) throws Exception {
        return this.objectMapper.readTree(raw);
    }

    private void assertRejected(String raw, ErrorCode expected) throws Exception {
        JsonNode node = json(raw);
        assertThatThrownBy(() -> this.parser.parse(node, MAX))
                .isInstanceOf(GatewayException.class)
                .extracting(ex -> ((GatewayException) ex).errorCode())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("文档给出的推荐格式可以正常导入")
    void parsesTheDocumentedFormat() throws Exception {
        var parsed = this.parser.parse(json("""
                {
                  "mcpServers": {
                    "knowledge_base_a": {
                      "type": "streamable-http",
                      "url": "https://example.com/mcp",
                      "headers": { "Authorization": "Bearer real-token" }
                    }
                  }
                }
                """), MAX);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.getFirst().name()).isEqualTo("knowledge_base_a");
        assertThat(parsed.getFirst().type()).isEqualTo("streamable-http");
        assertThat(parsed.getFirst().url()).isEqualTo("https://example.com/mcp");
        assertThat(parsed.getFirst().headers()).containsEntry("Authorization", "Bearer real-token");
    }

    @Test
    @DisplayName("一次可以导入 3 个，顺序保持粘贴时的顺序")
    void parsesThreeServersInOrder() throws Exception {
        var parsed = this.parser.parse(json("""
                {"mcpServers": {
                  "kb_c": {"type":"streamable-http","url":"https://c.example.com/mcp"},
                  "kb_a": {"type":"streamable-http","url":"https://a.example.com/mcp"},
                  "kb_b": {"type":"streamable-http","url":"https://b.example.com/mcp"}
                }}
                """), MAX);

        assertThat(parsed).extracting(McpServersImportParser.ParsedDownstream::name)
                .containsExactly("kb_c", "kb_a", "kb_b");
    }

    @Test
    @DisplayName("需求 FR-02：headers 缺省时按空对象处理")
    void defaultsHeadersToEmpty() throws Exception {
        var parsed = this.parser.parse(json("""
                {"mcpServers": {"kb_a": {"type":"streamable-http","url":"https://a.example.com/mcp"}}}
                """), MAX);

        assertThat(parsed.getFirst().headers()).isEmpty();
    }

    @Test
    @DisplayName("需求 15.1.3：导入第 4 个子 MCP 时返回清晰的数量超限错误")
    void rejectsMoreThanTheRemainingQuota() throws Exception {
        String fourServers = """
                {"mcpServers": {
                  "kb_a": {"type":"streamable-http","url":"https://a.example.com/mcp"},
                  "kb_b": {"type":"streamable-http","url":"https://b.example.com/mcp"},
                  "kb_c": {"type":"streamable-http","url":"https://c.example.com/mcp"},
                  "kb_d": {"type":"streamable-http","url":"https://d.example.com/mcp"}
                }}
                """;
        assertRejected(fourServers, ErrorCode.MCP_SERVER_LIMIT_EXCEEDED);

        // 已经有 2 个时，只剩 1 个名额，一次导入 2 个也要被挡住。
        JsonNode two = json("""
                {"mcpServers": {
                  "kb_a": {"type":"streamable-http","url":"https://a.example.com/mcp"},
                  "kb_b": {"type":"streamable-http","url":"https://b.example.com/mcp"}
                }}
                """);
        assertThatThrownBy(() -> this.parser.parse(two, 1))
                .isInstanceOf(GatewayException.class)
                .extracting(ex -> ((GatewayException) ex).errorCode())
                .isEqualTo(ErrorCode.MCP_SERVER_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("需求 15.1.4：stdio 配置被明确拒绝")
    void rejectsStdioConfiguration() throws Exception {
        assertRejected("""
                {"mcpServers": {"local": {"command":"npx","args":["-y","some-mcp"]}}}
                """, ErrorCode.UNSUPPORTED_TRANSPORT);

        assertRejected("""
                {"mcpServers": {"local": {"type":"stdio","command":"node"}}}
                """, ErrorCode.UNSUPPORTED_TRANSPORT);

        // 即便同时写了合法的 type 和 url，只要带 env 就是自相矛盾的配置，不猜用户意图。
        assertRejected("""
                {"mcpServers": {"kb_a": {
                  "type":"streamable-http","url":"https://a.example.com/mcp","env":{"TOKEN":"x"}}}}
                """, ErrorCode.UNSUPPORTED_TRANSPORT);
    }

    @Test
    @DisplayName("需求 15.1.4：旧版 SSE 传输被拒绝")
    void rejectsSseTransport() throws Exception {
        assertRejected("""
                {"mcpServers": {"kb_a": {"type":"sse","url":"https://a.example.com/sse"}}}
                """, ErrorCode.UNSUPPORTED_TRANSPORT);
        assertRejected("""
                {"mcpServers": {"kb_a": {"type":"http","url":"https://a.example.com/sse"}}}
                """, ErrorCode.UNSUPPORTED_TRANSPORT);
    }

    @Test
    @DisplayName("根节点结构不对时报 INVALID_MCP_CONFIG")
    void rejectsMalformedRoot() throws Exception {
        assertRejected("{}", ErrorCode.INVALID_MCP_CONFIG);
        assertRejected("{\"mcpServers\": []}", ErrorCode.INVALID_MCP_CONFIG);
        assertRejected("{\"mcpServers\": {}}", ErrorCode.INVALID_MCP_CONFIG);
        assertRejected("{\"servers\": {\"kb_a\": {}}}", ErrorCode.INVALID_MCP_CONFIG);
        assertRejected("[]", ErrorCode.INVALID_MCP_CONFIG);
    }

    @Test
    @DisplayName("缺 type 或缺 url 时报 INVALID_MCP_CONFIG")
    void rejectsMissingRequiredFields() throws Exception {
        assertRejected("""
                {"mcpServers": {"kb_a": {"url":"https://a.example.com/mcp"}}}
                """, ErrorCode.INVALID_MCP_CONFIG);
        assertRejected("""
                {"mcpServers": {"kb_a": {"type":"streamable-http"}}}
                """, ErrorCode.INVALID_MCP_CONFIG);
        assertRejected("""
                {"mcpServers": {"kb_a": "https://a.example.com/mcp"}}
                """, ErrorCode.INVALID_MCP_CONFIG);
    }

    @Test
    @DisplayName("需求 6.2.3：名称非法或含连续下划线被拒绝")
    void rejectsInvalidServerNames() throws Exception {
        assertRejected("""
                {"mcpServers": {"kb a": {"type":"streamable-http","url":"https://a.example.com/mcp"}}}
                """, ErrorCode.INVALID_MCP_CONFIG);
        assertRejected("""
                {"mcpServers": {"kb/a": {"type":"streamable-http","url":"https://a.example.com/mcp"}}}
                """, ErrorCode.INVALID_MCP_CONFIG);
        // __ 是聚合工具名的分隔符，名字里不能再出现
        assertRejected("""
                {"mcpServers": {"kb__a": {"type":"streamable-http","url":"https://a.example.com/mcp"}}}
                """, ErrorCode.INVALID_MCP_CONFIG);
    }

    @Test
    @DisplayName("需求 12.7：非 http/https 协议与含 user-info 的 URL 被拒绝")
    void rejectsUnsafeUrls() throws Exception {
        assertRejected("""
                {"mcpServers": {"kb_a": {"type":"streamable-http","url":"file:///etc/passwd"}}}
                """, ErrorCode.INVALID_MCP_CONFIG);
        assertRejected("""
                {"mcpServers": {"kb_a": {"type":"streamable-http","url":"ftp://a.example.com/mcp"}}}
                """, ErrorCode.INVALID_MCP_CONFIG);
        assertRejected("""
                {"mcpServers": {"kb_a": {"type":"streamable-http","url":"https://user:pass@a.example.com/mcp"}}}
                """, ErrorCode.INVALID_MCP_CONFIG);
        assertRejected("""
                {"mcpServers": {"kb_a": {"type":"streamable-http","url":"/relative/mcp"}}}
                """, ErrorCode.INVALID_MCP_CONFIG);
    }

    @Test
    @DisplayName("headers 结构不对时报错，且错误信息里不出现 header 的值")
    void rejectsMalformedHeadersWithoutLeakingValues() throws Exception {
        assertRejected("""
                {"mcpServers": {"kb_a": {
                  "type":"streamable-http","url":"https://a.example.com/mcp","headers":"Bearer x"}}}
                """, ErrorCode.INVALID_MCP_CONFIG);

        JsonNode nonStringValue = json("""
                {"mcpServers": {"kb_a": {
                  "type":"streamable-http","url":"https://a.example.com/mcp",
                  "headers":{"Authorization": 12345}}}}
                """);
        assertThatThrownBy(() -> this.parser.parse(nonStringValue, MAX))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Authorization")
                .hasMessageNotContaining("12345");
    }
}
