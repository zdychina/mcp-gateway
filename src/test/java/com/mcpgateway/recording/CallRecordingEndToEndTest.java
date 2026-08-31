package com.mcpgateway.recording;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.TestMasterKey;
import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.domain.SyncStatus;
import com.mcpgateway.downstream.MockDownstreamConfig;
import com.mcpgateway.downstream.MockDownstreamMcpServer;
import com.mcpgateway.downstream.ToolSyncService;
import com.mcpgateway.mcpserver.GatewayMcpRuntime;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.GatewayRepository;
import com.mcpgateway.repository.GatewayToolRepository;
import com.mcpgateway.repository.ToolCallRecordRepository;
import com.mcpgateway.security.AccessTokenService;
import com.mcpgateway.security.DownstreamHeaderCodec;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 需求 FR-06 与 15.4 的验收：从真实的 Agent 调用一路验到落库的记录。
 *
 * 这一层最要紧的是"没有缺口"——未知工具和停用工具的调用也必须留下记录。
 * 如果当初依赖 SDK 的工具注册表，这两种请求根本到不了网关代码，这些用例会直接暴露那个洞。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { com.mcpgateway.McpGatewayApplication.class, MockDownstreamConfig.class })
@ActiveProfiles("test")
class CallRecordingEndToEndTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mcp-gateway.security.master-key", () -> TestMasterKey.BASE64);
    }

    private static final String DOWNSTREAM_TOKEN = "Bearer sk-downstream-secret";

    @LocalServerPort
    private int port;

    @Autowired
    private GatewayRepository gateways;

    @Autowired
    private DownstreamMcpRepository downstreams;

    @Autowired
    private GatewayToolRepository tools;

    @Autowired
    private ToolCallRecordRepository callRecords;

    @Autowired
    private ToolSyncService syncService;

    @Autowired
    private DownstreamHeaderCodec headerCodec;

    @Autowired
    private AccessTokenService accessTokens;

    @Autowired
    private MockDownstreamMcpServer mockKbA;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<McpSyncClient> openedClients = new ArrayList<>();

    private Gateway gateway;

    private String accessToken;

    private DownstreamMcp downstream;

    @BeforeEach
    void seed() {
        this.mockKbA.reset(List.of("search", "ping"));

        AccessTokenService.GeneratedToken token = this.accessTokens.generate();
        this.accessToken = token.token();
        this.gateway = new Gateway(UUID.randomUUID().toString(), "打点网关",
                "rec-" + Long.toString(System.nanoTime(), 36), null, token.hash(),
                Instant.now(), Instant.now());
        this.gateways.insert(this.gateway);

        this.downstream = new DownstreamMcp(UUID.randomUUID().toString(), this.gateway.id(), "kb_a",
                DownstreamMcp.TYPE_STREAMABLE_HTTP,
                "http://localhost:" + this.port + MockDownstreamConfig.KB_A_PATH,
                this.headerCodec.encrypt(Map.of("Authorization", DOWNSTREAM_TOKEN)),
                SyncStatus.PENDING, null, null, Instant.now(), Instant.now());
        this.downstreams.insert(this.downstream);
        assertThat(this.syncService.sync(this.downstream.id()).succeeded()).isTrue();
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

    // ---------------------------------------------------------------- 辅助

    private McpSyncClient agent(Map<String, String> extraHeaders) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + this.port)
                .endpoint(GatewayMcpRuntime.mcpPath(this.gateway.slug()))
                .httpRequestCustomizer((builder, method, uri, body, context) -> {
                    builder.header("Authorization", "Bearer " + this.accessToken);
                    extraHeaders.forEach(builder::header);
                })
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        this.openedClients.add(client);
        client.initialize();
        return client;
    }

    private McpSyncClient agent() {
        return agent(Map.of());
    }

    /** 直接读库，避开还没有的查询接口（需求 FR-06.5：MVP 不提供调用记录前端）。 */
    private List<Map<String, Object>> recordsOfThisGateway() {
        return this.jdbcClient.sql("""
                SELECT call_id, trace_id, gateway_id, downstream_mcp_id, exposed_tool_name, original_tool_name,
                       request_json, response_json, status, error_code, error_message, duration_ms,
                       started_at, finished_at
                  FROM tool_call_record WHERE gateway_id = :gatewayId ORDER BY started_at
                """)
                .param("gatewayId", this.gateway.id())
                .query()
                .listOfRows();
    }

    private Map<String, Object> singleRecord() {
        List<Map<String, Object>> all = recordsOfThisGateway();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    // ------------------------------------------------------------ 成功路径

    @Test
    @DisplayName("需求 15.4.1：成功调用生成 SUCCESS 记录，含输入、输出和耗时")
    void successfulCallIsRecorded() throws Exception {
        agent().callTool(new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "hello")));

        Map<String, Object> record = singleRecord();
        assertThat(record.get("STATUS")).isEqualTo(CallStatus.SUCCESS.name());
        assertThat(record.get("EXPOSED_TOOL_NAME")).isEqualTo("kb_a__search");
        assertThat(record.get("ORIGINAL_TOOL_NAME")).isEqualTo("search");
        assertThat(record.get("DOWNSTREAM_MCP_ID")).isEqualTo(this.downstream.id());
        assertThat(record.get("ERROR_CODE")).isNull();
        assertThat(record.get("FINISHED_AT")).isNotNull();
        assertThat((Long) record.get("DURATION_MS")).isNotNegative();

        // 需求 FR-06.4：输入输出按原始 JSON 保存
        JsonNode request = this.objectMapper.readTree((String) record.get("REQUEST_JSON"));
        assertThat(request.get("q").asText()).isEqualTo("hello");
        JsonNode response = this.objectMapper.readTree((String) record.get("RESPONSE_JSON"));
        assertThat(response.at("/content/0/text").asText()).isEqualTo("kbA/search:hello");
    }

    @Test
    @DisplayName("需求 15.4.3：记录可通过 call_id 和 trace_id 唯一关联")
    void recordsAreIdentifiableByCallIdAndTraceId() {
        agent().callTool(new McpSchema.CallToolRequest("kb_a__ping", Map.of("q", "1")));

        Map<String, Object> record = singleRecord();
        String callId = (String) record.get("CALL_ID");
        String traceId = (String) record.get("TRACE_ID");

        assertThat(callId).isNotBlank();
        assertThat(traceId).isNotBlank();
        assertThat(this.callRecords.findByCallId(callId)).isPresent();
        assertThat(this.callRecords.findByTraceId(traceId)).hasSize(1);
    }

    @Test
    @DisplayName("需求 FR-06：上游带了 trace_id 就沿用，没带才由网关生成")
    void upstreamTraceIdIsReused() {
        agent(Map.of("X-Request-Id", "upstream-trace-42"))
                .callTool(new McpSchema.CallToolRequest("kb_a__ping", Map.of("q", "1")));

        assertThat(singleRecord().get("TRACE_ID")).isEqualTo("upstream-trace-42");
    }

    @Test
    @DisplayName("W3C traceparent 只取中间的 trace-id 段")
    void traceparentIsParsed() {
        agent(Map.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .callTool(new McpSchema.CallToolRequest("kb_a__ping", Map.of("q", "1")));

        assertThat(singleRecord().get("TRACE_ID")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    // --------------------------------------------------------- 失败路径无缺口

    @Test
    @DisplayName("需求 FR-06：未知工具的调用同样留下 ERROR 记录，不是缺口")
    void unknownToolStillProducesARecord() {
        McpSyncClient agent = agent();

        assertThat(catchThrowable(() -> agent.callTool(
                new McpSchema.CallToolRequest("kb_a__nope", Map.of("q", "x"))))).isNotNull();

        Map<String, Object> record = singleRecord();
        assertThat(record.get("STATUS")).isEqualTo(CallStatus.ERROR.name());
        assertThat(record.get("ERROR_CODE")).isEqualTo("TOOL_NOT_FOUND");
        assertThat(record.get("EXPOSED_TOOL_NAME")).isEqualTo("kb_a__nope");
        // 未知工具无法确定目标，这两列为空是预期行为
        assertThat(record.get("DOWNSTREAM_MCP_ID")).isNull();
        assertThat(record.get("ORIGINAL_TOOL_NAME")).isNull();
        assertThat(record.get("FINISHED_AT")).isNotNull();
    }

    @Test
    @DisplayName("需求 FR-06：停用工具的调用留下 TOOL_DISABLED 记录，且不触达下游")
    void disabledToolStillProducesARecord() {
        GatewayTool search = this.tools
                .findByGatewayIdAndExposedName(this.gateway.id(), "kb_a__search").orElseThrow();
        this.tools.updateEnabled(search.id(), false, Instant.now());
        this.mockKbA.reset(List.of("search", "ping"));
        McpSyncClient agent = agent();

        assertThat(catchThrowable(() -> agent.callTool(
                new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "x"))))).isNotNull();

        Map<String, Object> record = singleRecord();
        assertThat(record.get("STATUS")).isEqualTo(CallStatus.ERROR.name());
        assertThat(record.get("ERROR_CODE")).isEqualTo("TOOL_DISABLED");
        assertThat(record.get("EXPOSED_TOOL_NAME")).isEqualTo("kb_a__search");
        // 需求 6.5.2：请求不得转发给子 MCP
        assertThat(this.mockKbA.calledTools()).isEmpty();
    }

    @Test
    @DisplayName("需求 15.4.2：下游不可达时生成 ERROR 记录并带上耗时")
    void downstreamFailureProducesAnErrorRecord() {
        this.downstreams.updateConfig(this.downstream.id(), "kb_a", "http://localhost:1/mcp",
                this.headerCodec.encrypt(Map.of("Authorization", DOWNSTREAM_TOKEN)), Instant.now());
        McpSyncClient agent = agent();

        assertThat(catchThrowable(() -> agent.callTool(
                new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "x"))))).isNotNull();

        Map<String, Object> record = singleRecord();
        assertThat(record.get("STATUS")).isIn(CallStatus.ERROR.name(), CallStatus.TIMEOUT.name());
        assertThat((String) record.get("ERROR_CODE")).startsWith("DOWNSTREAM_");
        // 路由是解析成功的，所以目标信息完整
        assertThat(record.get("DOWNSTREAM_MCP_ID")).isEqualTo(this.downstream.id());
        assertThat(record.get("ORIGINAL_TOOL_NAME")).isEqualTo("search");
        assertThat(record.get("RESPONSE_JSON")).isNull();
        assertThat((Long) record.get("DURATION_MS")).isNotNull();
    }

    // ------------------------------------------------------------ 脱敏

    @Test
    @DisplayName("需求 15.4.4：记录里不含 HTTP header、访问令牌和服务端堆栈")
    void recordsCarryNoCredentialsOrStackTraces() {
        McpSyncClient agent = agent(Map.of("X-Custom-Secret", "header-secret-value"));
        agent.callTool(new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "hello")));
        // 再制造一次失败，确保错误摘要也是干净的
        this.downstreams.updateConfig(this.downstream.id(), "kb_a", "http://10.1.2.3:9999/mcp",
                this.headerCodec.encrypt(Map.of("Authorization", DOWNSTREAM_TOKEN)), Instant.now());
        catchThrowable(() -> agent.callTool(
                new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "x"))));

        String everything = recordsOfThisGateway().toString();
        assertThat(everything)
                .doesNotContain(this.accessToken)
                .doesNotContain("sk-downstream-secret")
                .doesNotContain("header-secret-value")
                .doesNotContain("X-Custom-Secret")
                .doesNotContain("10.1.2.3")
                .doesNotContain("java.net")
                .doesNotContain("at com.mcpgateway");
    }

    @Test
    @DisplayName("每次调用各自一条记录，多次调用不会互相覆盖")
    void eachCallGetsItsOwnRecord() {
        McpSyncClient agent = agent();
        agent.callTool(new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "one")));
        agent.callTool(new McpSchema.CallToolRequest("kb_a__ping", Map.of("q", "two")));
        catchThrowable(() -> agent.callTool(new McpSchema.CallToolRequest("kb_a__nope", Map.of("q", "3"))));

        List<Map<String, Object>> all = recordsOfThisGateway();
        assertThat(all).hasSize(3);
        assertThat(all).extracting(row -> row.get("EXPOSED_TOOL_NAME"))
                .containsExactlyInAnyOrder("kb_a__search", "kb_a__ping", "kb_a__nope");
        assertThat(all).extracting(row -> row.get("CALL_ID")).doesNotHaveDuplicates();
        // 没有记录停在 STARTED —— 每次调用都有终态
        assertThat(all).extracting(row -> row.get("STATUS")).doesNotContain(CallStatus.STARTED.name());
    }

    @Test
    @DisplayName("tools/list 不产生调用记录，只有 tools/call 才算一次调用")
    void listingToolsIsNotRecorded() {
        agent().listTools();

        assertThat(recordsOfThisGateway()).isEmpty();
    }
}
