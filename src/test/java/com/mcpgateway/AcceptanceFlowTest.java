package com.mcpgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.downstream.MockDownstreamConfig;
import com.mcpgateway.downstream.MockDownstreamMcpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 需求 §2.2 的成功标准：把那七步演示流程完整走一遍。
 *
 * 与其他端到端测试的分工：那些各自验证一个模块的行为，这个只回答一个问题 ——
 * 一个操作人从零开始，能不能一路走到"Agent 调用成功且库里有记录"。
 * 所以它全程只用对外接口：管理 API、页面、以及官方 MCP 客户端。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { com.mcpgateway.McpGatewayApplication.class, MockDownstreamConfig.class })
@ActiveProfiles("test")
class AcceptanceFlowTest {

    private static final String DOWNSTREAM_TOKEN = "sk-knowledge-base-credential";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mcp-gateway.security.master-key", () -> TestMasterKey.BASE64);
        TestAdminCredentials.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockDownstreamMcpServer mockKbA;

    @Autowired
    private MockDownstreamMcpServer mockKbB;

    private final List<McpSyncClient> openedClients = new ArrayList<>();

    @BeforeEach
    void resetDownstreams() {
        this.mockKbA.reset(List.of("search", "ping"));
        this.mockKbB.reset(List.of("search", "lookup"));
        /*
         * 管理 API 现在需要登录（需求 12.8）。这一步也属于"只用对外接口"的验收范围 ——
         * 走的是浏览器完全相同的一条路：取 CSRF 令牌、登录、带着会话继续。
         */
        AdminSession.signIn(this.rest);
    }

    @AfterEach
    void closeClients() {
        this.openedClients.forEach(client -> {
            try {
                client.closeGracefully();
            }
            catch (RuntimeException ignored) {
                // 测试收尾
            }
        });
        this.openedClients.clear();
    }

    private JsonNode postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = this.rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("POST %s -> %s : %s", path, response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody();
    }

    private JsonNode patchJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // TestRestTemplate 默认的 JDK 客户端不支持 PATCH，这里用 Apache HttpClient 的封装。
        ResponseEntity<JsonNode> response = this.rest.exchange(path, HttpMethod.PATCH,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("PATCH %s -> %s : %s", path, response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody();
    }

    private int localPort() {
        return Integer.parseInt(URI.create(this.rest.getRootUri()).getPort() + "");
    }

    @Test
    @DisplayName("需求 2.2：从创建网关到 Agent 调用成功并留下记录，七步完整走通")
    void completeDemonstrationFlow() throws Exception {
        int port = localPort();

        // ---- 第 1 步：创建一个总 MCP -------------------------------------
        JsonNode created = postJson("/api/gateways", """
                {"name":"知识库聚合网关","slug":"acceptance","description":"聚合两个知识库，供 Agent 检索"}
                """);
        String gatewayId = created.at("/data/gateway/id").asText();
        // 需求 FR-05.3：令牌只在这里完整返回一次
        String accessToken = created.at("/data/accessToken").asText();
        assertThat(gatewayId).isNotBlank();
        assertThat(accessToken).startsWith("mcpgw_");
        assertThat(created.at("/data/gateway/status").asText()).isEqualTo("EMPTY");

        // ---- 第 2 步：粘贴包含 2 个远程 MCP 的配置 JSON -------------------
        String importPayload = """
                {"mcpServers": {
                  "kb_a": {"type":"streamable-http","url":"http://localhost:%d%s",
                           "headers":{"Authorization":"Bearer %s"}},
                  "kb_b": {"type":"streamable-http","url":"http://localhost:%d%s"}
                }}
                """.formatted(port, MockDownstreamConfig.KB_A_PATH, DOWNSTREAM_TOKEN,
                port, MockDownstreamConfig.KB_B_PATH);

        // ---- 第 3 步：成功连接并拉取工具 ---------------------------------
        JsonNode imported = postJson("/api/gateways/" + gatewayId + "/mcp-servers/import", importPayload);
        assertThat(imported.at("/data/gateway/status").asText()).isEqualTo("READY");
        assertThat(imported.at("/data/syncResults").size()).isEqualTo(2);
        for (JsonNode syncResult : imported.at("/data/syncResults")) {
            assertThat(syncResult.get("succeeded").asBoolean())
                    .as("sync result: %s", syncResult).isTrue();
        }
        // 两个子 MCP 都有 search，聚合后不冲突
        List<String> exposedNames = new ArrayList<>();
        for (JsonNode downstream : imported.at("/data/gateway/downstreams")) {
            for (JsonNode tool : downstream.get("tools")) {
                exposedNames.add(tool.get("exposedName").asText());
            }
        }
        assertThat(exposedNames).containsExactlyInAnyOrder(
                "kb_a__search", "kb_a__ping", "kb_b__search", "kb_b__lookup");

        // 凭证从来不回显
        assertThat(imported.toString()).doesNotContain(DOWNSTREAM_TOKEN);

        // ---- 第 4 步：调整工具启用状态和描述 ------------------------------
        String pingToolId = toolIdOf(imported, "kb_a__ping");
        String searchToolId = toolIdOf(imported, "kb_a__search");
        patchJson("/api/gateways/" + gatewayId + "/tools/" + pingToolId, "{\"enabled\":false}");
        patchJson("/api/gateways/" + gatewayId + "/tools/" + searchToolId,
                "{\"customDescription\":\"检索 A 库；查人名和制度条款优先用它\"}");

        // ---- 第 5 步：复制前端生成的 Agent 接入 JSON -----------------------
        /*
         * 详情页是 Vue 单页应用的空壳，页面本身不含任何数据 —— 所以这里只验它可达，
         * 内容验在它实际渲染所依据的那个接口上。
         * 页面把这份 JSON 渲染成什么样，由 frontend/test/gateway-detail-view.spec.ts 验。
         */
        String detailPage = this.rest.getForObject("/ui/gateways/" + gatewayId, String.class);
        assertThat(detailPage).contains("id=\"app\"").contains("/app/assets/");

        JsonNode agentConfig = this.rest.getForObject(
                "/api/gateways/" + gatewayId + "/agent-config", JsonNode.class);
        JsonNode entry = agentConfig.at("/data/mcpServers/acceptance");
        assertThat(entry.get("type").asText()).isEqualTo("streamable-http");
        String mcpUrl = entry.get("url").asText();
        assertThat(mcpUrl).endsWith("/mcp/acceptance");

        // 令牌位置是占位符（服务端只有哈希），且这份 JSON 里不含任何子 MCP 凭证
        assertThat(entry.at("/headers/Authorization").asText())
                .isEqualTo("Bearer <gateway-access-token>");
        assertThat(agentConfig.toString()).doesNotContain(DOWNSTREAM_TOKEN);

        // ---- 第 6 步：Agent 连接总 MCP，发现工具并成功调用 -----------------
        // 完全按接入 JSON 的形态连接：streamable-http + Bearer 令牌
        McpSyncClient agent = connectAgent(port, "acceptance", accessToken);

        List<String> discovered = agent.listTools().tools().stream()
                .map(McpSchema.Tool::name).sorted().toList();
        // 停用的 kb_a__ping 不出现
        assertThat(discovered).containsExactly("kb_a__search", "kb_b__lookup", "kb_b__search");

        // 自定义描述已经生效
        String searchDescription = agent.listTools().tools().stream()
                .filter(tool -> tool.name().equals("kb_a__search"))
                .findFirst().orElseThrow().description();
        assertThat(searchDescription).isEqualTo("检索 A 库；查人名和制度条款优先用它");

        McpSchema.CallToolResult result = agent.callTool(
                new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "年假制度")));
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(textOf(result)).isEqualTo("kbA/search:年假制度");

