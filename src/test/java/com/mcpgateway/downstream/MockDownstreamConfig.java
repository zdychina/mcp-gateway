package com.mcpgateway.downstream;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/** 在测试进程里挂两个模拟子 MCP，走真实 HTTP。 */
@TestConfiguration
public class MockDownstreamConfig {

    public static final String KB_A_PATH = "/mock-downstream/kb-a";

    public static final String KB_B_PATH = "/mock-downstream/kb-b";

    @Bean
    public MockDownstreamMcpServer mockKbA() {
        return new MockDownstreamMcpServer("kbA", KB_A_PATH, List.of("search", "ping"));
    }

    @Bean
    public MockDownstreamMcpServer mockKbB() {
        return new MockDownstreamMcpServer("kbB", KB_B_PATH, List.of("search", "lookup"));
    }

    @Bean
    public ServletRegistrationBean<?> mockKbAServlet(MockDownstreamMcpServer mockKbA) {
        var registration = new ServletRegistrationBean<>(mockKbA.transport(), KB_A_PATH);
        registration.setName("mockKbA");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<?> mockKbBServlet(MockDownstreamMcpServer mockKbB) {
        var registration = new ServletRegistrationBean<>(mockKbB.transport(), KB_B_PATH);
        registration.setName("mockKbB");
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * 让模拟子 MCP 不被管理端那条过滤器链拦住。
     *
     * 这两个 servlet 挂在被测应用自己的端口上，因此同样要经过 SecurityConfig ——
     * 而那条链末尾是 anyRequest().denyAll()，没有这条开洞的话所有同步都会拿到 401。
     * 真实部署里子 MCP 在别的进程里，不存在这个问题，所以开洞只在测试配置里。
     *
     * @Order(0) 要排在 SecurityConfig 的两条链之前。
     */
    @Bean
    @Order(0)
    public SecurityFilterChain mockDownstreamSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/mock-downstream/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
