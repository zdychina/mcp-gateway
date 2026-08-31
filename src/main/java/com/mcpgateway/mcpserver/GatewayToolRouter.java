package com.mcpgateway.mcpserver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.GatewayToolRepository;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * tools/list 与 tools/call 的实现（需求 FR-04 / 6.6）。
 *
 * 两件事都直接读数据库快照，不经过 SDK 自己的工具注册表：
 * <ul>
 *   <li>需求 6.4.1：tools/list 从快照返回，不实时访问下游</li>
 *   <li>需求 6.3.1：调用按 (gateway_id, exposed_name) 精确查表，绝不拆 "__" 猜目标</li>
 * </ul>
 */
@Component
public class GatewayToolRouter {

    private static final Logger log = LoggerFactory.getLogger(GatewayToolRouter.class);

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final GatewayToolRepository tools;

    private final DownstreamMcpRepository downstreams;

    private final DownstreamClientPool clientPool;

    private final ObjectMapper objectMapper;

    public GatewayToolRouter(GatewayToolRepository tools, DownstreamMcpRepository downstreams,
            DownstreamClientPool clientPool, ObjectMapper objectMapper) {
        this.tools = tools;
        this.downstreams = downstreams;
        this.clientPool = clientPool;
        this.objectMapper = objectMapper;
    }

    /**
     * 需求 FR-04：只返回已启用工具，用聚合名称、生效描述和下游原始 Schema。
     */
    @Transactional(readOnly = true)
    public McpSchema.ListToolsResult listTools(String gatewayId) {
        List<McpSchema.Tool> exposed = new ArrayList<>();
        for (GatewayTool tool : this.tools.findEnabledByGatewayId(gatewayId)) {
            var builder = McpSchema.Tool.builder()
                    .name(tool.exposedName())
                    // 需求 6.5.5：自定义描述非空时替换原始描述
                    .description(tool.effectiveDescription());

            Map<String, Object> inputSchema = readJsonObject(tool.inputSchemaJson(), tool.exposedName());
            if (inputSchema != null) {
                builder.inputSchema(inputSchema);
            }
            Map<String, Object> outputSchema = readJsonObject(tool.outputSchemaJson(), tool.exposedName());
            if (outputSchema != null) {
                builder.outputSchema(outputSchema);
            }
            builder.annotations(readAnnotations(tool.annotationsJson(), tool.exposedName()));
            exposed.add(builder.build());
        }
        // MVP 不分页，一次返回全部。
        return new McpSchema.ListToolsResult(exposed, null);
    }

    /**
     * 解析路由目标。
     *
     * 分开成一个方法是为了让打点能在真正调用之前就拿到 downstream_mcp_id 和原工具名，
     * 也让"未知工具"和"已停用"这两种失败可以带上各自的稳定错误码。
     */
    @Transactional(readOnly = true)
    public Route resolve(String gatewayId, String exposedName) {
        GatewayTool tool = this.tools.findByGatewayIdAndExposedName(gatewayId, exposedName)
                .orElseThrow(() -> GatewayException.of(ErrorCode.TOOL_NOT_FOUND,
                        "tool not found or disabled: " + exposedName));

        if (!tool.enabled()) {
            // 需求 6.5.2：停用的工具不得转发给子 MCP。对外的文案与"不存在"保持一致，
            // 免得把网关的配置状态透给 Agent；区分只体现在错误码和调用记录里。
            throw GatewayException.of(ErrorCode.TOOL_DISABLED,
                    "tool not found or disabled: " + exposedName);
        }

        DownstreamMcp downstream = this.downstreams.findById(tool.downstreamMcpId())
                .orElseThrow(() -> GatewayException.of(ErrorCode.TOOL_NOT_FOUND,
                        "tool not found or disabled: " + exposedName));

        return new Route(tool, downstream);
    }

    /**
     * 需求 6.6.2 / 6.6.3：参数原样传给子 MCP 的原工具，结果原样带回。
     * 需求 6.6.4：不自动重试 —— 无法假定下游工具幂等。
     */
    public McpSchema.CallToolResult call(Route route, Map<String, Object> arguments) {
        McpSyncClient client = this.clientPool.acquire(route.downstream());
        try {
            return client.callTool(new McpSchema.CallToolRequest(route.tool().originalName(), arguments));
        }
        catch (RuntimeException ex) {
            // 连接可能已经坏了，丢掉缓存让下一次重新建连。注意这不是重试本次调用。
            this.clientPool.evict(route.downstream().id());
            throw com.mcpgateway.downstream.DownstreamErrorMapper.map(ex, ErrorCode.DOWNSTREAM_ERROR,
                    route.downstream().name());
        }
    }

    private Map<String, Object> readJsonObject(String json, String toolName) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return this.objectMapper.readValue(json, JSON_OBJECT);
        }
        catch (Exception ex) {
            // 快照里的 Schema 存坏了。宁可不带 Schema 也要让工具可见，
            // 否则一条坏数据会让整个 tools/list 失败。
            log.warn("tool {} has an unreadable schema in the snapshot, exposing it without one", toolName);
            return null;
        }
    }

    private McpSchema.ToolAnnotations readAnnotations(String json, String toolName) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return this.objectMapper.readValue(json, McpSchema.ToolAnnotations.class);
        }
        catch (Exception ex) {
            log.warn("tool {} has unreadable annotations in the snapshot, exposing it without them", toolName);
            return null;
        }
    }

    /** 一次调用的路由目标。 */
    public record Route(GatewayTool tool, DownstreamMcp downstream) {
    }

    /** 把 tools/call 的 params 转成 SDK 的请求对象。 */
    public McpSchema.CallToolRequest parseCallRequest(Object params) {
        try {
            return this.objectMapper.convertValue(params, McpSchema.CallToolRequest.class);
        }
        catch (IllegalArgumentException ex) {
            throw GatewayException.of(ErrorCode.INVALID_TOOL_ARGUMENTS, "malformed tools/call parameters");
        }
    }

    /** 参数为 null 时按空对象处理，避免下游收到 null 而不是 {}。 */
    public static Map<String, Object> argumentsOf(McpSchema.CallToolRequest request) {
        return request.arguments() == null ? new LinkedHashMap<>() : request.arguments();
    }
}
