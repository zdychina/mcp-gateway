package com.mcpgateway.api;

import com.mcpgateway.AbstractApiTest;
import com.mcpgateway.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 需求 12.7：关掉下游 TLS 校验之后，这件事必须一路传到界面上。
 *
 * 单独一个类而不是塞进 {@link GatewayApiTest}，是因为它需要一个属性不同的 Spring 上下文 ——
 * 混在一起会让整个测试类都跑在"校验已关闭"的配置下，而那正是我们要确保**不是**默认值的东西。
 *
 * 这里不去连真的 TLS 端点：造一个自签证书的服务器再断言握手能过，测的是 JDK 而不是我们的代码。
 * 真正值得钉住的是"开关有没有一路传到看得见的地方"，以及
 * {@code SecurityInvariantsTest} 守着的"默认关闭 + 两样都关"。
 */
@TestPropertySource(properties = "mcp-gateway.downstream.insecure-skip-tls-verify=true")
class InsecureDownstreamTlsApiTest extends AbstractApiTest {

    @Autowired
    private GatewayProperties properties;

    @Test
    @DisplayName("环境变量能绑上，且详情接口把它带给前端")
    void exposesTheDisabledPostureToTheUi() throws Exception {
        assertThat(this.properties.getDownstream().isInsecureSkipTlsVerify())
                .as("mcp-gateway.downstream.insecure-skip-tls-verify 是否绑上了")
                .isTrue();

        MvcResult created = this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"内网网关","slug":"insecure-tls-gw","description":null}
                        """))
                .andExpect(status().isCreated())
                .andReturn();
        String gatewayId = this.objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/gateway/id").asText();

        this.mockMvc.perform(get("/api/gateways/" + gatewayId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insecureDownstreamTls").value(true));
    }
}
