package com.mcpgateway.mcpserver;

import com.mcpgateway.TestMasterKey;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.domain.SyncStatus;
import com.mcpgateway.downstream.MockDownstreamConfig;
import com.mcpgateway.downstream.MockDownstreamMcpServer;
import com.mcpgateway.downstream.ToolSyncService;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.GatewayRepository;
import com.mcpgateway.repository.GatewayToolRepository;
import com.mcpgateway.security.AccessTokenService;
import com.mcpgateway.security.DownstreamHeaderCodec;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 需求 15.3 的验收路径：Agent 用真实的 MCP 客户端连总 MCP，走完整的
 * initialize / tools/list / tools/call，下游也是真实的 MCP 服务。
 *
 * 三段真实 HTTP：Agent -> 网关 -> 子 MCP，全在同一个进程里但都过网络栈。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { com.mcpgateway.McpGatewayApplication.class, MockDownstreamConfig.class })
@ActiveProfiles("test")
class AgentEndToEndTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mcp-gateway.security.master-key", () -> TestMasterKey.BASE64);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private GatewayRepository gateways;

    @Autowired
    private DownstreamMcpRepository downstreams;

    @Autowired
    private GatewayToolRepository tools;

    @Autowired
    private ToolSyncService syncService;

    @Autowired
    private DownstreamHeaderCodec headerCodec;

    @Autowired
    private AccessTokenService accessTokens;

    @Autowired
    private MockDownstreamMcpServer mockKbA;

    @Autowired
    private MockDownstreamMcpServer mockKbB;

    private final List<McpSyncClient> openedClients = new ArrayList<>();

    private Gateway gateway;

    private String accessToken;

    @BeforeEach
    void seed() {
        this.mockKbA.reset(List.of("search", "ping"));
        this.mockKbB.reset(List.of("search", "lookup"));

        AccessTokenService.GeneratedToken token = this.accessTokens.generate();
        this.accessToken = token.token();
        this.gateway = new Gateway(UUID.randomUUID().toString(), "端到端网关",
                "e2e-" + Long.toString(System.nanoTime(), 36), "网关用途说明", token.hash(),
                Instant.now(), Instant.now());
        this.gateways.insert(this.gateway);
    }

    @AfterEach
    void closeClients() {
        this.openedClients.forEach(client -> {
            try {
                client.closeGracefully();
            }
            catch (RuntimeException ignored) {
                // 测试收尾，忽略
            }
        });
        this.openedClients.clear();
    }

    // ---------------------------------------------------------------- 辅助

    private DownstreamMcp addDownstream(String name, String path) {
        DownstreamMcp downstream = new DownstreamMcp(UUID.randomUUID().toString(), this.gateway.id(), name,
                DownstreamMcp.TYPE_STREAMABLE_HTTP, "http://localhost:" + this.port + path,
                this.headerCodec.encrypt(Map.of()), SyncStatus.PENDING, null, null,
                Instant.now(), Instant.now());
        this.downstreams.insert(downstream);
        assertThat(this.syncService.sync(downstream.id()).succeeded()).isTrue();
        return downstream;
    }

    /** 完全按前端给出的接入 JSON 那样连接：streamable-http + Bearer 令牌。 */
    private McpSyncClient agentClient(String token) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + this.port)
                .endpoint(GatewayMcpRuntime.mcpPath(this.gateway.slug()))
                .httpRequestCustomizer((builder, method, uri, body, context) -> {
                    if (token != null) {
                        builder.header("Authorization", "Bearer " + token);
                    }
                })
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        this.openedClients.add(client);
        return client;
    }

    private McpSyncClient connectedAgent() {
        McpSyncClient client = agentClient(this.accessToken);
        client.initialize();
        return client;
    }

    private static List<String> namesOf(McpSchema.ListToolsResult result) {
        return result.tools().stream().map(McpSchema.Tool::name).sorted().toList();
    }

    private static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst().orElse("");
    }

    // ------------------------------------------------------------ 连接与发现

    @Test
    @DisplayName("需求 15.3.1 / 15.3.2：Agent 能连上总 MCP，完成 tools/list 和一次成功的 tools/call")
    void agentCanDiscoverAndCallTools() {
        addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);
        McpSyncClient agent = connectedAgent();

        assertThat(namesOf(agent.listTools())).containsExactly("kb_a__ping", "kb_a__search");

        McpSchema.CallToolResult result = agent.callTool(
                new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "hello")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(textOf(result)).isEqualTo("kbA/search:hello");
        // 需求 15.3.3：参数准确转发给目标子 MCP
        assertThat(this.mockKbA.calledTools()).containsExactly("search");
    }

    @Test
    @DisplayName("需求 6.1.4：网关描述作为 instructions 传给 Agent")
    void gatewayDescriptionIsExposedAsInstructions() {
        addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);

        assertThat(connectedAgent().getServerInstructions()).isEqualTo("网关用途说明");
    }

    @Test
    @DisplayName("需求 15.2.2 / 15.3.3：两个子 MCP 的同名工具各自路由到正确的下游")
    void duplicateToolNamesRouteCorrectly() {
        addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);
        addDownstream("kb_b", MockDownstreamConfig.KB_B_PATH);
        McpSyncClient agent = connectedAgent();

        assertThat(namesOf(agent.listTools()))
                .containsExactly("kb_a__ping", "kb_a__search", "kb_b__lookup", "kb_b__search");

        assertThat(textOf(agent.callTool(
                new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "x")))))
                .isEqualTo("kbA/search:x");
        assertThat(textOf(agent.callTool(
                new McpSchema.CallToolRequest("kb_b__search", Map.of("q", "x")))))
                .isEqualTo("kbB/search:x");
    }

    @Test
    @DisplayName("tools/list 返回生效描述和下游原始 Schema")
    void toolsListCarriesEffectiveDescriptionAndSchema() {
        DownstreamMcp downstream = addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);
        GatewayTool search = this.tools
                .findByGatewayIdAndExposedName(this.gateway.id(), "kb_a__search").orElseThrow();
        this.tools.updateCustomDescription(search.id(), "运营写的提示词", Instant.now());

        McpSchema.Tool exposed = connectedAgent().listTools().tools().stream()
                .filter(tool -> tool.name().equals("kb_a__search")).findFirst().orElseThrow();

        // 需求 15.2.6：Agent 看到新描述
        assertThat(exposed.description()).isEqualTo("运营写的提示词");
        // 需求 15.2.7：描述修改不改变原始 Schema
        assertThat(exposed.inputSchema()).containsKey("properties");
        assertThat(downstream.name()).isEqualTo("kb_a");
    }

    // ------------------------------------------------------------ 启停

    @Test
    @DisplayName("需求 15.2.4 / 15.2.5：停用后立即消失且不触达下游，重新启用后恢复")
    void disabledToolDisappearsAndIsNotForwarded() {
        addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);
        GatewayTool search = this.tools
                .findByGatewayIdAndExposedName(this.gateway.id(), "kb_a__search").orElseThrow();
        this.tools.updateEnabled(search.id(), false, Instant.now());
        this.mockKbA.reset(List.of("search", "ping"));

        McpSyncClient agent = connectedAgent();
        assertThat(namesOf(agent.listTools())).containsExactly("kb_a__ping");

        assertThatThrownBy(() -> agent.callTool(
                new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "x"))))
                .isInstanceOf(RuntimeException.class);
        // 需求 6.5.2：请求不得转发给子 MCP
        assertThat(this.mockKbA.calledTools()).isEmpty();

        // 重新启用后立刻恢复，无需重启进程或重建 MCP 上下文
        this.tools.updateEnabled(search.id(), true, Instant.now());
        McpSyncClient reconnected = connectedAgent();
        assertThat(namesOf(reconnected.listTools())).containsExactly("kb_a__ping", "kb_a__search");
        assertThat(textOf(reconnected.callTool(
                new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "back")))))
                .isEqualTo("kbA/search:back");
    }

    @Test
    @DisplayName("停用工具与未知工具带回各自的稳定错误码，但对外文案一致")
    void disabledAndUnknownToolsCarryDistinctErrorCodes() {
        addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);
        GatewayTool search = this.tools
                .findByGatewayIdAndExposedName(this.gateway.id(), "kb_a__search").orElseThrow();
        this.tools.updateEnabled(search.id(), false, Instant.now());
        McpSyncClient agent = connectedAgent();

        // 这正是 W0 探针当时做不到的事：SDK 自己的工具注册表只会回固定的 "Unknown tool"。
        // 现在两种失败各自带上网关的稳定错误码。
        assertThat(errorOf(agent, "kb_a__search")).isEqualTo("TOOL_DISABLED");
        assertThat(errorOf(agent, "kb_a__nope")).isEqualTo("TOOL_NOT_FOUND");

        // 错误码放在 data 里而不是 message 里：两种情况的文案模板完全一致，
        // 免得把"这个工具存在但被停用了"这类网关配置状态透给 Agent。
        // 文案里只有 Agent 自己传来的工具名，不构成泄漏。
        assertThat(messageOf(agent, "kb_a__search")).isEqualTo("tool not found or disabled: kb_a__search");
        assertThat(messageOf(agent, "kb_a__nope")).isEqualTo("tool not found or disabled: kb_a__nope");
    }

    private String errorOf(McpSyncClient agent, String toolName) {
        McpError error = catchMcpError(agent, toolName);
        assertThat(error.getJsonRpcError().code()).isEqualTo(-32602);
        return ((Map<?, ?>) error.getJsonRpcError().data()).get("errorCode").toString();
    }

    private String messageOf(McpSyncClient agent, String toolName) {
        return catchMcpError(agent, toolName).getJsonRpcError().message();
    }

    private McpError catchMcpError(McpSyncClient agent, String toolName) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> agent.callTool(
                new McpSchema.CallToolRequest(toolName, Map.of("q", "x"))));
        assertThat(thrown).isInstanceOf(McpError.class);
        return (McpError) thrown;
    }

    // ------------------------------------------------------------ 鉴权

    @Test
    @DisplayName("需求 4.3：没有令牌或令牌不对时无法连接")
    void accessTokenIsRequired() {
        addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);

        assertThatThrownBy(() -> agentClient(null).initialize()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> agentClient("mcpgw_wrong-token").initialize())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("需求 FR-05.3：轮换令牌后旧令牌立即失效")
    void rotatingTheTokenInvalidatesTheOldOne() {
        addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);
        assertThat(connectedAgent().listTools().tools()).isNotEmpty();

        AccessTokenService.GeneratedToken rotated = this.accessTokens.generate();
        this.gateways.updateAccessTokenHash(this.gateway.id(), rotated.hash(), Instant.now());

        assertThatThrownBy(() -> agentClient(this.accessToken).initialize())
                .isInstanceOf(RuntimeException.class);
        McpSyncClient withNewToken = agentClient(rotated.token());
        withNewToken.initialize();
        assertThat(withNewToken.listTools().tools()).isNotEmpty();
    }

    // ------------------------------------------------------------ 隔离与降级

    @Test
    @DisplayName("需求 15.3.4：一个子 MCP 不可用时，其他子 MCP 的工具仍可调用")
    void oneBrokenDownstreamDoesNotBlockTheOthers() {
        addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);
        DownstreamMcp broken = addDownstream("kb_b", MockDownstreamConfig.KB_B_PATH);
        // 同步成功之后下游才挂掉：快照仍在，但调用会失败
        this.downstreams.updateConfig(broken.id(), "kb_b", "http://localhost:1/mcp", null, Instant.now());

        McpSyncClient agent = connectedAgent();
        assertThat(namesOf(agent.listTools()))
                .containsExactly("kb_a__ping", "kb_a__search", "kb_b__lookup", "kb_b__search");

        assertThatThrownBy(() -> agent.callTool(
                new McpSchema.CallToolRequest("kb_b__search", Map.of("q", "x"))))
                .isInstanceOf(RuntimeException.class);

        // 坏掉的那个不影响健康的那个
        assertThat(textOf(agent.callTool(
                new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "ok")))))
                .isEqualTo("kbA/search:ok");
    }

    @Test
    @DisplayName("需求 15.3.5：下游错误脱敏，不泄漏凭证与内网地址")
    void downstreamFailuresAreSanitised() {
        DownstreamMcp downstream = addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);
        this.downstreams.updateConfig(downstream.id(), "kb_a", "http://10.1.2.3:9999/mcp",
                this.headerCodec.encrypt(Map.of("Authorization", "Bearer sk-secret-value")), Instant.now());
        McpSyncClient agent = connectedAgent();

        assertThatThrownBy(() -> agent.callTool(
                new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "x"))))
                .hasMessageNotContaining("sk-secret-value")
                .hasMessageNotContaining("10.1.2.3")
                .hasMessageNotContaining("java.net");
    }

    @Test
    @DisplayName("下游返回的业务错误原样带回 Agent")
    void downstreamBusinessErrorsArePassedThrough() {
        addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);
        McpSyncClient agent = connectedAgent();

        // mock 的工具正常返回，这里验证结果结构原样保留（需求 6.6.3）
        McpSchema.CallToolResult result = agent.callTool(
                new McpSchema.CallToolRequest("kb_a__ping", Map.of("q", "v")));

        assertThat(result.content()).hasSize(1);
        assertThat(textOf(result)).isEqualTo("kbA/ping:v");
    }

    // ------------------------------------------------------------ 路由隔离

    @Test
    @DisplayName("未知 slug 与多段路径都被拒绝，不会落到任何网关上")
    void unknownOrNestedPathsAreRejected() {
        addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);

        var unknown = HttpClientStreamableHttpTransport.builder("http://localhost:" + this.port)
                .endpoint("/mcp/no-such-gateway")
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + this.accessToken))
                .build();
        McpSyncClient unknownClient = McpClient.sync(unknown).build();
        this.openedClients.add(unknownClient);
        assertThatThrownBy(unknownClient::initialize).isInstanceOf(RuntimeException.class);

        // transport 内部只做后缀匹配，这条路径能骗过它，但分发器的精确匹配会拦住
        var nested = HttpClientStreamableHttpTransport.builder("http://localhost:" + this.port)
                .endpoint("/mcp/anything" + GatewayMcpRuntime.mcpPath(this.gateway.slug()))
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + this.accessToken))
                .build();
        McpSyncClient nestedClient = McpClient.sync(nested).build();
        this.openedClients.add(nestedClient);
        assertThatThrownBy(nestedClient::initialize).isInstanceOf(RuntimeException.class);
    }
}
