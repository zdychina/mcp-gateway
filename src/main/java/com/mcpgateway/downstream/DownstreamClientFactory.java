package com.mcpgateway.downstream;

import com.mcpgateway.config.GatewayProperties;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.security.DownstreamHeaderCodec;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;

/**
 * 按子 MCP 配置构造官方 SDK 的 Streamable HTTP 客户端。
 *
 * 这里是明文 headers 唯一被使用的地方：从密文解出来后直接塞进请求构造器，
 * 不落到任何字段、日志或返回值上（需求 12.2 / 12.5）。
 */
@Component
public class DownstreamClientFactory {

    private final DownstreamHeaderCodec headerCodec;

    private final GatewayProperties properties;

    public DownstreamClientFactory(DownstreamHeaderCodec headerCodec, GatewayProperties properties) {
        this.headerCodec = headerCodec;
        this.properties = properties;
    }

    /**
     * 建立并完成 MCP 初始化握手。
     *
     * 返回的客户端由调用方负责关闭。
     */
    public McpSyncClient connect(DownstreamMcp downstream) {
        McpSyncClient client = build(downstream);
        try {
            client.initialize();
            return client;
        }
        catch (RuntimeException ex) {
            closeQuietly(client);
            throw DownstreamErrorMapper.map(ex, ErrorCode.DOWNSTREAM_INIT_FAILED, downstream.name());
        }
    }

    private McpSyncClient build(DownstreamMcp downstream) {
        URI uri = URI.create(downstream.url());
        String baseUri = uri.getScheme() + "://" + uri.getAuthority();
        String endpoint = endpointOf(uri);

        Map<String, String> headers = this.headerCodec.decrypt(downstream.encryptedHeadersJson());
        GatewayProperties.Downstream config = this.properties.getDownstream();

        var transport = HttpClientStreamableHttpTransport.builder(baseUri)
                .endpoint(endpoint)
                .connectTimeout(config.getConnectTimeout())
                // 需求 12.9：下游响应体大小上限。
                .maxResponseSize(config.getMaxResponseSize())
                // 需求 12.7：重定向后的目标必须重新校验协议。MVP 采取更保守的做法 ——
                // 干脆不跟随重定向，免得一个 302 把请求连同凭证带去未经校验的主机。
                .customizeClient(builder -> builder.followRedirects(HttpClient.Redirect.NEVER))
                .httpRequestCustomizer((requestBuilder, method, requestUri, body, context) ->
                        headers.forEach(requestBuilder::header))
                .build();

        return McpClient.sync(transport)
                .requestTimeout(config.getCallTimeout())
                .initializationTimeout(config.getCallTimeout())
                // SDK 默认上报的 clientInfo 版本号是过期的，显式声明便于子 MCP 侧识别来访者。
                .clientInfo(new McpSchema.Implementation("mcp-gateway", "MCP Aggregation Gateway", "1.0.0"))
                .build();
    }

    private static String endpointOf(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    public static void closeQuietly(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
        }
        catch (RuntimeException ignored) {
            // 关闭失败不应掩盖真正的业务错误。
        }
    }

    /** 让调用方无需重复 try/finally。 */
    public <T> T withClient(DownstreamMcp downstream, java.util.function.Function<McpSyncClient, T> action) {
        McpSyncClient client = connect(downstream);
        try {
            return action.apply(client);
        }
        finally {
            closeQuietly(client);
        }
    }

    static GatewayException unsupported(String name) {
        return GatewayException.of(ErrorCode.UNSUPPORTED_TRANSPORT,
                name + ": only " + DownstreamMcp.TYPE_STREAMABLE_HTTP + " is supported");
    }
}
