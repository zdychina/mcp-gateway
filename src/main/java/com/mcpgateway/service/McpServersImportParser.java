package com.mcpgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析并校验粘贴进来的 {@code mcpServers} 配置 JSON（需求 FR-02）。
 *
 * 这里刻意直接吃 {@link JsonNode} 而不是绑定到一个 DTO：需求要求对"出现了 command/args/env"
 * 这类情况给出明确的 UNSUPPORTED_TRANSPORT，而 Jackson 的默认绑定只会把未知字段忽略掉，
 * 用户就会看到一个"成功导入"却完全不是他想要的结果。
 */
@Component
public class McpServersImportParser {

    private static final String ROOT_FIELD = "mcpServers";

    private static final Pattern NAME_PATTERN = Pattern.compile(DownstreamMcp.NAME_PATTERN);

    /** 需求 3.2 / FR-02：stdio 配置的判定特征，出现即拒绝。 */
    private static final List<String> STDIO_FIELDS = List.of("command", "args", "env");

    /**
     * @param root       请求体根节点
     * @param maxServers 本次最多允许导入几个（由"上限 3 减去已有数量"算出）
     */
    public List<ParsedDownstream> parse(JsonNode root, int maxServers) {
        if (root == null || !root.isObject() || !root.has(ROOT_FIELD)) {
            throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG, "root object must contain mcpServers");
        }
        JsonNode servers = root.get(ROOT_FIELD);
        if (!servers.isObject()) {
            throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG, "mcpServers must be an object");
        }
        if (servers.isEmpty()) {
            throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG, "mcpServers must contain at least one server");
        }
        if (servers.size() > maxServers) {
            throw GatewayException.of(ErrorCode.MCP_SERVER_LIMIT_EXCEEDED,
                    "at most " + maxServers + " more downstream MCP server(s) can be imported");
        }

        List<ParsedDownstream> parsed = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = servers.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            parsed.add(parseServer(entry.getKey(), entry.getValue()));
        }
        return parsed;
    }

    private ParsedDownstream parseServer(String name, JsonNode node) {
        validateName(name);

        if (node == null || !node.isObject()) {
            throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG, name + ": server entry must be an object");
        }

        // 先查 stdio 特征。哪怕同时写了 type=streamable-http，这份配置也是自相矛盾的，
        // 与其猜用户想要哪个，不如直接报错。
        for (String stdioField : STDIO_FIELDS) {
            if (node.has(stdioField)) {
                throw GatewayException.of(ErrorCode.UNSUPPORTED_TRANSPORT,
                        name + ": stdio configuration (" + stdioField + ") is not supported");
            }
        }

        JsonNode typeNode = node.get("type");
        if (typeNode == null || !typeNode.isTextual() || typeNode.asText().isBlank()) {
            throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG, name + ": type is required");
        }
        String type = typeNode.asText().trim();
        if (!DownstreamMcp.TYPE_STREAMABLE_HTTP.equals(type)) {
            throw GatewayException.of(ErrorCode.UNSUPPORTED_TRANSPORT,
                    name + ": only " + DownstreamMcp.TYPE_STREAMABLE_HTTP + " is supported, got " + type);
        }

        JsonNode urlNode = node.get("url");
        if (urlNode == null || !urlNode.isTextual()) {
            throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG, name + ": url is required");
        }
        String url = urlNode.asText().trim();
        DownstreamUrlValidator.validate(url, name);

        return new ParsedDownstream(name, type, url, parseHeaders(name, node.get("headers")));
    }

    /** 需求 6.2.3：名称只能含字母、数字、下划线、短横线和点，且不得包含连续分隔符 __。 */
    private void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG,
                    "server name must match " + DownstreamMcp.NAME_PATTERN);
        }
        if (DownstreamMcp.containsDoubleUnderscore(name)) {
            // __ 是聚合工具名的分隔符，名字里再出现就无法把聚合名读回原始结构。
            throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG,
                    name + ": server name must not contain '__'");
        }
    }

    /** 需求 FR-02：headers 缺省时按空对象处理。 */
    private Map<String, String> parseHeaders(String name, JsonNode headersNode) {
        if (headersNode == null || headersNode.isNull()) {
            return Map.of();
        }
        if (!headersNode.isObject()) {
            throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG, name + ": headers must be an object");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = headersNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (!value.isTextual()) {
                // 不回显值本身，它很可能是凭证。
                throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG,
                        name + ": header " + entry.getKey() + " must be a string");
            }
            headers.put(entry.getKey(), value.asText());
        }
        return headers;
    }

    /**
     * 一条已校验通过的子 MCP 配置。headers 是**明文**，只允许交给
     * {@code DownstreamHeaderCodec} 加密后落库，不得直接回显或写日志。
     */
    public record ParsedDownstream(String name, String type, String url, Map<String, String> headers) {
    }
}
