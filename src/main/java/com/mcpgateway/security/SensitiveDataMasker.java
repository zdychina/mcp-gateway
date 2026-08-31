package com.mcpgateway.security;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 遮罩工具。需求 12.4：配置查询接口只返回 header 名称及遮罩值；
 * 需求 12.5：应用日志、调用记录和异常信息必须过滤 Authorization、Cookie、API Key 等凭证。
 *
 * 这里对**所有** header 值一律遮罩，而不是只遮罩已知的敏感名。任何 header 都可能被
 * 某个子 MCP 当成凭证使用，白名单式的判断迟早会漏。
 */
public final class SensitiveDataMasker {

    public static final String MASK = "******";

    /** 已知的凭证类 header，仅用于日志里的额外强调，不影响"全部遮罩"的策略。 */
    private static final Set<String> WELL_KNOWN_CREDENTIAL_HEADERS = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "api-key",
            "x-auth-token",
            "x-access-token");

    private SensitiveDataMasker() {
    }

    /**
     * 把 header 映射转成只含名称的遮罩视图，供 API 和前端展示。
     * 返回值保持原有顺序，方便前端稳定渲染。
     */
    public static Map<String, String> maskValues(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> masked = new LinkedHashMap<>();
        headers.forEach((name, value) -> masked.put(name, MASK));
        return masked;
    }

    public static boolean isWellKnownCredentialHeader(String headerName) {
        return headerName != null
                && WELL_KNOWN_CREDENTIAL_HEADERS.contains(headerName.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 供日志使用：只保留 header 名称，值一律替换成遮罩串。
     * 任何时候都不要直接 log 原始 header 映射。
     */
    public static String describeForLog(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "{}";
        }
        return String.join(", ", headers.keySet().stream().map(name -> name + "=" + MASK).toList());
    }

    /**
     * 遮罩单个令牌，只保留前 4 位用于人工核对。长度不足时整体遮罩。
     * 用于"令牌只完整显示一次"之后的占位符展示（需求 FR-05.3）。
     */
    public static String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return MASK;
        }
        return token.substring(0, 4) + MASK;
    }
}
