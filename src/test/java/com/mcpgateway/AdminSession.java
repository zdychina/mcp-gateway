package com.mcpgateway;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 让走真实 HTTP 的测试能像浏览器那样保持登录态。
 *
 * 管理 API 现在需要登录（需求 12.8），而 {@link TestRestTemplate} 既不存 Cookie 也不懂
 * CSRF 令牌。这个拦截器把两件事都补上，装上之后调用方一行都不用改 —— 这正是目的：
 * 验收测试关心的是业务流程，不该被会话管理的样板淹没。
 *
 * 顺带它也真的验证了一遍登录链路：拿令牌、登录、带着会话继续，与浏览器完全同一条路。
 */
public final class AdminSession implements ClientHttpRequestInterceptor {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final Map<String, String> cookies = new LinkedHashMap<>();

    private AdminSession() {
    }

    /**
     * 用测试凭证登录，并让此后所有经过这个模板的请求自动带上会话 Cookie 与 CSRF 令牌。
     *
     * 先 GET 一次 /api/auth/session 是必须的：CSRF 令牌 Cookie 要靠一次请求才会下发，
     * 而登录接口本身也在 CSRF 保护之内。浏览器里这一步由加载页面完成。
     */
    public static AdminSession signIn(TestRestTemplate rest) {
        AdminSession session = new AdminSession();
        // 同一个模板是测试类里的共享 Bean，重复安装会让 Cookie 串味。
        rest.getRestTemplate().getInterceptors().removeIf(AdminSession.class::isInstance);
        rest.getRestTemplate().getInterceptors().add(session);

        rest.getForEntity("/api/auth/session", String.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"username": "%s", "password": "%s"}
                """.formatted(TestAdminCredentials.USERNAME, TestAdminCredentials.PASSWORD);

        ResponseEntity<String> response = rest.exchange("/api/auth/login",
                org.springframework.http.HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("admin login -> %s : %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return session;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {

        if (!this.cookies.isEmpty()) {
            request.getHeaders().add(HttpHeaders.COOKIE, this.cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("; ")));
        }
        String csrfToken = this.cookies.get(CSRF_COOKIE);
        if (csrfToken != null) {
            request.getHeaders().add(CSRF_HEADER, csrfToken);
        }

        ClientHttpResponse response = execution.execute(request, body);
        capture(response.getHeaders().get(HttpHeaders.SET_COOKIE));
        return response;
    }

    /** 只取 {@code NAME=VALUE}，属性段一概忽略 —— 测试里不需要模拟过期和作用域。 */
    private void capture(List<String> setCookieHeaders) {
        if (setCookieHeaders == null) {
            return;
        }
        for (String header : setCookieHeaders) {
            String pair = header.split(";", 2)[0];
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = pair.substring(0, separator).trim();
            String value = pair.substring(separator + 1).trim();
            if (value.isEmpty()) {
                // 服务端在退出登录时用空值让 Cookie 过期。
                this.cookies.remove(name);
            }
            else {
                this.cookies.put(name, value);
            }
        }
    }
}
