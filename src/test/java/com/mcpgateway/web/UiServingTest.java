package com.mcpgateway.web;

import com.mcpgateway.TestAdminCredentials;
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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在真实 Tomcat 下验证页面与静态资源的响应头。
 *
 * 为什么不放在 MockMvc 测试里：MockHttpServletResponse 对 charset 的处理和真实容器不一致，
 * 在那边断言 charset 会得出与线上相反的结论。界面文案里全是中文，这条必须按容器行为验。
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
        TestAdminCredentials.register(registry);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private ResponseEntity<String> fetch(String path) {
        return this.restTemplate.getForEntity(path, String.class);
    }

    @Test
    @DisplayName("页面以 UTF-8 返回，中文文案不会乱码")
    void pagesAreServedAsUtf8() {
        // 页面是 Vue 应用的入口文档（转发自 /app/index.html），壳里仍有中文（<title>）
        ResponseEntity<String> response = fetch("/ui/gateways");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).containsIgnoringCase("charset=UTF-8");
        assertThat(response.getBody()).contains("MCP 聚合网关").contains("id=\"app\"");
    }

    @Test
    @DisplayName("脚本以 UTF-8 返回 —— 界面文案全在包里，靠浏览器猜编码不可接受")
    void scriptIsServedAsUtf8() {
        String script = scriptPath();
        ResponseEntity<String> response = fetch(script);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).containsIgnoringCase("charset=UTF-8");
        // 包里确实有中文文案，说明编码断言不是在验一个纯 ASCII 文件
        assertThat(response.getBody()).contains("处理中").contains("mcp-servers");
    }

    @Test
    @DisplayName("需求 14：前端资源随应用一起提供，内网无外网时界面照常可用")
    void frontendAssetsAreVendoredLocally() {
        String shell = fetch("/ui/gateways").getBody();

        /*
         * 前端做成同源而不是独立部署：独立部署必须放开 CORS，而"没有任何 CORS 响应头"
         * 是跨站保护的一层，会话 Cookie 的 SameSite=Strict 也依赖同源。
         * 这里守住的是它的前提：入口文档引用的资源必须全是同源相对路径。
         */
        assertThat(shell).contains("/app/assets/");
        assertThat(shell).doesNotContain("//localhost:").doesNotContain("//127.0.0.1:");

        List<String> assets = assetPathsIn(shell);
        for (String asset : assets) {
            assertThat(fetch(asset).getStatusCode().value())
                    .as("静态资源 %s", asset).isEqualTo(200);
        }

        // 页面和脚本里都不能出现指向外网的资源引用
        String script = fetch(scriptPath()).getBody();
        for (String page : new String[] { shell, script }) {
            assertThat(page).doesNotContain("http://cdn").doesNotContain("https://cdn")
                    .doesNotContain("unpkg.com").doesNotContain("jsdelivr")
                    .doesNotContain("fonts.googleapis.com");
        }
    }

    @Test
    @DisplayName("根路径跟随重定向后落在网关列表页")
    void rootLandsOnTheGatewayList() {
        // 列表页是 Vue 应用的空壳，内容由前端调 /api/gateways 填充
        assertThat(fetch("/").getBody()).contains("id=\"app\"");
    }

    @Test
    @DisplayName("需求 12.8：actuator 只放开健康检查，不暴露其他管理端点")
    void onlyHealthEndpointIsExposed() {
        assertThat(fetch("/actuator/health").getStatusCode().value()).isEqualTo(200);

        /*
         * 加登录之前这两个是 404（端点根本没暴露）；现在先被 SecurityConfig 末尾的
         * anyRequest().denyAll() 拦下，拿到的是 401。两者都不泄漏任何东西，
         * 区别只是拦截点从路由层提前到了过滤器链 —— 断言跟着改，是因为它要守的是
         * "除 health 外一律进不去"，而不是某一个具体状态码。
         */
        assertThat(fetch("/actuator/env").getStatusCode().value()).isEqualTo(401);
        assertThat(fetch("/actuator/beans").getStatusCode().value()).isEqualTo(401);
        assertThat(fetch("/actuator/configprops").getStatusCode().value()).isEqualTo(401);
    }

    /** 入口文档里那个带 hash 的脚本路径。 */
    private String scriptPath() {
        return assetPathsIn(fetch("/ui/gateways").getBody()).stream()
                .filter(path -> path.endsWith(".js"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("入口文档里没有找到脚本引用"));
    }

    /** 从入口文档里抠出 /app/assets/... 的引用。 */
    private static List<String> assetPathsIn(String html) {
        Matcher matcher = Pattern.compile("/app/assets/[A-Za-z0-9._-]+").matcher(html);
        List<String> paths = new ArrayList<>();
        while (matcher.find()) {
            paths.add(matcher.group());
        }
        assertThat(paths).as("入口文档里的资源引用").isNotEmpty();
        return paths;
    }
}
