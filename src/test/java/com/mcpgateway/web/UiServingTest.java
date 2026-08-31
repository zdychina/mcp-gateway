package com.mcpgateway.web;

import com.mcpgateway.TestMasterKey;
import com.mcpgateway.downstream.MockDownstreamConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在真实 Tomcat 下验证页面与静态资源的响应头。
 *
 * 为什么不放在 MockMvc 测试里：MockHttpServletResponse 对 charset 的处理和真实容器不一致，
 * 在那边断言 charset 会得出与线上相反的结论。页面文案和脚本里都有中文，这条必须按容器行为验。
 *
 * 注解与 AgentEndToEndTest 保持一致，好让 Spring 复用同一个测试上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { com.mcpgateway.McpGatewayApplication.class, MockDownstreamConfig.class })
@ActiveProfiles("test")
class UiServingTest {

    /*
     * 这个类同时守住一条容易回归的配置：src/test/resources 下的配置文件必须叫
     * application-test.yml。一旦有人把它改回 application.yml，就会和主配置同为
     * classpath:/application.yml 而把后者整份遮蔽掉，charset 相关的断言会立刻变红。
     */

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mcp-gateway.security.master-key", () -> TestMasterKey.BASE64);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private ResponseEntity<String> fetch(String path) {
        return this.restTemplate.getForEntity(path, String.class);
    }

    @Test
    @DisplayName("页面以 UTF-8 返回，中文文案不会乱码")
    void pagesAreServedAsUtf8() {
        ResponseEntity<String> response = fetch("/ui/gateways");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).containsIgnoringCase("charset=UTF-8");
        assertThat(response.getBody()).contains("网关列表").contains("创建网关");
    }

    @Test
    @DisplayName("脚本以 UTF-8 返回 —— 里面有中文提示文案，靠浏览器猜编码不可接受")
    void scriptIsServedAsUtf8() {
        ResponseEntity<String> response = fetch("/js/app.js");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).containsIgnoringCase("charset=UTF-8");
        assertThat(response.getBody()).contains("处理中").contains("mcp-servers");
    }

    @Test
    @DisplayName("需求 14：Bootstrap 随应用一起提供，内网无外网时界面照常可用")
    void bootstrapIsVendoredLocally() {
        ResponseEntity<String> response = fetch("/css/bootstrap.min.css");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("Bootstrap");

        // 页面里不能出现任何指向外网的资源引用
        String page = fetch("/ui/gateways").getBody();
        assertThat(page).doesNotContain("http://cdn").doesNotContain("https://cdn")
                .doesNotContain("unpkg.com").doesNotContain("jsdelivr");
    }

    @Test
    @DisplayName("根路径跟随重定向后落在网关列表页")
    void rootLandsOnTheGatewayList() {
        assertThat(fetch("/").getBody()).contains("网关列表");
    }

    @Test
    @DisplayName("需求 12.8：actuator 只放开健康检查，不暴露其他管理端点")
    void onlyHealthEndpointIsExposed() {
        assertThat(fetch("/actuator/health").getStatusCode().value()).isEqualTo(200);
        assertThat(fetch("/actuator/env").getStatusCode().value()).isEqualTo(404);
        assertThat(fetch("/actuator/beans").getStatusCode().value()).isEqualTo(404);
        assertThat(fetch("/actuator/configprops").getStatusCode().value()).isEqualTo(404);
    }
}
