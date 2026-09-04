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

        private final Admin admin = new Admin();

        /**
         * 管理端登录 Cookie 是否只在 HTTPS 上下发。
         *
         * 默认 false：默认部署是 localhost 明文 HTTP，写死 true 会让 Cookie 根本不下发、
         * 登录进不去。放在做了 TLS 的反向代理后面时必须显式设为 true。
         */
        private boolean cookieSecure;

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

        public Admin getAdmin() {
            return this.admin;
        }

        public boolean isCookieSecure() {
            return this.cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }
    }

    /**
     * 管理端的唯一账号（需求 3.2：不含权限系统，这里做的是身份校验而不是用户体系）。
     *
     * 与主密钥同样的处理：口令只能来自环境变量，配置文件里没有默认值，缺失时应用启动失败。
     * 代价是改口令要重启 —— 换来的是零数据库状态、无首启向导、无找回密码通道。
     */
    public static class Admin {

        private String username = "admin";

        /**
         * 明文口令，来自 {@code MCP_GATEWAY_ADMIN_PASSWORD}。
         *
         * 启动时由 {@code AdminAccount} 哈希成 BCrypt 摘要，之后明文不再被读取。
         * 注意这里不能给默认值：有默认值就等于把一把公开的钥匙发给了所有部署。
         */
        private String password;

        public String getUsername() {
            return this.username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return this.password;
        }

        public void setPassword(String password) {
            this.password = password;
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
