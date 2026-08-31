package com.mcpgateway.service;

import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * 子 MCP URL 校验（需求 6.2.5 / 12.7）。
 *
 * 只允许 http/https，禁止 URL user-info。后者是防 SSRF 凭证走私的常规措施：
 * {@code https://attacker@internal-host/} 这种写法会让人误读目标主机。
 *
 * 需求 12.7 还要求"重定向后的目标仍需重新校验协议"—— 那一步在实际发起 HTTP 请求时做，
 * 属于 W4 下游客户端的职责，不在这里。
 */
public final class DownstreamUrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** 与 downstream_mcp.url 的列宽一致。 */
    public static final int MAX_URL_LENGTH = 2048;

    private DownstreamUrlValidator() {
    }

    /**
     * @param url  待校验的 URL
     * @param what 出错信息里用于定位的名字，例如子 MCP 名称
     */
    public static void validate(String url, String what) {
        if (url == null || url.isBlank()) {
            throw fail(what, "url is required");
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw fail(what, "url exceeds " + MAX_URL_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        }
        catch (URISyntaxException ex) {
            throw fail(what, "url is not a valid URI");
        }

        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw fail(what, "url must be absolute");
        }
        if (!ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw fail(what, "url scheme must be http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw fail(what, "url must contain a host");
        }
        if (uri.getRawUserInfo() != null) {
            // 不要把 user-info 的内容回显出去，它可能就是一组凭证。
            throw fail(what, "url must not contain user-info");
        }
    }

    private static GatewayException fail(String what, String reason) {
        return GatewayException.of(ErrorCode.INVALID_MCP_CONFIG, what + ": " + reason);
    }
}
