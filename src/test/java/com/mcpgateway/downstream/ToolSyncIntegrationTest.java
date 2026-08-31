package com.mcpgateway.downstream;

import com.mcpgateway.TestMasterKey;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.domain.SyncStatus;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.GatewayRepository;
import com.mcpgateway.repository.GatewayToolRepository;
import com.mcpgateway.security.DownstreamHeaderCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同步引擎的端到端验证：真实 HTTP、真实 MCP 握手、真实数据库。
 *
 * 注意这里不能用 @Transactional 回滚 —— 同步流程刻意把落库放在它自己的事务里，
 * 外层测试事务会把它和被测行为隔开，测出来的就不是真实行为了。所以改为每个用例
 * 自己造独立的网关，互不干扰。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { com.mcpgateway.McpGatewayApplication.class, MockDownstreamConfig.class })
@ActiveProfiles("test")
class ToolSyncIntegrationTest {

    private static final String REAL_TOKEN = "Bearer sk-downstream-secret";

    @DynamicPropertySource
    static void registerMasterKey(DynamicPropertyRegistry registry) {
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
    private MockDownstreamMcpServer mockKbA;

    @Autowired
    private TestRestTemplate restTemplate;

    private Gateway gateway;

    @BeforeEach
    void seed() {
        this.mockKbA.reset(List.of("search", "ping"));
        this.gateway = new Gateway(UUID.randomUUID().toString(), "gw",
                "sync-" + Long.toString(System.nanoTime(), 36), null, "hash", Instant.now(), Instant.now());
        this.gateways.insert(this.gateway);
    }

    private DownstreamMcp createDownstream(String name, String path, Map<String, String> headers) {
        DownstreamMcp downstream = new DownstreamMcp(UUID.randomUUID().toString(), this.gateway.id(), name,
                DownstreamMcp.TYPE_STREAMABLE_HTTP, "http://localhost:" + this.port + path,
                this.headerCodec.encrypt(headers), SyncStatus.PENDING, null, null,
                Instant.now(), Instant.now());
        this.downstreams.insert(downstream);
        return downstream;
    }

    private List<String> exposedNames(String downstreamId) {
        return this.tools.findByDownstreamMcpId(downstreamId).stream()
                .map(GatewayTool::exposedName).sorted().toList();
    }

    // ------------------------------------------------------------ 首次同步

    @Test
    @DisplayName("需求 15.2.1：有效子 MCP 可成功初始化并拉取工具，新工具默认启用")
    void firstSyncCreatesEnabledTools() {
        DownstreamMcp downstream = createDownstream("kb_a", MockDownstreamConfig.KB_A_PATH, Map.of());

        ToolSyncService.SyncReport report = this.syncService.sync(downstream.id());

        assertThat(report.succeeded()).isTrue();
        assertThat(report.outcome().added()).isEqualTo(2);
        assertThat(report.outcome().removed()).isZero();

        // 需求 15.2.3：名称严格为 子MCP名称__原工具名
        assertThat(exposedNames(downstream.id())).containsExactly("kb_a__ping", "kb_a__search");
        // 需求 6.4.3：新发现的工具默认启用
        assertThat(this.tools.findByDownstreamMcpId(downstream.id()))
                .allSatisfy(tool -> assertThat(tool.enabled()).isTrue());
        // 原始 Schema 原样落库
        GatewayTool search = this.tools
                .findByGatewayIdAndExposedName(this.gateway.id(), "kb_a__search").orElseThrow();
        assertThat(search.originalDescription()).isEqualTo("original description of kbA.search");
        assertThat(search.inputSchemaJson()).contains("\"properties\"").contains("\"q\"");
        assertThat(search.definitionHash()).isNotBlank();

        assertThat(this.downstreams.findById(downstream.id()).orElseThrow().syncStatus())
                .isEqualTo(SyncStatus.SUCCESS);
    }

    @Test
    @DisplayName("需求 6.2.6：配置的自定义 headers 确实被送到下游")
    void customHeadersReachTheDownstream() {
        DownstreamMcp downstream = createDownstream("kb_a", MockDownstreamConfig.KB_A_PATH,
                Map.of("Authorization", REAL_TOKEN));

        this.syncService.sync(downstream.id());

        assertThat(this.mockKbA.receivedAuthorizationHeaders())
                .isNotEmpty()
                .allSatisfy(header -> assertThat(header).isEqualTo(REAL_TOKEN));
    }

    @Test
    @DisplayName("没配 headers 时不会凭空发出 Authorization")
    void noHeadersMeansNoAuthorization() {
        DownstreamMcp downstream = createDownstream("kb_a", MockDownstreamConfig.KB_A_PATH, Map.of());

        this.syncService.sync(downstream.id());

        assertThat(this.mockKbA.receivedAuthorizationHeaders()).isNotEmpty().containsOnly("<none>");
    }

    @Test
    @DisplayName("需求 6.2.8：通过 API 触发的测试并同步返回变更统计")
    void syncEndpointReportsOutcome() {
        DownstreamMcp downstream = createDownstream("kb_a", MockDownstreamConfig.KB_A_PATH, Map.of());

        String body = this.restTemplate.postForObject(
                "/api/gateways/{gatewayId}/mcp-servers/{serverId}/sync", null, String.class,
                this.gateway.id(), downstream.id());

        assertThat(body).contains("\"success\":true");
        assertThat(body).contains("\"succeeded\":true");
        assertThat(body).contains("\"added\":2");
        assertThat(exposedNames(downstream.id())).containsExactly("kb_a__ping", "kb_a__search");
    }

    // ------------------------------------------------------------ 重新同步

    @Test
    @DisplayName("需求 15.2.8：重新同步更新原始定义，同时保留启停状态和自定义描述")
    void resyncPreservesOperatorConfiguration() {
        DownstreamMcp downstream = createDownstream("kb_a", MockDownstreamConfig.KB_A_PATH, Map.of());
        this.syncService.sync(downstream.id());

        GatewayTool search = this.tools
                .findByGatewayIdAndExposedName(this.gateway.id(), "kb_a__search").orElseThrow();
        Instant configuredAt = Instant.now();
        this.tools.updateEnabled(search.id(), false, configuredAt);
        this.tools.updateCustomDescription(search.id(), "运营写的提示词", configuredAt);

        // 下游改了工具描述
        this.mockKbA.redescribe("search", "下游更新后的描述");
        ToolSyncService.SyncReport report = this.syncService.sync(downstream.id());

        assertThat(report.succeeded()).isTrue();
        GatewayTool reloaded = this.tools.findById(search.id()).orElseThrow();
        assertThat(reloaded.originalDescription()).isEqualTo("下游更新后的描述");
        // 这两项不能被同步覆盖
        assertThat(reloaded.enabled()).isFalse();
        assertThat(reloaded.customDescription()).isEqualTo("运营写的提示词");
        // 需求 6.5.5：自定义描述非空时完全替换原始描述
        assertThat(reloaded.effectiveDescription()).isEqualTo("运营写的提示词");
    }

    @Test
    @DisplayName("需求 6.4.6：下游删掉的工具在同步后从快照移除，新增的工具被补上")
    void resyncAddsAndRemovesTools() {
        DownstreamMcp downstream = createDownstream("kb_a", MockDownstreamConfig.KB_A_PATH, Map.of());
        this.syncService.sync(downstream.id());
        assertThat(exposedNames(downstream.id())).containsExactly("kb_a__ping", "kb_a__search");

        // 下游删掉 ping，加上 summarize
        this.mockKbA.setTools(List.of("search", "summarize"));
        ToolSyncService.SyncReport report = this.syncService.sync(downstream.id());

        assertThat(report.succeeded()).isTrue();
        assertThat(report.outcome().added()).isEqualTo(1);
        assertThat(report.outcome().removed()).isEqualTo(1);
        assertThat(exposedNames(downstream.id())).containsExactly("kb_a__search", "kb_a__summarize");
    }

    @Test
    @DisplayName("定义没变化的工具被识别为 unchanged，不重写协议字段")
    void unchangedToolsAreDetected() {
        DownstreamMcp downstream = createDownstream("kb_a", MockDownstreamConfig.KB_A_PATH, Map.of());
        this.syncService.sync(downstream.id());
        GatewayTool before = this.tools
                .findByGatewayIdAndExposedName(this.gateway.id(), "kb_a__search").orElseThrow();

        ToolSyncService.SyncReport second = this.syncService.sync(downstream.id());

        assertThat(second.outcome().unchanged()).isEqualTo(2);
        assertThat(second.outcome().updated()).isZero();
        GatewayTool after = this.tools.findById(before.id()).orElseThrow();
        // updated_at 没被推高，但 last_synced_at 前进了
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(after.lastSyncedAt()).isAfterOrEqualTo(before.lastSyncedAt());
    }

    // ------------------------------------------------------------ 失败路径

    @Test
    @DisplayName("需求 6.4.7：同步失败保留上一次成功快照，并把子 MCP 标记为异常")
    void failedSyncKeepsThePreviousSnapshot() {
        DownstreamMcp downstream = createDownstream("kb_a", MockDownstreamConfig.KB_A_PATH, Map.of());
        this.syncService.sync(downstream.id());
        Instant successfulSyncAt = this.downstreams.findById(downstream.id()).orElseThrow().lastSyncAt();
        assertThat(successfulSyncAt).isNotNull();

        // 把地址改成一个连不上的端口，再同步
        this.downstreams.updateConfig(downstream.id(), "kb_a", "http://localhost:1/mcp", null, Instant.now());
        ToolSyncService.SyncReport report = this.syncService.sync(downstream.id());

        assertThat(report.succeeded()).isFalse();
        assertThat(report.errorCode().name()).isIn("DOWNSTREAM_INIT_FAILED", "DOWNSTREAM_SYNC_FAILED",
                "DOWNSTREAM_TIMEOUT");

        DownstreamMcp reloaded = this.downstreams.findById(downstream.id()).orElseThrow();
        assertThat(reloaded.syncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(reloaded.lastSyncError()).isNotBlank();
        // last_sync_at 停在上一次成功的时刻，它的含义是"快照有多新"
        assertThat(reloaded.lastSyncAt()).isEqualTo(successfulSyncAt);
        // 上一次的快照原封不动
        assertThat(exposedNames(downstream.id())).containsExactly("kb_a__ping", "kb_a__search");
    }

    @Test
    @DisplayName("同步失败的错误摘要里不含子 MCP 凭证")
    void failureMessageDoesNotLeakCredentials() {
        DownstreamMcp downstream = createDownstream("kb_a", MockDownstreamConfig.KB_A_PATH,
                Map.of("Authorization", REAL_TOKEN));
        // 指到一个连不上的端口，凭证保持不变
        this.downstreams.updateConfig(downstream.id(), "kb_a", "http://localhost:1/mcp",
                this.headerCodec.encrypt(Map.of("Authorization", REAL_TOKEN)), Instant.now());

        ToolSyncService.SyncReport report = this.syncService.sync(downstream.id());

        assertThat(report.succeeded()).isFalse();
        assertThat(report.errorMessage())
                .doesNotContain("sk-downstream-secret")
                .doesNotContain("Bearer");
        assertThat(this.downstreams.findById(downstream.id()).orElseThrow().lastSyncError())
                .doesNotContain("sk-downstream-secret");
    }

    @Test
    @DisplayName("需求 6.2.9：一个子 MCP 挂掉不影响同网关另一个子 MCP 的同步")
    void oneBrokenDownstreamDoesNotAffectTheOther() {
        DownstreamMcp healthy = createDownstream("kb_a", MockDownstreamConfig.KB_A_PATH, Map.of());
        DownstreamMcp broken = createDownstream("kb_dead", MockDownstreamConfig.KB_A_PATH, Map.of());
        this.downstreams.updateConfig(broken.id(), "kb_dead", "http://localhost:1/mcp", null, Instant.now());

        ToolSyncService.SyncReport brokenReport = this.syncService.sync(broken.id());
        ToolSyncService.SyncReport healthyReport = this.syncService.sync(healthy.id());

        assertThat(brokenReport.succeeded()).isFalse();
        assertThat(healthyReport.succeeded()).isTrue();
        assertThat(exposedNames(healthy.id())).containsExactly("kb_a__ping", "kb_a__search");
        assertThat(exposedNames(broken.id())).isEmpty();
    }

    // -------------------------------------------------------- 重名与命名规则

    @Test
    @DisplayName("需求 15.2.2：两个子 MCP 有同名工具时，聚合名仍然唯一")
    void duplicateToolNamesAcrossDownstreamsDoNotClash() {
        DownstreamMcp kbA = createDownstream("kb_a", MockDownstreamConfig.KB_A_PATH, Map.of());
        DownstreamMcp kbB = createDownstream("kb_b", MockDownstreamConfig.KB_B_PATH, Map.of());

        assertThat(this.syncService.sync(kbA.id()).succeeded()).isTrue();
        assertThat(this.syncService.sync(kbB.id()).succeeded()).isTrue();

        assertThat(this.tools.findByGatewayId(this.gateway.id()))
                .extracting(GatewayTool::exposedName)
                .containsExactlyInAnyOrder("kb_a__search", "kb_a__ping", "kb_b__search", "kb_b__lookup");

        // 两个 search 各自指向自己的子 MCP
        assertThat(this.tools.findByGatewayIdAndExposedName(this.gateway.id(), "kb_a__search")
                .orElseThrow().downstreamMcpId()).isEqualTo(kbA.id());
        assertThat(this.tools.findByGatewayIdAndExposedName(this.gateway.id(), "kb_b__search")
                .orElseThrow().downstreamMcpId()).isEqualTo(kbB.id());
    }

}
