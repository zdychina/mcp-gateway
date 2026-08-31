package com.mcpgateway.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * 网关部署配置。
 */
@Validated
@ConfigurationProperties(prefix = "mcp-gateway")
public class GatewayProperties {

    /**
     * 需求 FR-05.1：Agent 接入 JSON 里的 baseUrl 只能来自这里，
     * 绝不允许根据 Host / X-Forwarded-Proto 等不可信请求头拼接。
     */
    @NotBlank
    private String baseUrl;

    private final Security security = new Security();

    private final Downstream downstream = new Downstream();

    private final Server server = new Server();

    public String getBaseUrl() {
        return this.baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Security getSecurity() {
        return this.security;
    }

    public Downstream getDownstream() {
        return this.downstream;
    }

    public Server getServer() {
        return this.server;
    }

    public static class Security {

        /**
         * Base64 编码的 32 字节 AES 主密钥，用于加密子 MCP headers。
         * 需求 12.1 / 12.2：只能来自环境变量，没有默认值，缺失时应用启动失败。
         */
        private String masterKey;

        /** 需求 12.6：Streamable HTTP 端点允许的 Origin。为空表示只接受不带 Origin 的请求。 */
        private List<String> allowedOrigins = List.of();

        public String getMasterKey() {
            return this.masterKey;
        }

        public void setMasterKey(String masterKey) {
            this.masterKey = masterKey;
        }

        public List<String> getAllowedOrigins() {
            return this.allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }

    public static class Downstream {

        /** 需求 6.6.5：默认下游调用超时 30 秒。 */
        private Duration callTimeout = Duration.ofSeconds(30);

        private Duration connectTimeout = Duration.ofSeconds(10);

        /** 需求 12.9：下游响应体大小上限。 */
        @Min(1024)
        private int maxResponseSize = 1024 * 1024;

        public Duration getCallTimeout() {
            return this.callTimeout;
        }

        public void setCallTimeout(Duration callTimeout) {
            this.callTimeout = callTimeout;
        }

        public Duration getConnectTimeout() {
            return this.connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public int getMaxResponseSize() {
            return this.maxResponseSize;
        }

        public void setMaxResponseSize(int maxResponseSize) {
            this.maxResponseSize = maxResponseSize;
        }
    }

    public static class Server {

        /** 需求 12.9：Agent 请求体大小上限。 */
        @Min(1024)
        private int maxRequestSize = 1024 * 1024;

        /** 需求 6.2.1：每个网关最多 3 个子 MCP。 */
        @Min(1)
        private int maxDownstreamPerGateway = 3;

        public int getMaxRequestSize() {
            return this.maxRequestSize;
        }

        public void setMaxRequestSize(int maxRequestSize) {
            this.maxRequestSize = maxRequestSize;
        }

        public int getMaxDownstreamPerGateway() {
            return this.maxDownstreamPerGateway;
        }

        public void setMaxDownstreamPerGateway(int maxDownstreamPerGateway) {
            this.maxDownstreamPerGateway = maxDownstreamPerGateway;
        }
    }
}
