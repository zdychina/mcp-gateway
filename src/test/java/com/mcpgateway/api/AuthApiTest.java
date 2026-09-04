package com.mcpgateway.api;

import com.mcpgateway.AbstractApiTest;
import com.mcpgateway.TestAdminCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端登录（需求 12.8）。
 *
 * 全程用 {@code anonymousMockMvc} —— 这个类要验的恰恰是认证本身，用基类那个已经带好身份的
 * 客户端会把要测的东西整个绕过去。
 *
 * 限速是按来源 IP 计数的进程内状态，且 {@code LoginThrottle} 是单例：几个用例共用
 * 默认的 127.0.0.1 会互相把对方的计数推过阈值，用例之间的成败就和执行顺序有关了。
 * 所以每个会制造登录失败的用例都给自己一个来源地址 —— 顺带也说明了限速的粒度。
 */
class AuthApiTest extends AbstractApiTest {

    private static String loginBody(String username, String password) {
        return """
                {"username": "%s", "password": "%s"}
                """.formatted(username, password);
    }

    private static String correctCredentials() {
        return loginBody(TestAdminCredentials.USERNAME, TestAdminCredentials.PASSWORD);
    }

    /** 给用例一个独立的来源地址，免得共用一份限速计数。 */
    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder, String remoteAddress) {
        return builder.with(request -> {
            request.setRemoteAddr(remoteAddress);
            return request;
        });
    }

    // ------------------------------------------------------------ 登录成功

    @Test
    @DisplayName("凭证正确时登录成功，并且此后带着会话就能访问管理 API")
    void signsInAndKeepsTheSession() throws Exception {
        MvcResult login = this.anonymousMockMvc.perform(post(AuthController.LOGIN_PATH)
                        .contentType("application/json")
                        .content(correctCredentials())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.username").value(TestAdminCredentials.USERNAME))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).as("登录必须建立会话").isNotNull();

        this.anonymousMockMvc.perform(get("/api/gateways").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("需求 12.8：登录后换掉会话 ID —— 会话固定攻击防护")
    void changesSessionIdOnLogin() throws Exception {
        // 先拿一个未登录的会话，模拟攻击者预先塞给受害者的那一枚。
        MockHttpSession preLogin = new MockHttpSession();
        String beforeId = preLogin.getId();

        MvcResult login = this.anonymousMockMvc.perform(post(AuthController.LOGIN_PATH)
                        .session(preLogin)
                        .contentType("application/json")
                        .content(correctCredentials())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        String afterId = login.getRequest().getSession(false).getId();
        assertThat(afterId)
                .as("登录前后的会话 ID 必须不同，否则登录前塞进来的会话 ID 会直接升级成已登录身份")
                .isNotEqualTo(beforeId);
    }

    // ------------------------------------------------------------ 登录失败

    @Test
    @DisplayName("口令不对与用户名不存在给完全相同的响应，不泄漏哪一半错了")
    void wrongCredentialsAreIndistinguishable() throws Exception {
        String wrongPassword = this.anonymousMockMvc.perform(from(post(AuthController.LOGIN_PATH), "10.9.0.1")
                        .contentType("application/json")
                        .content(loginBody(TestAdminCredentials.USERNAME, "definitely-not-the-password"))
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andReturn().getResponse().getContentAsString();

        String unknownUser = this.anonymousMockMvc.perform(from(post(AuthController.LOGIN_PATH), "10.9.0.2")
                        .contentType("application/json")
                        .content(loginBody("no-such-operator", TestAdminCredentials.PASSWORD))
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownUser).isEqualTo(wrongPassword);
        assertThat(wrongPassword).doesNotContain(TestAdminCredentials.USERNAME);
    }

    @Test
    @DisplayName("失败响应里不回显收到的口令")
    void failureNeverEchoesTheSubmittedPassword() throws Exception {
        String secret = "hunter2-hunter2-hunter2";

        String body = this.anonymousMockMvc.perform(from(post(AuthController.LOGIN_PATH), "10.9.0.3")
                        .contentType("application/json")
                        .content(loginBody(TestAdminCredentials.USERNAME, secret))
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(secret);
    }

    @Test
    @DisplayName("连续失败到阈值后该来源被锁定，正确口令也进不去")
    void throttlesRepeatedFailures() throws Exception {
        String attacker = "10.9.1.7";

        for (int attempt = 0; attempt < 5; attempt++) {
            this.anonymousMockMvc.perform(from(post(AuthController.LOGIN_PATH), attacker)
                            .contentType("application/json")
                            .content(loginBody(TestAdminCredentials.USERNAME, "wrong-" + attempt))
                            .with(csrf()))
                    .andExpect(status().isUnauthorized());
        }

        // 关键的一条：锁定期间即使口令是对的也必须拒绝，否则限速只是拖慢而不是拦住。
        this.anonymousMockMvc.perform(from(post(AuthController.LOGIN_PATH), attacker)
                        .contentType("application/json")
                        .content(correctCredentials())
                        .with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_ATTEMPTS"));

        // 限速按来源计数：别的地址不受牵连，否则攻击者能把管理员一起锁在门外。
        this.anonymousMockMvc.perform(from(post(AuthController.LOGIN_PATH), "10.9.1.8")
                        .contentType("application/json")
                        .content(correctCredentials())
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------- 会话与退出

    @Test
    @DisplayName("未登录时会话接口只说“没登录”，不透露配置好的用户名")
    void sessionEndpointRevealsNothingWhenAnonymous() throws Exception {
        String body = this.anonymousMockMvc.perform(get(AuthController.SESSION_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(TestAdminCredentials.USERNAME);
    }

    @Test
    @DisplayName("退出登录让服务端会话失效 —— 旧会话不能再用")
    void logoutInvalidatesTheSessionOnTheServer() throws Exception {
        MvcResult login = this.anonymousMockMvc.perform(post(AuthController.LOGIN_PATH)
                        .contentType("application/json")
                        .content(correctCredentials())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        this.anonymousMockMvc.perform(post(AuthController.LOGOUT_PATH).session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(false));

        assertThat(session.isInvalid()).as("会话必须在服务端销毁，只清 Cookie 不算").isTrue();

        this.anonymousMockMvc.perform(get("/api/gateways").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("退出登录本身也要求已登录，会话没了就落到统一的 401")
    void logoutRequiresASession() throws Exception {
        this.anonymousMockMvc.perform(post(AuthController.LOGOUT_PATH).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ------------------------------------------------------ 出口与请求形态

    @Test
    @DisplayName("过滤器链里的 401 也走统一信封 —— 前端只有一条解析路径")
    void unauthenticatedApiCallsGetTheStandardEnvelope() throws Exception {
        this.anonymousMockMvc.perform(get("/api/gateways"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                // 不发 WWW-Authenticate：那会让浏览器弹出原生的 Basic 认证框。
                .andExpect(result -> assertThat(result.getResponse().getHeader("WWW-Authenticate")).isNull());
    }

    @Test
    @DisplayName("缺 CSRF 令牌的写请求被拒，且用 FORBIDDEN 而不是 UNAUTHORIZED")
    void writesWithoutCsrfTokenAreRejected() throws Exception {
        this.anonymousMockMvc.perform(post(AuthController.LOGIN_PATH)
                        .contentType("application/json")
                        .content(correctCredentials()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("登录接口只收 JSON —— 表单编码是 SECURITY.md 里那道跨站保护要挡的东西")
    void loginRejectsFormEncoding() throws Exception {
        this.anonymousMockMvc.perform(from(post(AuthController.LOGIN_PATH), "10.9.0.4")
                        .contentType("application/x-www-form-urlencoded")
                        .content("username=" + TestAdminCredentials.USERNAME
                                + "&password=" + TestAdminCredentials.PASSWORD)
                        .with(csrf()))
                .andExpect(status().isUnsupportedMediaType());
    }
}
