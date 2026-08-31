package com.mcpgateway.mcpserver;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 需求 12.6：Streamable HTTP 端点校验 Origin。
 *
 * 这是防 DNS rebinding / 浏览器跨站直连本地网关的常规措施。规则：
 * <ul>
 *   <li>没有 Origin 头：放行。Agent 之类的非浏览器客户端不会带这个头。</li>
 *   <li>有 Origin 且在允许列表里：放行。</li>
 *   <li>有 Origin 但不在列表里：403。本机部署默认列表为空，也就是拒绝一切浏览器来源。</li>
 * </ul>
 */
public class OriginValidator implements ServerTransportSecurityValidator {

    private static final String ORIGIN = "origin";

    private static final int FORBIDDEN = 403;

    private final Set<String> allowedOrigins;

    public OriginValidator(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? Set.of()
                : allowedOrigins.stream()
                        .filter(origin -> origin != null && !origin.isBlank())
                        .map(origin -> origin.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
        String origin = firstOrigin(headers);
        if (origin == null) {
            return;
        }
        if (!this.allowedOrigins.contains(origin.trim().toLowerCase(Locale.ROOT))) {
            // 不回显收到的 Origin，避免把它反射进响应。
            throw new ServerTransportSecurityException(FORBIDDEN, "origin is not allowed");
        }
    }

    /** header 名大小写不敏感。 */
    private static String firstOrigin(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && ORIGIN.equalsIgnoreCase(entry.getKey().trim())) {
                List<String> values = entry.getValue();
                return (values == null || values.isEmpty()) ? null : values.getFirst();
            }
        }
        return null;
    }
}
