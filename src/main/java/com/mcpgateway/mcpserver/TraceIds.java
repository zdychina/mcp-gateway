package com.mcpgateway.mcpserver;

import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 请求链路标识（需求 FR-06 的 trace_id：无上游值时由网关生成）。
 *
 * 值从 HTTP 层取出后放进 {@link McpTransportContext}，工具处理器的第一个入参就能读到 ——
 * 这是官方 SDK 提供的通道，不需要自建 ThreadLocal。
 */
public final class TraceIds {

    public static final String CONTEXT_KEY = "mcp-gateway.traceId";

    /**
     * 按优先级尝试的请求头。W3C traceparent 排在最前，其次是各类网关和代理常用的自定义头。
     */
    private static final List<String> TRACE_HEADERS = List.of(
            "traceparent", "X-Trace-Id", "X-Request-Id", "X-Correlation-Id");

    /** 与 tool_call_record.trace_id 的列宽一致。 */
    private static final int MAX_LENGTH = 64;

    private TraceIds() {
    }

    /** 供 transport 的 contextExtractor 使用。 */
    public static McpTransportContext fromRequest(HttpServletRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(CONTEXT_KEY, extract(request));
        return McpTransportContext.create(values);
    }

    public static String fromContext(McpTransportContext context) {
        if (context == null) {
            return generate();
        }
        Object value = context.get(CONTEXT_KEY);
        return (value instanceof String traceId && !traceId.isBlank()) ? traceId : generate();
    }

    static String extract(HttpServletRequest request) {
        if (request == null) {
            return generate();
        }
        for (String header : TRACE_HEADERS) {
            String raw = request.getHeader(header);
            String normalized = normalize(header, raw);
            if (normalized != null) {
                return normalized;
            }
        }
        return generate();
    }

    /**
     * traceparent 的格式是 {@code version-traceid-spanid-flags}，取中间的 trace-id 段；
     * 其他头原样使用。上游的值不可信，所以要限长并剔除不适合落库和写日志的字符。
     */
    private static String normalize(String header, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if ("traceparent".equalsIgnoreCase(header)) {
            String[] parts = value.split("-");
            if (parts.length < 3 || parts[1].isBlank()) {
                return null;
            }
            value = parts[1];
        }
        // 只保留可安全落库与写日志的字符，防止日志注入和奇怪的控制字符。
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "");
        if (sanitized.isBlank()) {
            return null;
        }
        return sanitized.length() <= MAX_LENGTH ? sanitized : sanitized.substring(0, MAX_LENGTH);
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }
}
