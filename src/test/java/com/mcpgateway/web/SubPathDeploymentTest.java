package com.mcpgateway.web;

import com.mcpgateway.TestAdminCredentials;
import com.mcpgateway.TestMasterKey;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.mcpserver.GatewayMcpRuntime;
import com.mcpgateway.repository.GatewayRepository;
import com.mcpgateway.security.AccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 * 挂在子路径下部署（server.servlet.context-path）时，四类地址整体搬家且一个都不落下。
 *
 * 这个能力的全部实现就是 application.yml 里的一行配置 —— 正因为如此才需要这道测试：
 * 一行配置没有任何代码可读，后来的人只会从这里知道「原来它支持子路径，而且是这样验的」。
 *
 * 断言分两半，缺一半都不够：
 *
 * <ul>
 *   <li><b>前缀下的地址要通。</b> 尤其是 /mcp/{slug} —— 它是自己注册的 servlet，
 *       slug 从 {@code request.getPathInfo()} 里切出来。容器是否在算 pathInfo 之前
 *       剥掉了 context path，从代码上看不出来；剥错了每个网关都会变成 404，
 *       而那是只有在真的挂上前缀之后才会暴露的失败。</li>
 *   <li><b>不带前缀的同一地址要 404。</b> 这才是子路径部署的意义：同一个域名上还有别的
 *       应用占着 /api、/ui，本应用不能漏到前缀外面去。</li>
 * </ul>
 *
 * 前端产物那一半不在这里 —— 资源地址的前缀是构建期由 VITE_BASE_PATH 打进 index.html 的，
 * 测试跑的是默认（空前缀）那份产物。见 frontend/test/base-path.spec.ts。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = com.mcpgateway.McpGatewayApplication.class,
        properties = "server.servlet.context-path=" + SubPathDeploymentTest.PREFIX)
@ActiveProfiles("test")
class SubPathDeploymentTest {

    static final String PREFIX = "/kbmcp";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mcp-gateway.security.master-key", () -> TestMasterKey.BASE64);
        TestAdminCredentials.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private GatewayRepository gateways;

    @Autowired
    private AccessTokenService accessTokens;

    private Gateway gateway;

    @BeforeEach
    void seed() {
        this.gateway = new Gateway(UUID.randomUUID().toString(), "子路径网关",
                "sub-" + Long.toString(System.nanoTime(), 36), null,
                this.accessTokens.generate().hash(), Instant.now(), Instant.now());
        this.gateways.insert(this.gateway);
    }

    /*
     * 一律用绝对地址：TestRestTemplate 的相对地址会自动补上 context-path，
     * 那样就永远测不到"不带前缀时是什么反应"—— 而那正是这里要断言的另一半。
     */
    private ResponseEntity<String> get(String path) {
        return this.restTemplate.getForEntity("http://localhost:" + this.port + path, String.class);
    }

    @Test
    @DisplayName("SPA 入口、静态资源、管理 API 都在前缀下")
    void adminSurfacesMoveUnderThePrefix() {
        ResponseEntity<String> spa = get(PREFIX + "/ui/gateways");
        assertThat(spa.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(spa.getBody()).contains("id=\"app\"");

        // 深层路由同样要落到入口文档上 —— 直接访问和刷新靠的就是它。
        assertThat(get(PREFIX + "/ui/gateways/" + this.gateway.id() + "/calls").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(get(PREFIX + "/app/index.html").getStatusCode()).isEqualTo(HttpStatus.OK);

        // 管理 API 还是那扇门：前缀不改变任何授权结论。
        assertThat(get(PREFIX + "/api/gateways").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("根地址的重定向带着前缀，不会把人甩到前缀外面")
    void rootRedirectKeepsThePrefix() throws IOException, InterruptedException {
        /*
         * 这一条必须自己发请求：TestRestTemplate 会跟着 302 走，拿回来的是跳转之后那个
         * 200，Location 头早就被吞掉了 —— 而这里要断言的恰恰就是 Location 里有没有前缀。
         */
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + PREFIX + "/")).build(),
                    HttpResponse.BodyHandlers.discarding());

            assertThat(response.statusCode()).isEqualTo(HttpStatus.FOUND.value());
            // 容器可能回相对地址也可能回绝对地址，两种都对；要紧的是前缀没丢。
            assertThat(response.headers().firstValue("Location")).get(as(STRING))
                    .endsWith(PREFIX + "/ui/gateways");
        }
    }

    @Test
    @DisplayName("Agent 端点在前缀下仍能按 slug 分发，且照样要令牌")
    void mcpEndpointResolvesSlugUnderThePrefix() {
        // 401 而不是 404：说明 slug 切出来了、网关也找到了，挡住请求的是令牌校验。
        ResponseEntity<String> unauthorized = postToMcp(this.gateway.slug());
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthorized.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");

        // 不存在的 slug 仍是 404，前缀没有把这层判断变宽。
        assertThat(postToMcp("no-such-gateway").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Cookie 的作用域收在前缀里，不与同域的其他应用打架")
    void cookiesAreScopedToThePrefix() {
        List<String> cookies = get(PREFIX + "/api/auth/session").getHeaders().get(HttpHeaders.SET_COOKIE);

        assertThat(cookies).isNotNull().isNotEmpty()
                .allSatisfy(cookie -> assertThat(cookie).contains("Path=" + PREFIX));
    }

    @Test
    @DisplayName("前缀之外什么都没有 —— 同域上的其他应用不会被抢走")
    void nothingIsServedOutsideThePrefix() {
        for (String path : new String[] { "/ui/gateways", "/api/gateways", "/app/index.html",
                GatewayMcpRuntime.mcpPath(this.gateway.slug()), "/" }) {
            assertThat(get(path).getStatusCode())
                    .as("前缀外的 %s", path)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private ResponseEntity<String> postToMcp(String slug) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        return this.restTemplate.exchange(
                "http://localhost:" + this.port + PREFIX + GatewayMcpRuntime.mcpPath(slug),
                HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", headers),
                String.class);
    }
}
