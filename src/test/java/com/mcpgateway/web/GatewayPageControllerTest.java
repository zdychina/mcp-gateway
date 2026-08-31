package com.mcpgateway.web;

import com.mcpgateway.AbstractApiTest;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.domain.SyncStatus;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** 需求 §10 的两个页面。 */
class GatewayPageControllerTest extends AbstractApiTest {

    private static final String REAL_TOKEN = "sk-downstream-real-credential";

    @Autowired
    private DownstreamMcpRepository downstreams;

    @Autowired
    private GatewayToolRepository tools;

    @Autowired
    private DownstreamHeaderCodec headerCodec;

    private String createGateway(String name) throws Exception {
        MvcResult result = this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"slug\":\"%s\",\"description\":\"网关用途说明\"}"
                        .formatted(name, uniqueSlug("ui"))))
                .andExpect(status().isCreated())
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/gateway/id").asText();
    }

    private DownstreamMcp seedDownstreamWithTool(String gatewayId) {
        DownstreamMcp downstream = new DownstreamMcp(UUID.randomUUID().toString(), gatewayId, "kb_a",
                DownstreamMcp.TYPE_STREAMABLE_HTTP, "https://kb.example.com/mcp",
                this.headerCodec.encrypt(Map.of("Authorization", "Bearer " + REAL_TOKEN)),
                SyncStatus.SUCCESS, Instant.now(), null, Instant.now(), Instant.now());
        this.downstreams.insert(downstream);

        Instant now = Instant.now();
        this.tools.insert(new GatewayTool(UUID.randomUUID().toString(), gatewayId, downstream.id(),
                "search", "kb_a__search", "下游的原始描述", "运营写的提示词",
                "{\"type\":\"object\"}", null, null, true, "hash", now, now, now));
        this.tools.insert(new GatewayTool(UUID.randomUUID().toString(), gatewayId, downstream.id(),
                "ping", "kb_a__ping", "原始 ping 描述", null,
                "{\"type\":\"object\"}", null, null, false, "hash", now, now, now));
        return downstream;
    }

    @Test
    @DisplayName("根路径重定向到网关列表")
    void rootRedirectsToTheList() throws Exception {
        this.mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/gateways"));
    }

    @Test
    @DisplayName("需求 10.1：列表页展示名称、slug、状态、数量和更新时间")
    void listPageShowsRequiredColumns() throws Exception {
        String gatewayId = createGateway("列表页网关");
        seedDownstreamWithTool(gatewayId);

        MvcResult result = this.mockMvc.perform(get("/ui/gateways"))
                .andExpect(status().isOk())
                .andExpect(view().name("gateways"))
                .andExpect(model().attributeExists("gateways"))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("列表页网关");
        assertThat(html).contains("READY");
        assertThat(html).contains("创建网关");
        // 指向详情页的链接
        assertThat(html).contains("/ui/gateways/" + gatewayId);
    }

    @Test
    @DisplayName("列表页为空时给出引导文案，不是一张空表")
    void listPageHandlesEmptyState() throws Exception {
        assertThat(this.mockMvc.perform(get("/ui/gateways"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .contains("创建网关");
    }

    @Test
    @DisplayName("需求 10.2：详情页四段内容齐全")
    void detailPageRendersAllFourSections() throws Exception {
        String gatewayId = createGateway("详情页网关");
        seedDownstreamWithTool(gatewayId);

        MvcResult result = this.mockMvc.perform(get("/ui/gateways/{id}", gatewayId))
                .andExpect(status().isOk())
                .andExpect(view().name("gateway-detail"))
                .andExpect(model().attributeExists("gateway", "agentConfigJson"))
                .andReturn();
        String html = result.getResponse().getContentAsString();

        // 1 基本信息
        assertThat(html).contains("基本信息").contains("详情页网关").contains("网关用途说明");
        // 2 子 MCP 配置：导入、状态、测试并同步、编辑、删除
        assertThat(html).contains("粘贴 mcpServers 配置 JSON")
                .contains("kb_a")
                .contains("测试并同步")
                .contains("https://kb.example.com/mcp");
        // 3 聚合工具：按子 MCP 分组，启停开关和描述编辑框
        assertThat(html).contains("聚合工具")
                .contains("kb_a__search")
                .contains("kb_a__ping")
                .contains("下游的原始描述")
                .contains("运营写的提示词");
        // 4 Agent 接入：MCP URL、接入 JSON、复制、轮换
        assertThat(html).contains("Agent 接入")
                .contains("/mcp/")
                .contains("streamable-http")
                .contains("轮换访问令牌");
    }

    @Test
    @DisplayName("需求 12.4：页面上的 headers 只有名称和遮罩值，没有真实凭证")
    void detailPageNeverRendersRealCredentials() throws Exception {
        String gatewayId = createGateway("凭证网关");
        seedDownstreamWithTool(gatewayId);

        String html = this.mockMvc.perform(get("/ui/gateways/{id}", gatewayId))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Authorization");
        assertThat(html).contains(SensitiveDataMasker.MASK);
        assertThat(html).doesNotContain(REAL_TOKEN);
    }

    @Test
    @DisplayName("需求 FR-05.3：页面上的接入 JSON 用占位符，不含真实令牌")
    void agentConfigOnThePageUsesAPlaceholder() throws Exception {
        String gatewayId = createGateway("接入网关");

        String html = this.mockMvc.perform(get("/ui/gateways/{id}", gatewayId))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("&lt;gateway-access-token&gt;");
        assertThat(html).doesNotContain("mcpgw_");
    }

    @Test
    @DisplayName("停用的工具在页面上显示为未勾选，启用的显示为勾选")
    void toolSwitchesReflectStoredState() throws Exception {
        String gatewayId = createGateway("开关网关");
        seedDownstreamWithTool(gatewayId);

        String html = this.mockMvc.perform(get("/ui/gateways/{id}", gatewayId))
                .andReturn().getResponse().getContentAsString();

        GatewayTool search = this.tools.findByGatewayIdAndExposedName(gatewayId, "kb_a__search").orElseThrow();
        GatewayTool ping = this.tools.findByGatewayIdAndExposedName(gatewayId, "kb_a__ping").orElseThrow();

        assertThat(html).contains("id=\"enabled-" + search.id() + "\"");
        assertThat(html).contains("id=\"enabled-" + ping.id() + "\"");
        // 只有启用的那个带 checked
        int searchIndex = html.indexOf("enabled-" + search.id());
        int pingIndex = html.indexOf("enabled-" + ping.id());
        assertThat(html.substring(searchIndex, searchIndex + 200)).contains("checked");
        assertThat(html.substring(pingIndex, pingIndex + 200)).doesNotContain("checked");
    }

    @Test
    @DisplayName("网关不存在时页面返回 404，而不是 500")
    void unknownGatewayReturnsNotFound() throws Exception {
        this.mockMvc.perform(get("/ui/gateways/{id}", "no-such-gateway"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("静态资源随应用一起提供，不依赖外网 CDN")
    void staticAssetsAreServedLocally() throws Exception {
        this.mockMvc.perform(get("/css/bootstrap.min.css"))
                .andExpect(status().isOk());
        // 只验证资源存在。响应 charset 由 UiServingTest 在真实 Tomcat 下验证 ——
        // MockHttpServletResponse 的 charset 行为和容器不一致，在这里断言会得出错误结论。
        this.mockMvc.perform(get("/js/app.js")).andExpect(status().isOk());
        this.mockMvc.perform(get("/css/app.css")).andExpect(status().isOk());
    }
}
