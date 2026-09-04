package com.mcpgateway.api;

import com.mcpgateway.AbstractApiTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 管理 API 的授权边界：除白名单外，每一个 {@code /api} 端点未登录都必须进不去。
 *
 * 这条不变量守的不是今天的代码 —— 今天的代码是对的 —— 而是**以后新加的控制器**。
 * SecurityConfig 里那条 {@code .requestMatchers("/api/**").authenticated()} 让新端点默认受保护，
 * 但只要有人往白名单里多加一行、或者把新接口挂到 {@code /api} 以外的前缀上，
 * 保护就悄无声息地没了，而且不会有任何一个功能测试因此变红。
 *
 * 做法是从 Spring MVC 的处理器映射里把端点全枚举出来逐个真打一遍，而不是读配置对答案：
 * 只有实打实地发一个请求，才能证明它真的被拦住了。
 */
class ApiAuthorizationInvariantsTest extends AbstractApiTest {

    /**
     * 允许匿名访问的管理接口，一份完整清单。
     *
     * 往这里加东西必须是自觉的决定：登录接口未登录才用得上，会话查询接口是前端启动时
     * 判断"渲染登录页还是主界面"的依据，除此以外没有第三个接口有理由公开。
     */
    private static final Set<String> PUBLIC_API_PATHS = Set.of(
            AuthController.LOGIN_PATH,
            AuthController.SESSION_PATH);

    /** actuator 也注册了一个同类型的映射，必须指名要 MVC 那个。 */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("需求 12.8：除登录与会话查询外，所有 /api 端点未登录一律 401")
    void everyApiEndpointRequiresAuthentication() throws Exception {
        List<String> reachableWithoutLogin = new ArrayList<>();

        for (RequestMappingInfo mapping : this.handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : patternsOf(mapping)) {
                if (!pattern.startsWith("/api/") || PUBLIC_API_PATHS.contains(pattern)) {
                    continue;
                }
                for (HttpMethod method : methodsOf(mapping)) {
                    int status = this.anonymousMockMvc.perform(
                                    request(method, concretePathFor(pattern))
                                            .contentType("application/json")
                                            .content("{}")
                                            // 带上 CSRF 令牌，让请求真正走到授权那一步 ——
                                            // 不带的话 CsrfFilter 先返回 403，
                                            // 一个本该公开却漏配的端点会被这个 403 掩盖过去。
                                            .with(csrf()))
                            .andReturn().getResponse().getStatus();

                    if (status != 401) {
                        reachableWithoutLogin.add(method + " " + pattern + " -> " + status);
                    }
                }
            }
        }

        assertThat(reachableWithoutLogin).as("未登录就能走到的管理端点").isEmpty();
    }

    @Test
    @DisplayName("公开的管理接口只有登录和会话查询这两个")
    void publicEndpointsAreExactlyTheWhitelist() throws Exception {
        List<String> unexpectedlyPublic = new ArrayList<>();

        for (RequestMappingInfo mapping : this.handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : patternsOf(mapping)) {
                if (!pattern.startsWith("/api/") || PUBLIC_API_PATHS.contains(pattern)) {
                    continue;
                }
                // 只用 GET 探一下：能匿名读到 200 的一定是漏配。
                int status = this.anonymousMockMvc.perform(
                                request(HttpMethod.GET, concretePathFor(pattern)))
                        .andReturn().getResponse().getStatus();
                if (status == 200) {
                    unexpectedlyPublic.add(pattern);
                }
            }
        }

        assertThat(unexpectedlyPublic).as("意外公开的管理端点").isEmpty();
    }

    /** 把 {@code /api/gateways/{gatewayId}} 这种模板换成一个能真正发出去的具体路径。 */
    private static String concretePathFor(String pattern) {
        // 占位符换成一个不存在的 ID：真走到业务层会得到 404，而这里期待的是更早的 401。
        return pattern.replaceAll("\\{[^/{}]+}", "no-such-id");
    }

    private static Set<String> patternsOf(RequestMappingInfo mapping) {
        if (mapping.getPathPatternsCondition() != null) {
            return mapping.getPathPatternsCondition().getPatternValues();
        }
        return mapping.getPatternsCondition() == null ? Set.of() : mapping.getPatternsCondition().getPatterns();
    }

    /** 映射没写 method 时它对所有方法开放，探测就退回到最常见的这几个。 */
    private static Set<HttpMethod> methodsOf(RequestMappingInfo mapping) {
        Set<HttpMethod> methods = new LinkedHashSet<>();
        mapping.getMethodsCondition().getMethods()
                .forEach(method -> methods.add(HttpMethod.valueOf(method.name())));
        if (methods.isEmpty()) {
            methods.addAll(List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                    HttpMethod.PATCH, HttpMethod.DELETE));
        }
        return methods;
    }
}
