package com.mcpgateway.mcpserver;

import com.mcpgateway.security.AccessTokenService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * 挂在 /mcp/* 上的入口（需求 FR-04）。
 *
 * 做三件事，然后把请求整体交给对应网关的 SDK transport：
 * <ol>
 *   <li>按 slug 精确路由。SDK transport 内部只做 {@code requestURI.endsWith(messageEndpoint)}
 *       的宽松后缀比较，不能当作路由依据，权威匹配必须在这里做。</li>
 *   <li>校验网关访问令牌（需求 4.3）。</li>
 *   <li>把请求委派给该网关的 transport —— 它本身就是一个 HttpServlet。</li>
 * </ol>
 *
 * Origin 校验交给 transport 自己的 securityValidator，见 {@link OriginValidator}。
 */
public class GatewayMcpDispatcherServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(GatewayMcpDispatcherServlet.class);

    private final transient GatewayMcpRegistry registry;

    private final transient AccessTokenService accessTokens;

    public GatewayMcpDispatcherServlet(GatewayMcpRegistry registry, AccessTokenService accessTokens) {
        this.registry = registry;
        this.accessTokens = accessTokens;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String slug = extractSlug(request.getPathInfo());
        if (slug == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "gateway not found");
            return;
        }

        Optional<GatewayMcpRegistry.Resolved> resolved = this.registry.resolve(slug);
        if (resolved.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "gateway not found");
            return;
        }

        String presented = this.accessTokens.extractBearerToken(request.getHeader("Authorization"));
        if (!this.accessTokens.matches(presented, resolved.get().gateway().accessTokenHash())) {
            // 不区分"没带令牌"和"令牌不对"，也不回显收到的值。
            log.warn("rejected unauthenticated MCP request for gateway [{}]", slug);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid or missing access token");
            return;
        }

        resolved.get().runtime().transport().service(request, response);
    }

    /**
     * /mcp/{slug} 只接受恰好一段路径。
     *
     * 多段路径一律拒绝：transport 的后缀校验会让 /mcp/a/mcp/b 这类路径蒙混过关，
     * 精确匹配放在这里是唯一可靠的一道。
     */
    static String extractSlug(String pathInfo) {
        if (pathInfo == null || pathInfo.length() < 2 || pathInfo.charAt(0) != '/') {
            return null;
        }
        String slug = pathInfo.substring(1);
        if (slug.isEmpty() || slug.indexOf('/') >= 0) {
            return null;
        }
        return slug;
    }
}
