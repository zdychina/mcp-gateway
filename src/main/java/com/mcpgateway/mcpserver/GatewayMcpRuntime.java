package com.mcpgateway.mcpserver;

import com.mcpgateway.config.GatewayProperties;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.recording.ToolCallRecorder;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个网关对外的 MCP 服务上下文：独立的 transport + 独立的 SDK server。
 *
 * 刻意**不**往 SDK 里注册任何工具。工具目录完全由 {@link GatewayMcpHandler} 从数据库快照提供，
 * 因此启停工具、重新同步、改描述都不需要重建这个对象 —— 只有网关自身的 slug 或描述变了才需要。
 */
public final class GatewayMcpRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GatewayMcpRuntime.class);

    private final String slug;

    private final HttpServletStatelessServerTransport transport;

    private final McpStatelessSyncServer server;

    private GatewayMcpRuntime(Gateway gateway, GatewayProperties properties, GatewayToolRouter router,
            ToolCallRecorder recorder) {
        this.slug = gateway.slug();

        this.transport = HttpServletStatelessServerTransport.builder()
                // transport 自己只做 requestURI.endsWith(messageEndpoint) 的宽松校验，
                // 权威路由在 GatewayMcpDispatcherServlet 里做精确匹配。
                .messageEndpoint(mcpPath(gateway.slug()))
                // 需求 12.9：请求体大小上限
                .maxRequestSize(properties.getServer().getMaxRequestSize())
                // 需求 12.6：Origin 校验
                .securityValidator(new OriginValidator(properties.getSecurity().getAllowedOrigins()))
                // 需求 FR-06：从 HTTP 层取出 trace_id 放进 McpTransportContext，
                // 处理器的第一个入参就能读到，不需要自建 ThreadLocal。
                .contextExtractor(TraceIds::fromRequest)
                .build();

        var capturing = new CapturingStatelessTransport(this.transport,
                delegate -> new GatewayMcpHandler(delegate, gateway.id(), gateway.slug(), router, recorder));

        this.server = McpServer.sync(capturing)
                .serverInfo("mcp-gateway-" + gateway.slug(), "1.0.0")
                // 需求 6.1.4：网关描述映射为 initialize 结果里的 instructions
                .instructions(gateway.description())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .requestTimeout(properties.getDownstream().getCallTimeout())
                .build();

        log.info("MCP endpoint ready for gateway [{}] at {}", gateway.slug(), mcpPath(gateway.slug()));
    }

    public static GatewayMcpRuntime create(Gateway gateway, GatewayProperties properties,
            GatewayToolRouter router, ToolCallRecorder recorder) {
        return new GatewayMcpRuntime(gateway, properties, router, recorder);
    }

    /** 需求 FR-04：每个网关的独立 MCP 地址。 */
    public static String mcpPath(String slug) {
        return "/mcp/" + slug;
    }

    public HttpServletStatelessServerTransport transport() {
        return this.transport;
    }

    @Override
    public void close() {
        try {
            this.server.closeGracefully();
        }
        catch (RuntimeException ex) {
            log.warn("failed to close MCP server for gateway [{}]: {}", this.slug, ex.toString());
        }
    }
}
