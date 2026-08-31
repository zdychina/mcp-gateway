package com.mcpgateway.mcpserver;

import com.mcpgateway.security.AccessTokenService;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 所有网关共用一个 /mcp/* 入口，内部按 slug 分发（需求 FR-04）。 */
@Configuration
public class McpServerConfiguration {

    public static final String MCP_PATH_PATTERN = "/mcp/*";

    @Bean
    public ServletRegistrationBean<GatewayMcpDispatcherServlet> mcpDispatcherServlet(
            GatewayMcpRegistry registry, AccessTokenService accessTokens) {
        var registration = new ServletRegistrationBean<>(
                new GatewayMcpDispatcherServlet(registry, accessTokens), MCP_PATH_PATTERN);
        registration.setName("mcpGatewayDispatcher");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
