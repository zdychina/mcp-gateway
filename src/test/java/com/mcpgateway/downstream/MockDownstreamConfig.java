package com.mcpgateway.downstream;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

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
}