        // 重名工具路由正确
        assertThat(textOf(agent.callTool(
                new McpSchema.CallToolRequest("kb_b__search", Map.of("q", "年假制度")))))
                .isEqualTo("kbB/search:年假制度");

        // ---- 第 7 步：数据库中生成对应调用记录 ----------------------------
        List<Map<String, Object>> records = this.jdbcClient.sql("""
                SELECT exposed_tool_name, original_tool_name, status, request_json, response_json,
                       duration_ms, trace_id, error_code
                  FROM tool_call_record WHERE gateway_id = :gatewayId ORDER BY started_at
                """)
                .param("gatewayId", gatewayId)
                .query()
                .listOfRows();

        assertThat(records).hasSize(2);
        assertThat(records).extracting(row -> row.get("EXPOSED_TOOL_NAME"))
                .containsExactly("kb_a__search", "kb_b__search");
        assertThat(records).allSatisfy(row -> {
            assertThat(row.get("STATUS")).isEqualTo(CallStatus.SUCCESS.name());
            assertThat((String) row.get("REQUEST_JSON")).contains("年假制度");
            assertThat((String) row.get("RESPONSE_JSON")).isNotBlank();
            assertThat((Long) row.get("DURATION_MS")).isNotNull();
            assertThat((String) row.get("TRACE_ID")).isNotBlank();
            assertThat(row.get("ERROR_CODE")).isNull();
        });
        // 需求 15.4.4：记录里没有任何凭证
        assertThat(records.toString())
                .doesNotContain(DOWNSTREAM_TOKEN)
                .doesNotContain(accessToken);
    }

    @Test
    @DisplayName("需求 6.2.9 / 15.3.4：一个子 MCP 挂掉时，演示流程的其余部分照常可用")
    void flowSurvivesOneBrokenDownstream() {
        int port = localPort();
        JsonNode created = postJson("/api/gateways", """
                {"name":"降级网关","slug":"degraded","description":"其中一个知识库不可用"}
                """);
        String gatewayId = created.at("/data/gateway/id").asText();
        String accessToken = created.at("/data/accessToken").asText();

        JsonNode imported = postJson("/api/gateways/" + gatewayId + "/mcp-servers/import", """
                {"mcpServers": {
                  "kb_a": {"type":"streamable-http","url":"http://localhost:%d%s"},
                  "kb_dead": {"type":"streamable-http","url":"http://localhost:1/mcp"}
                }}
                """.formatted(port, MockDownstreamConfig.KB_A_PATH));

        // 需求 FR-01：仍有可用工具，所以是 DEGRADED 而不是全挂
        assertThat(imported.at("/data/gateway/status").asText()).isEqualTo("DEGRADED");

        McpSyncClient agent = connectAgent(port, "degraded", accessToken);
        assertThat(agent.listTools().tools()).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("kb_a__search", "kb_a__ping");
        assertThat(textOf(agent.callTool(
                new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "x")))))
                .isEqualTo("kbA/search:x");
    }

    // ---------------------------------------------------------------- 辅助

    private String toolIdOf(JsonNode importResponse, String exposedName) {
        for (JsonNode downstream : importResponse.at("/data/gateway/downstreams")) {
            for (JsonNode tool : downstream.get("tools")) {
                if (exposedName.equals(tool.get("exposedName").asText())) {
                    return tool.get("id").asText();
                }
            }
        }
        throw new AssertionError("no such tool in the import response: " + exposedName);
    }

    private McpSyncClient connectAgent(int port, String slug, String token) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp/" + slug)
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        this.openedClients.add(client);
        client.initialize();
        return client;
    }

    private static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst().orElse("");
    }
}
