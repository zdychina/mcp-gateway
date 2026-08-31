package com.mcpgateway.api;

import com.mcpgateway.AbstractApiTest;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.domain.SyncStatus;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.GatewayToolRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 需求 6.5 的工具启停与描述覆盖。 */
class GatewayToolApiTest extends AbstractApiTest {

    private static final String INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}";

    @Autowired
    private DownstreamMcpRepository downstreams;

    @Autowired
    private GatewayToolRepository tools;

    private String gatewayId;

    private GatewayTool seedTool() throws Exception {
        MvcResult result = this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"gw\",\"slug\":\"%s\"}".formatted(uniqueSlug("tool"))))
                .andExpect(status().isCreated())
                .andReturn();
        this.gatewayId = this.objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/gateway/id").asText();

        DownstreamMcp downstream = new DownstreamMcp(UUID.randomUUID().toString(), this.gatewayId, "kb_a",
                DownstreamMcp.TYPE_STREAMABLE_HTTP, "http://127.0.0.1:1/mcp", null,
                SyncStatus.SUCCESS, Instant.now(), null, Instant.now(), Instant.now());
        this.downstreams.insert(downstream);

        Instant now = Instant.now();
        GatewayTool tool = new GatewayTool(UUID.randomUUID().toString(), this.gatewayId, downstream.id(),
                "search", "kb_a__search", "下游的原始描述", null, INPUT_SCHEMA, null, null,
                true, "hash-v1", now, now, now);
        this.tools.insert(tool);
        return tool;
    }

    private MvcResult patchTool(String toolId, String body) throws Exception {
        return this.mockMvc.perform(patch("/api/gateways/{id}/tools/{toolId}", this.gatewayId, toolId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andReturn();
    }

    @Test
    @DisplayName("需求 6.5.2 / 6.5.3：可以停用再启用，状态立即体现在详情里")
    void togglesEnabledState() throws Exception {
        GatewayTool tool = seedTool();

        patchTool(tool.id(), "{\"enabled\":false}");
        assertThat(this.tools.findById(tool.id()).orElseThrow().enabled()).isFalse();
        this.mockMvc.perform(get("/api/gateways/{id}", this.gatewayId))
                .andExpect(jsonPath("$.data.downstreams[0].tools[0].enabled").value(false));
        assertThat(this.tools.findEnabledByGatewayId(this.gatewayId)).isEmpty();

        patchTool(tool.id(), "{\"enabled\":true}");
        assertThat(this.tools.findById(tool.id()).orElseThrow().enabled()).isTrue();
        assertThat(this.tools.findEnabledByGatewayId(this.gatewayId)).hasSize(1);
    }

    @Test
    @DisplayName("需求 6.5.5：自定义描述覆盖原始描述，清空后回退")
    void overridesAndClearsDescription() throws Exception {
        GatewayTool tool = seedTool();

        this.mockMvc.perform(patch("/api/gateways/{id}/tools/{toolId}", this.gatewayId, tool.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customDescription\":\"运营写的提示词\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveDescription").value("运营写的提示词"))
                .andExpect(jsonPath("$.data.originalDescription").value("下游的原始描述"));

        // 传 null 清空
        this.mockMvc.perform(patch("/api/gateways/{id}/tools/{toolId}", this.gatewayId, tool.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customDescription\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customDescription").doesNotExist())
                .andExpect(jsonPath("$.data.effectiveDescription").value("下游的原始描述"));

        // 空白串同样视为清空，不能让 Agent 看到空描述
        patchTool(tool.id(), "{\"customDescription\":\"覆盖\"}");
        this.mockMvc.perform(patch("/api/gateways/{id}/tools/{toolId}", this.gatewayId, tool.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customDescription\":\"   \"}"))
                .andExpect(jsonPath("$.data.effectiveDescription").value("下游的原始描述"));
    }

    @Test
    @DisplayName("PATCH 语义：不传的字段保持不变，不会被当成清空")
    void omittedFieldsAreLeftAlone() throws Exception {
        GatewayTool tool = seedTool();
        patchTool(tool.id(), "{\"customDescription\":\"运营写的提示词\",\"enabled\":false}");

        // 只改 enabled，不提 customDescription —— 描述必须还在
        this.mockMvc.perform(patch("/api/gateways/{id}/tools/{toolId}", this.gatewayId, tool.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.customDescription").value("运营写的提示词"));

        // 只改描述，不提 enabled —— 启用状态必须还在
        patchTool(tool.id(), "{\"enabled\":false}");
        this.mockMvc.perform(patch("/api/gateways/{id}/tools/{toolId}", this.gatewayId, tool.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customDescription\":\"换个描述\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.customDescription").value("换个描述"));
    }

    @Test
    @DisplayName("需求 6.5.6 / 15.2.7：改描述不会动到名称和 Schema，也没有改它们的入口")
    void cannotChangeNameOrSchema() throws Exception {
        GatewayTool tool = seedTool();

        // 即便请求体里塞了这些字段，也必须被忽略
        patchTool(tool.id(), """
                {"customDescription":"新描述","exposedName":"hacked","originalName":"hacked",
                 "inputSchemaJson":"{}","annotationsJson":"{\\"readOnlyHint\\":true}"}
                """);

        GatewayTool reloaded = this.tools.findById(tool.id()).orElseThrow();
        assertThat(reloaded.customDescription()).isEqualTo("新描述");
        assertThat(reloaded.exposedName()).isEqualTo("kb_a__search");
        assertThat(reloaded.originalName()).isEqualTo("search");
        assertThat(reloaded.inputSchemaJson()).isEqualTo(INPUT_SCHEMA);
        assertThat(reloaded.annotationsJson()).isNull();
    }

    @Test
    @DisplayName("自定义描述超长被拒绝")
    void rejectsOverlongCustomDescription() throws Exception {
        GatewayTool tool = seedTool();

        this.mockMvc.perform(patch("/api/gateways/{id}/tools/{toolId}", this.gatewayId, tool.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(this.objectMapper.writeValueAsString(
                        java.util.Map.of("customDescription", "x".repeat(4001)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("用别的网关的 id 改工具返回 TOOL_NOT_FOUND，不能越权")
    void toolAccessIsScopedToItsGateway() throws Exception {
        GatewayTool tool = seedTool();
        MvcResult other = this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"other\",\"slug\":\"%s\"}".formatted(uniqueSlug("other"))))
                .andReturn();
        String otherGatewayId = this.objectMapper.readTree(other.getResponse().getContentAsString())
                .at("/data/gateway/id").asText();

        this.mockMvc.perform(patch("/api/gateways/{id}/tools/{toolId}", otherGatewayId, tool.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TOOL_NOT_FOUND"));

        assertThat(this.tools.findById(tool.id()).orElseThrow().enabled()).isTrue();
    }

    @Test
    @DisplayName("工具不存在时返回 TOOL_NOT_FOUND")
    void unknownToolIsRejected() throws Exception {
        seedTool();

        this.mockMvc.perform(patch("/api/gateways/{id}/tools/{toolId}", this.gatewayId, "no-such-tool")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TOOL_NOT_FOUND"));
    }
}
