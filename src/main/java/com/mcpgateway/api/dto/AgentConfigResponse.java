package com.mcpgateway.api.dto;

import java.util.Map;

/**
 * 需求 FR-05：可复制的 Agent 接入 JSON。
 *
 * 只包含访问总 MCP 所需的信息，绝不包含任何子 MCP 的 headers。
 * url 里的 baseUrl 来自部署配置，不从请求头拼接（FR-05.1）。
 */
public record AgentConfigResponse(Map<String, ServerEntry> mcpServers) {

    public record ServerEntry(String type, String url, Map<String, String> headers) {
    }
}
