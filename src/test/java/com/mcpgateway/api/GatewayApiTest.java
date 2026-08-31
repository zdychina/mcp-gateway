package com.mcpgateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpgateway.AbstractApiTest;
import com.mcpgateway.repository.GatewayRepository;
import com.mcpgateway.security.AccessTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 需求 FR-01 / FR-05 的管理 API。 */
class GatewayApiTest extends AbstractApiTest {

    @Autowired
    private GatewayRepository gateways;

    @Autowired
    private AccessTokenService accessTokens;

    private JsonNode createGateway(String slug) throws Exception {
        MvcResult result = this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"知识库网关","slug":"%s","description":"聚合两个知识库"}
                        """.formatted(slug)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("需求 15.1.1：能创建网关，并只在创建时返回一次明文令牌")
    void createsGatewayAndReturnsTokenOnce() throws Exception {
        String slug = uniqueSlug("alpha");
        JsonNode body = createGateway(slug);

        String token = body.at("/data/accessToken").asText();
        assertThat(token).startsWith(AccessTokenService.TOKEN_PREFIX);
        assertThat(body.at("/data/gateway/slug").asText()).isEqualTo(slug);
        assertThat(body.at("/data/gateway/status").asText()).isEqualTo("EMPTY");
        assertThat(body.at("/data/gateway/mcpUrl").asText()).isEqualTo("http://localhost:8080/mcp/" + slug);
        assertThat(body.at("/error").isNull()).isTrue();

        // 需求 12.3：库里只有哈希，且哈希与明文对得上。
        String gatewayId = body.at("/data/gateway/id").asText();
        String storedHash = this.gateways.findById(gatewayId).orElseThrow().accessTokenHash();
        assertThat(storedHash).isNotEqualTo(token);
        assertThat(this.accessTokens.matches(token, storedHash)).isTrue();

        // 之后任何查询接口都拿不到明文
        this.mockMvc.perform(get("/api/gateways/{id}", gatewayId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    @Test
    @DisplayName("需求 6.1.2：slug 重复返回 DUPLICATE_GATEWAY_SLUG")
    void rejectsDuplicateSlug() throws Exception {
        String slug = uniqueSlug("dup");
        createGateway(slug);

        this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"另一个","slug":"%s"}
                        """.formatted(slug)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_GATEWAY_SLUG"));
    }

    @Test
    @DisplayName("需求 6.1.1-6.1.3：名称必填、slug 字符受限、描述长度受限")
    void validatesGatewayFields() throws Exception {
        this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"slug\":\"ok-slug\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("name")));

        this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"n\",\"slug\":\"bad slug!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("slug")));

        this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"n\",\"slug\":\"ok\",\"description\":\"%s\"}".formatted("x".repeat(4001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("列表返回状态、子 MCP 数量、工具数量和 MCP 地址")
    void listsGatewaysWithDerivedFields() throws Exception {
        String slug = uniqueSlug("list");
        createGateway(slug);

        this.mockMvc.perform(get("/api/gateways"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[?(@.slug=='" + slug + "')].status").value("EMPTY"))
                .andExpect(jsonPath("$.data[?(@.slug=='" + slug + "')].downstreamCount").value(0))
                .andExpect(jsonPath("$.data[?(@.slug=='" + slug + "')].toolCount").value(0));
    }

    @Test
    @DisplayName("可以编辑名称、slug 和描述")
    void updatesGateway() throws Exception {
        String gatewayId = createGateway(uniqueSlug("upd")).at("/data/gateway/id").asText();
        String newSlug = uniqueSlug("renamed");

        this.mockMvc.perform(put("/api/gateways/{id}", gatewayId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"新名字","slug":"%s","description":"新描述"}
                        """.formatted(newSlug)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("新名字"))
                .andExpect(jsonPath("$.data.slug").value(newSlug))
                .andExpect(jsonPath("$.data.description").value("新描述"))
                // slug 变了，Agent 的 MCP 地址随之改变
                .andExpect(jsonPath("$.data.mcpUrl").value("http://localhost:8080/mcp/" + newSlug));
    }

    @Test
    @DisplayName("改成别人已占用的 slug 被拒绝，改回自己原来的 slug 不算冲突")
    void slugConflictOnlyAppliesToOtherGateways() throws Exception {
        String takenSlug = uniqueSlug("taken");
        createGateway(takenSlug);
        String ownSlug = uniqueSlug("own");
        String gatewayId = createGateway(ownSlug).at("/data/gateway/id").asText();

        this.mockMvc.perform(put("/api/gateways/{id}", gatewayId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"n\",\"slug\":\"%s\"}".formatted(takenSlug)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_GATEWAY_SLUG"));

        this.mockMvc.perform(put("/api/gateways/{id}", gatewayId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"仅改名\",\"slug\":\"%s\"}".formatted(ownSlug)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("删除后再查返回 GATEWAY_NOT_FOUND")
    void deletesGateway() throws Exception {
        String gatewayId = createGateway(uniqueSlug("del")).at("/data/gateway/id").asText();

        this.mockMvc.perform(delete("/api/gateways/{id}", gatewayId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        this.mockMvc.perform(get("/api/gateways/{id}", gatewayId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GATEWAY_NOT_FOUND"));

        this.mockMvc.perform(delete("/api/gateways/{id}", gatewayId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("需求 FR-05：接入 JSON 用配置的 baseUrl，令牌位置是占位符")
    void agentConfigUsesConfiguredBaseUrlAndTokenPlaceholder() throws Exception {
        String slug = uniqueSlug("agent");
        String gatewayId = createGateway(slug).at("/data/gateway/id").asText();

        MvcResult result = this.mockMvc.perform(get("/api/gateways/{id}/agent-config", gatewayId)
                // 需求 FR-05.1：即使伪造 Host 也不能影响输出的 baseUrl
                .header("Host", "evil.example.com")
                .header("X-Forwarded-Host", "evil.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mcpServers." + slug + ".type").value("streamable-http"))
                .andExpect(jsonPath("$.data.mcpServers." + slug + ".url")
                        .value("http://localhost:8080/mcp/" + slug))
                .andExpect(jsonPath("$.data.mcpServers." + slug + ".headers.Authorization")
                        .value("Bearer <gateway-access-token>"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("evil.example.com");
    }

    @Test
    @DisplayName("需求 FR-05.3：轮换后拿到新令牌，旧令牌立即失效")
    void rotatesAccessToken() throws Exception {
        JsonNode created = createGateway(uniqueSlug("rot"));
        String gatewayId = created.at("/data/gateway/id").asText();
        String originalToken = created.at("/data/accessToken").asText();

        MvcResult result = this.mockMvc.perform(post("/api/gateways/{id}/access-token/rotate", gatewayId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        String rotatedToken = this.objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/accessToken").asText();

        assertThat(rotatedToken).isNotEqualTo(originalToken);
        String storedHash = this.gateways.findById(gatewayId).orElseThrow().accessTokenHash();
        assertThat(this.accessTokens.matches(rotatedToken, storedHash)).isTrue();
        assertThat(this.accessTokens.matches(originalToken, storedHash)).isFalse();
    }

    @Test
    @DisplayName("需求 §8：错误响应不含 Java 堆栈")
    void errorResponsesCarryNoStackTrace() throws Exception {
        MvcResult result = this.mockMvc.perform(get("/api/gateways/{id}", "no-such-id"))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("java.lang")
                .doesNotContain("com.mcpgateway.error")
                .doesNotContain("at com.");
    }
}
