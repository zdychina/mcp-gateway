package com.mcpgateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpgateway.AbstractApiTest;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.GatewayToolRepository;
import com.mcpgateway.security.DownstreamHeaderCodec;
import com.mcpgateway.security.SensitiveDataMasker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 需求 FR-02 的子 MCP 配置 API。 */
class DownstreamMcpApiTest extends AbstractApiTest {

    private static final String REAL_TOKEN = "sk-real-downstream-credential";

    private static final String TWO_SERVERS = """
            {"mcpServers": {
              "kb_a": {"type":"streamable-http","url":"http://127.0.0.1:1/a/mcp",
                       "headers":{"Authorization":"Bearer %s"}},
              "kb_b": {"type":"streamable-http","url":"http://127.0.0.1:1/b/mcp"}
            }}
            """.formatted(REAL_TOKEN);

    @Autowired
    private DownstreamMcpRepository downstreams;

    @Autowired
    private GatewayToolRepository tools;

    @Autowired
    private DownstreamHeaderCodec headerCodec;

    private String createGateway() throws Exception {
        MvcResult result = this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"gw\",\"slug\":\"%s\"}".formatted(uniqueSlug("ds"))))
                .andExpect(status().isCreated())
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/gateway/id").asText();
    }

    private MvcResult importServers(String gatewayId, String payload) throws Exception {
        return this.mockMvc.perform(post("/api/gateways/{id}/mcp-servers/import", gatewayId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andReturn();
    }

    @Test
    @DisplayName("需求 15.1.2 / 15.1.5：能导入 2 个子 MCP，且凭证不明文回显")
    void importsServersWithoutEchoingCredentials() throws Exception {
        String gatewayId = createGateway();

        MvcResult result = importServers(gatewayId, TWO_SERVERS);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();

        JsonNode root = this.objectMapper.readTree(body);
        JsonNode detail = root.at("/data/gateway");
        assertThat(detail.at("/downstreams").size()).isEqualTo(2);
        // 导入后立即同步过一次（需求 6.4.2），下游不可达所以是 UNAVAILABLE
        assertThat(detail.at("/status").asText()).isEqualTo("UNAVAILABLE");
        // 需求 6.2.9：每个子 MCP 各自记录成败，配置照样全部落库
        assertThat(root.at("/data/syncResults").size()).isEqualTo(2);
        assertThat(root.at("/data/syncResults/0/succeeded").asBoolean()).isFalse();
        assertThat(root.at("/data/syncResults/0/errorCode").asText()).startsWith("DOWNSTREAM_");

        // 需求 12.4：只回 header 名称和遮罩值
        JsonNode kbA = detail.at("/downstreams/0");
        assertThat(kbA.at("/name").asText()).isEqualTo("kb_a");
        assertThat(kbA.at("/syncStatus").asText()).isEqualTo("FAILED");
        assertThat(kbA.at("/headers/Authorization").asText()).isEqualTo(SensitiveDataMasker.MASK);
        assertThat(detail.at("/downstreams/1/headers").isEmpty()).isTrue();

        // 需求 15.1.5：整个响应里不得出现真凭证
        assertThat(body).doesNotContain(REAL_TOKEN);

        // 但库里存的是可解回来的密文，不是遮罩值
        DownstreamMcp stored = this.downstreams.findByGatewayId(gatewayId).stream()
                .filter(d -> d.name().equals("kb_a")).findFirst().orElseThrow();
        assertThat(stored.encryptedHeadersJson()).doesNotContain(REAL_TOKEN);
        assertThat(this.headerCodec.decrypt(stored.encryptedHeadersJson()))
                .containsEntry("Authorization", "Bearer " + REAL_TOKEN);
    }

    @Test
    @DisplayName("需求 15.1.3：超过 3 个子 MCP 时返回 MCP_SERVER_LIMIT_EXCEEDED")
    void enforcesThreeServerLimitAcrossImports() throws Exception {
        String gatewayId = createGateway();
        importServers(gatewayId, TWO_SERVERS);

        // 再导入 2 个只剩 1 个名额
        this.mockMvc.perform(post("/api/gateways/{id}/mcp-servers/import", gatewayId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mcpServers": {
                          "kb_c": {"type":"streamable-http","url":"http://127.0.0.1:1/c/mcp"},
                          "kb_d": {"type":"streamable-http","url":"http://127.0.0.1:1/d/mcp"}
                        }}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MCP_SERVER_LIMIT_EXCEEDED"));

        // 导入第 3 个可以
        this.mockMvc.perform(post("/api/gateways/{id}/mcp-servers/import", gatewayId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mcpServers": {"kb_c": {"type":"streamable-http","url":"http://127.0.0.1:1/c/mcp"}}}
                        """))
                .andExpect(status().isOk());

        // 第 4 个不行
        this.mockMvc.perform(post("/api/gateways/{id}/mcp-servers/import", gatewayId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mcpServers": {"kb_d": {"type":"streamable-http","url":"http://127.0.0.1:1/d/mcp"}}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MCP_SERVER_LIMIT_EXCEEDED"));

        assertThat(this.downstreams.countByGatewayId(gatewayId)).isEqualTo(3);
    }

    @Test
    @DisplayName("需求 15.1.4：stdio 配置被拒绝，且整批不落库")
    void rejectsStdioAndRollsBackTheWholeBatch() throws Exception {
        String gatewayId = createGateway();

        this.mockMvc.perform(post("/api/gateways/{id}/mcp-servers/import", gatewayId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mcpServers": {
                          "kb_ok": {"type":"streamable-http","url":"http://127.0.0.1:1/ok/mcp"},
                          "kb_bad": {"command":"npx","args":["-y","x"]}
                        }}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_TRANSPORT"));

        // 合法的那个也不能留下来，否则操作人会拿到一个半成品配置
        assertThat(this.downstreams.countByGatewayId(gatewayId)).isZero();
    }

    @Test
    @DisplayName("需求 6.2.2：与已有子 MCP 重名时返回 DUPLICATE_DOWNSTREAM_NAME")
    void rejectsDuplicateDownstreamName() throws Exception {
        String gatewayId = createGateway();
        importServers(gatewayId, TWO_SERVERS);

        this.mockMvc.perform(post("/api/gateways/{id}/mcp-servers/import", gatewayId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mcpServers": {"kb_a": {"type":"streamable-http","url":"http://127.0.0.1:1/x/mcp"}}}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_DOWNSTREAM_NAME"));
    }

    @Test
    @DisplayName("需求 6.3.6：子 MCP 改名会连带重算所有聚合工具名")
    void renamingDownstreamRewritesExposedToolNames() throws Exception {
        String gatewayId = createGateway();
        importServers(gatewayId, TWO_SERVERS);
        DownstreamMcp kbA = this.downstreams.findByGatewayId(gatewayId).stream()
                .filter(d -> d.name().equals("kb_a")).findFirst().orElseThrow();

        // 造两条已同步的工具快照，其中一条被停用并写了自定义描述
        Instant now = Instant.now();
        GatewayTool search = new GatewayTool(UUID.randomUUID().toString(), gatewayId, kbA.id(), "search",
                "kb_a__search", "orig", "自定义描述", "{}", null, null, false, "h", now, now, now);
        GatewayTool ping = new GatewayTool(UUID.randomUUID().toString(), gatewayId, kbA.id(), "ping",
                "kb_a__ping", "orig", null, "{}", null, null, true, "h", now, now, now);
        this.tools.insert(search);
        this.tools.insert(ping);

        this.mockMvc.perform(put("/api/gateways/{id}/mcp-servers/{serverId}", gatewayId, kbA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"kb_alpha","url":"http://127.0.0.1:1/a/mcp"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downstreams[?(@.name=='kb_alpha')]").exists());

        assertThat(this.tools.findByDownstreamMcpId(kbA.id()))
                .extracting(GatewayTool::exposedName)
                .containsExactlyInAnyOrder("kb_alpha__search", "kb_alpha__ping");

        // 改名不得顺手把操作人的配置抹掉
        GatewayTool reloaded = this.tools.findById(search.id()).orElseThrow();
        assertThat(reloaded.enabled()).isFalse();
        assertThat(reloaded.customDescription()).isEqualTo("自定义描述");
    }

    @Test
    @DisplayName("编辑时不传 headers 表示保持原样，传空对象才是清空")
    void omittedHeadersAreKeptAndEmptyObjectClears() throws Exception {
        String gatewayId = createGateway();
        importServers(gatewayId, TWO_SERVERS);
        DownstreamMcp kbA = this.downstreams.findByGatewayId(gatewayId).stream()
                .filter(d -> d.name().equals("kb_a")).findFirst().orElseThrow();

        // 不传 headers：原凭证必须还在。这是关键行为 —— 前端拿到的是遮罩值，
        // 如果原样提交回来会把真凭证覆盖成 ******。
        this.mockMvc.perform(put("/api/gateways/{id}/mcp-servers/{serverId}", gatewayId, kbA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"kb_a\",\"url\":\"http://127.0.0.1:1/a2/mcp\"}"))
                .andExpect(status().isOk());

        DownstreamMcp afterUrlChange = this.downstreams.findById(kbA.id()).orElseThrow();
        assertThat(afterUrlChange.url()).isEqualTo("http://127.0.0.1:1/a2/mcp");
        assertThat(this.headerCodec.decrypt(afterUrlChange.encryptedHeadersJson()))
                .containsEntry("Authorization", "Bearer " + REAL_TOKEN);

        // 传空对象：清空
        this.mockMvc.perform(put("/api/gateways/{id}/mcp-servers/{serverId}", gatewayId, kbA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"kb_a\",\"url\":\"http://127.0.0.1:1/a2/mcp\",\"headers\":{}}"))
                .andExpect(status().isOk());

        assertThat(this.headerCodec.decrypt(
                this.downstreams.findById(kbA.id()).orElseThrow().encryptedHeadersJson())).isEmpty();
    }

    @Test
    @DisplayName("需求 12.7：编辑时同样校验 URL 协议与 user-info")
    void validatesUrlOnUpdate() throws Exception {
        String gatewayId = createGateway();
        importServers(gatewayId, TWO_SERVERS);
        DownstreamMcp kbA = this.downstreams.findByGatewayId(gatewayId).stream()
                .filter(d -> d.name().equals("kb_a")).findFirst().orElseThrow();

        this.mockMvc.perform(put("/api/gateways/{id}/mcp-servers/{serverId}", gatewayId, kbA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"kb_a\",\"url\":\"https://user:pass@a.example.com/mcp\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MCP_CONFIG"));

        this.mockMvc.perform(put("/api/gateways/{id}/mcp-servers/{serverId}", gatewayId, kbA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"kb__a\",\"url\":\"http://127.0.0.1:1/a/mcp\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MCP_CONFIG"));
    }

    @Test
    @DisplayName("需求 6.2.10：删除子 MCP 后其工具快照一并消失")
    void deletingDownstreamRemovesItsTools() throws Exception {
        String gatewayId = createGateway();
        importServers(gatewayId, TWO_SERVERS);
        DownstreamMcp kbA = this.downstreams.findByGatewayId(gatewayId).stream()
                .filter(d -> d.name().equals("kb_a")).findFirst().orElseThrow();
        Instant now = Instant.now();
        this.tools.insert(new GatewayTool(UUID.randomUUID().toString(), gatewayId, kbA.id(), "search",
                "kb_a__search", "orig", null, "{}", null, null, true, "h", now, now, now));

        this.mockMvc.perform(delete("/api/gateways/{id}/mcp-servers/{serverId}", gatewayId, kbA.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downstreams.length()").value(1));

        assertThat(this.tools.findByDownstreamMcpId(kbA.id())).isEmpty();
        assertThat(this.tools.findByGatewayId(gatewayId)).isEmpty();
    }

    @Test
    @DisplayName("用别的网关的 id 访问子 MCP 时返回 DOWNSTREAM_NOT_FOUND，不能越权")
    void downstreamAccessIsScopedToItsGateway() throws Exception {
        String gatewayId = createGateway();
        String otherGatewayId = createGateway();
        importServers(gatewayId, TWO_SERVERS);
        DownstreamMcp kbA = this.downstreams.findByGatewayId(gatewayId).stream()
                .filter(d -> d.name().equals("kb_a")).findFirst().orElseThrow();

        this.mockMvc.perform(delete("/api/gateways/{id}/mcp-servers/{serverId}", otherGatewayId, kbA.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOWNSTREAM_NOT_FOUND"));

        this.mockMvc.perform(put("/api/gateways/{id}/mcp-servers/{serverId}", otherGatewayId, kbA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"hijack\",\"url\":\"http://127.0.0.1:1/evil/mcp\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOWNSTREAM_NOT_FOUND"));

        assertThat(this.downstreams.findById(kbA.id()).orElseThrow().name()).isEqualTo("kb_a");
    }

    @Test
    @DisplayName("需求 6.2.8：下游不可达时同步端点仍返回 200，失败细节在 body 里")
    void syncEndpointReportsFailureWithoutHttpError() throws Exception {
        String gatewayId = createGateway();
        importServers(gatewayId, TWO_SERVERS);
        DownstreamMcp kbA = this.downstreams.findByGatewayId(gatewayId).stream()
                .filter(d -> d.name().equals("kb_a")).findFirst().orElseThrow();

        // 请求本身是处理成功的，失败的是与下游的交互 —— 前端要能一次拿到细节而不是猜
        this.mockMvc.perform(post("/api/gateways/{id}/mcp-servers/{serverId}/sync", gatewayId, kbA.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.succeeded").value(false))
                .andExpect(jsonPath("$.data.downstreamName").value("kb_a"))
                .andExpect(jsonPath("$.data.errorCode").exists());

        assertThat(this.downstreams.findById(kbA.id()).orElseThrow().syncStatus())
                .isEqualTo(com.mcpgateway.domain.SyncStatus.FAILED);
    }

    @Test
    @DisplayName("对别的网关的子 MCP 触发同步返回 DOWNSTREAM_NOT_FOUND")
    void syncIsScopedToItsGateway() throws Exception {
        String gatewayId = createGateway();
        String otherGatewayId = createGateway();
        importServers(gatewayId, TWO_SERVERS);
        DownstreamMcp kbA = this.downstreams.findByGatewayId(gatewayId).stream()
                .filter(d -> d.name().equals("kb_a")).findFirst().orElseThrow();

        this.mockMvc.perform(post("/api/gateways/{id}/mcp-servers/{serverId}/sync", otherGatewayId, kbA.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOWNSTREAM_NOT_FOUND"));
    }

    @Test
    @DisplayName("网关不存在时导入返回 GATEWAY_NOT_FOUND")
    void importRequiresAnExistingGateway() throws Exception {
        this.mockMvc.perform(post("/api/gateways/{id}/mcp-servers/import", "no-such-gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content(TWO_SERVERS))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GATEWAY_NOT_FOUND"));
    }
}
