package com.mcpgateway.downstream;

import com.mcpgateway.config.GatewayProperties;
import com.mcpgateway.config.GatewayVersion;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.security.DownstreamHeaderCodec;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * 按子 MCP 配置构造官方 SDK 的 Streamable HTTP 客户端。
 *
 * 这里是明文 headers 唯一被使用的地方：从密文解出来后直接塞进请求构造器，
 * 不落到任何字段、日志或返回值上（需求 12.2 / 12.5）。
 */
@Component
public class DownstreamClientFactory {

    private static final Logger log = LoggerFactory.getLogger(DownstreamClientFactory.class);

    private final DownstreamHeaderCodec headerCodec;

    private final GatewayProperties properties;

    private final GatewayVersion version;

    public DownstreamClientFactory(DownstreamHeaderCodec headerCodec, GatewayProperties properties,
            GatewayVersion version) {
        this.headerCodec = headerCodec;
        this.properties = properties;
        this.version = version;
    }

    /**
     * 关掉校验时在启动日志里留一条 WARN。
     *
     * 只在启动时打一次，不在每次连接时打 —— 每次同步都打一遍，几天之后就没人看得见它了，
     * 那等于没有警告。持续可见的那一份提示在管理界面上（网关详情页的子 MCP 配置卡片）。
     */
    @PostConstruct
    void warnIfTlsVerificationDisabled() {
        if (this.properties.getDownstream().isInsecureSkipTlsVerify()) {
            // 日志正文统一用英文，和这个项目其他所有 log.* 一致 ——
            // 中文在部分服务器控制台的编码下会变成乱码，那时它就等于没打。
            log.warn("downstream TLS verification is DISABLED "
                    + "(MCP_GATEWAY_DOWNSTREAM_INSECURE_SKIP_TLS_VERIFY=true): "
                    + "downstream MCP identity is not verified and credentials are sent over "
                    + "connections whose peer cannot be authenticated; "
                    + "use only for internal self-signed certificates whose CA cannot be obtained");
        }
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
                .customizeClient(builder -> {
                    builder.followRedirects(HttpClient.Redirect.NEVER);
                    if (config.isInsecureSkipTlsVerify()) {
                        applyInsecureTls(builder);
                    }
                })
                .httpRequestCustomizer((requestBuilder, method, requestUri, body, context) ->
                        headers.forEach(requestBuilder::header))
                .build();

        return McpClient.sync(transport)
                .requestTimeout(config.getCallTimeout())
                .initializationTimeout(config.getCallTimeout())
                // SDK 默认上报的 clientInfo 版本号是过期的，显式声明便于子 MCP 侧识别来访者。
                .clientInfo(new McpSchema.Implementation("mcp-gateway", "MCP Aggregation Gateway",
                        this.version.value()))
                .build();
    }

    /**
     * 关掉证书链校验和主机名校验。
     *
     * 两件事必须一起做，这是这类实现最常见的坑：换掉 TrustManager 只是让证书链不再被验证，
     * JDK HttpClient 的主机名校验走的是另一条路 —— SSLParameters 的
     * endpointIdentificationAlgorithm，默认是 "HTTPS"。只做前一半的话，
     * 自签证书能过了，但只要证书里的名字和你填的 host 对不上，照样握手失败，
     * 而且报的是另一个错，很容易被误判成"开关没生效"。
     */
    private static void applyInsecureTls(HttpClient.Builder builder) {
        builder.sslContext(trustAllContext());

        SSLParameters parameters = new SSLParameters();
        // null 而不是空串：空串在部分 JDK 上仍会走主机名校验。
        parameters.setEndpointIdentificationAlgorithm(null);
        builder.sslParameters(parameters);
    }

    /**
     * 一个什么都信的 SSLContext。
     *
     * 每次建客户端都新建一个：这条路径一天也走不了几次（同步和调用都是低频的），
     * 缓存一个进程级的可变 SSLContext 换来的那点开销不值得多一份状态。
     */
    private static SSLContext trustAllContext() {
        TrustManager[] trustAll = { new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // 刻意为空：这个 TrustManager 的全部意义就是不做校验。
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // 同上。
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        } };

        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            return context;
        }
        catch (GeneralSecurityException ex) {
            // TLS 一定存在；真出这个异常说明 JVM 装配坏了，没有合理的降级。
            throw new IllegalStateException("failed to build the insecure SSL context", ex);
        }
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
