package com.mcpgateway.mcpserver;

import com.mcpgateway.TestAdminCredentials;
import com.mcpgateway.TestMasterKey;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.SyncStatus;
import com.mcpgateway.downstream.MockDownstreamConfig;
import com.mcpgateway.downstream.MockDownstreamMcpServer;
import com.mcpgateway.downstream.ToolSyncService;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.GatewayRepository;
import com.mcpgateway.security.AccessTokenService;
import com.mcpgateway.security.DownstreamHeaderCodec;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * instructions 模板的全链路验收：环境变量 → GatewayProperties 绑定 →
 * GatewayMcpRegistry 组合 → initialize 结果，Agent 用真实 MCP 客户端读回来。
 *
 * 单元测试（AgentInstructionsTest）只证明渲染规则本身；配置项真的能从外部
 * 拧到 initialize 输出，只有这条链路能证明。独立成类是因为 @DynamicPropertySource
 * 按类生效 —— 在 AgentEndToEndTest 里覆盖模板会毁掉它对默认格式的整串断言，
 * 而那些断言正是"默认输出与 1.3 逐字节相同"的背书，一个字都不能动。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { com.mcpgateway.McpGatewayApplication.class, MockDownstreamConfig.class })
@ActiveProfiles("test")
class AgentInstructionsTemplateEndToEndTest {

    /** 故意与默认格式完全不同：标题、清单头都换了，任何一侧的泄漏都藏不住。 */
    private static final String CUSTOM_TEMPLATE =
            "【{{gatewayDescription}}】\n\n可用子服务：\n{{downstreams}}";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mcp-gateway.security.master-key", () -> TestMasterKey.BASE64);
        TestAdminCredentials.register(registry);
        registry.add("mcp-gateway.server.agent-instructions-template", () -> CUSTOM_TEMPLATE);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private GatewayRepository gateways;

    @Autowired
    private DownstreamMcpRepository downstreams;

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
        this.gateway = new Gateway(UUID.randomUUID().toString(), "模板端到端网关",
                "tpl-" + Long.toString(System.nanoTime(), 36), "网关用途说明", token.hash(),
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

    private void addDownstream(String name, String path) {
        DownstreamMcp downstream = new DownstreamMcp(UUID.randomUUID().toString(), this.gateway.id(), name,
                DownstreamMcp.TYPE_STREAMABLE_HTTP, "http://localhost:" + this.port + path,
                this.headerCodec.encrypt(Map.of()), null, null, SyncStatus.PENDING, null, null,
                Instant.now(), Instant.now());
        this.downstreams.insert(downstream);
        assertThat(this.syncService.sync(downstream.id()).succeeded()).isTrue();
    }

    private String instructionsAsSeenByAgent() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + this.port)
                .endpoint(GatewayMcpRuntime.mcpPath(this.gateway.slug()))
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + this.accessToken))
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        this.openedClients.add(client);
        client.initialize();
        return client.getServerInstructions();
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("配置了自定义模板时，Agent 在 initialize 里读回模板渲染结果")
    void customTemplateReachesAgentsInitializeResult() {
        addDownstream("kb_b", MockDownstreamConfig.KB_B_PATH);

        assertThat(instructionsAsSeenByAgent()).isEqualTo("【网关用途说明】\n\n可用子服务：\n"
                + "- kb_b：" + MockDownstreamConfig.KB_B_INSTRUCTIONS);
    }

    @Test
    @DisplayName("子 MCP 都没描述时，含 {{downstreams}} 的段落连同「可用子服务：」标题一起消失")
    void emptyDownstreamsParagraphDropsHeadingToo() {
        addDownstream("kb_a", MockDownstreamConfig.KB_A_PATH);

        assertThat(instructionsAsSeenByAgent()).isEqualTo("【网关用途说明】");
    }
}
