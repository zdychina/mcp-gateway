package com.mcpgateway.mcpserver;

import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.recording.ToolCallRecorder;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 网关自己的 MCP 请求处理器，装饰在 SDK 默认 handler 之外。
 *
 * 只接管 tools/list 和 tools/call 两个方法，其余（initialize、ping、协议协商等）原样交给
 * SDK 处理 —— 需求 16.1 明确禁止把探针扩展成自研 MCP 协议栈，这里守住那条线。
 *
 * 接管这两个方法换来三件事：
 * <ol>
 *   <li>tools/list 直接读数据库快照，不需要把工具注册进 SDK，也就不需要在每次启停后
 *       重建 server 实例（需求 6.4.1 / 6.4.8）</li>
 *   <li>停用工具能返回网关自己的 TOOL_DISABLED，而不是 SDK 固定的 "Unknown tool"</li>
 *   <li>未知工具和停用工具的调用同样会经过这里，打点因此没有缺口 —— 如果依赖 SDK 的
 *       工具注册表，这两种请求根本到不了我们的代码，FR-06 就会漏记</li>
 * </ol>
 */
public class GatewayMcpHandler implements McpStatelessServerHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayMcpHandler.class);

    /** JSON-RPC 标准错误码。 */
    private static final int INVALID_PARAMS = -32602;

    private static final int INTERNAL_ERROR = -32603;

    private final McpStatelessServerHandler delegate;

    private final String gatewayId;

    private final String gatewaySlug;

    private final GatewayToolRouter router;

    private final ToolCallRecorder recorder;

    public GatewayMcpHandler(McpStatelessServerHandler delegate, String gatewayId, String gatewaySlug,
            GatewayToolRouter router, ToolCallRecorder recorder) {
        this.delegate = delegate;
        this.gatewayId = gatewayId;
        this.gatewaySlug = gatewaySlug;
        this.router = router;
        this.recorder = recorder;
    }

    @Override
    public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
            McpSchema.JSONRPCRequest request) {
        return switch (request.method()) {
            case McpSchema.METHOD_TOOLS_LIST -> Mono.fromCallable(() -> listTools(request));
            case McpSchema.METHOD_TOOLS_CALL -> Mono.fromCallable(() -> callTool(context, request));
            default -> this.delegate.handleRequest(context, request);
        };
    }

    @Override
    public Mono<Void> handleNotification(McpTransportContext context, McpSchema.JSONRPCNotification notification) {
        return this.delegate.handleNotification(context, notification);
    }

    private McpSchema.JSONRPCResponse listTools(McpSchema.JSONRPCRequest request) {
        try {
            return McpSchema.JSONRPCResponse.result(request.id(), this.router.listTools(this.gatewayId));
        }
        catch (GatewayException ex) {
            return error(request.id(), INTERNAL_ERROR, ex);
        }
        catch (RuntimeException ex) {
            log.error("tools/list failed for gateway {}", this.gatewaySlug, ex);
            return error(request.id(), INTERNAL_ERROR,
                    GatewayException.of(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * 需求 FR-06：每次 tools/call 都要产生一条记录，成功、业务错误、协议错误、网络错误
     * 和超时都算在内。
     *
     * 参数解析之前的失败（params 完全读不出来）拿不到工具名，无法构造一条有意义的记录，
     * 这类请求会被 SDK 之外的调用方式触发的概率极低，这里只返回错误码。其余所有路径 ——
     * 包括未知工具和停用工具 —— 都会走完 recordStarted + 终态两步。
     */
    private McpSchema.JSONRPCResponse callTool(McpTransportContext context, McpSchema.JSONRPCRequest request) {
        McpSchema.CallToolRequest callRequest;
        try {
            callRequest = this.router.parseCallRequest(request.params());
        }
        catch (GatewayException ex) {
            return error(request.id(), INVALID_PARAMS, ex);
        }
        if (callRequest == null || callRequest.name() == null || callRequest.name().isBlank()) {
            return error(request.id(), INVALID_PARAMS,
                    GatewayException.of(ErrorCode.INVALID_TOOL_ARGUMENTS, "tool name is required"));
        }

        String callId = UUID.randomUUID().toString();
        String traceId = TraceIds.fromContext(context);
        String exposedName = callRequest.name();
        Map<String, Object> arguments = GatewayToolRouter.argumentsOf(callRequest);
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();

        // 先把路由解析出来，这样 STARTED 记录就能带上子 MCP 和原工具名。
        // 解析失败也不提前返回 —— 未知工具和停用工具同样要留下完整的两阶段记录。
        GatewayToolRouter.Route route = null;
        GatewayException routingFailure = null;
        try {
            route = this.router.resolve(this.gatewayId, exposedName);
        }
        catch (GatewayException ex) {
            routingFailure = ex;
        }
        catch (RuntimeException ex) {
            log.error("failed to resolve tool {} on gateway {}", exposedName, this.gatewaySlug, ex);
            routingFailure = GatewayException.of(ErrorCode.INTERNAL_ERROR, "internal error");
        }

        this.recorder.recordStarted(callId, traceId, this.gatewayId,
                route == null ? null : route.downstream().id(), exposedName,
                route == null ? null : route.tool().originalName(), arguments, startedAt);

        if (routingFailure != null) {
            return failed(request.id(), callId, routingFailure, startedNanos);
        }

        try {
            // 结果原样返回，包括下游自己标记的 isError 业务错误（需求 6.6.3）。
            McpSchema.CallToolResult result = this.router.call(route, arguments);
            this.recorder.recordSuccess(callId, result, Instant.now(), elapsedMillis(startedNanos));
            return McpSchema.JSONRPCResponse.result(request.id(), result);
        }
        catch (GatewayException ex) {
            return failed(request.id(), callId, ex, startedNanos);
        }
        catch (RuntimeException ex) {
            log.error("tools/call failed for gateway {} tool {}", this.gatewaySlug, exposedName, ex);
            return failed(request.id(), callId,
                    GatewayException.of(ErrorCode.INTERNAL_ERROR, "internal error"), startedNanos);
        }
    }

    private McpSchema.JSONRPCResponse failed(Object requestId, String callId, GatewayException ex,
            long startedNanos) {
        // 落库的错误摘要和返回给 Agent 的是同一份已脱敏文案，不会因为写库而多暴露什么。
        this.recorder.recordFailure(callId, ex.errorCode(), ex.getMessage(), Instant.now(),
                elapsedMillis(startedNanos));
        return error(requestId, jsonRpcCodeFor(ex.errorCode()), ex);
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static int jsonRpcCodeFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case TOOL_NOT_FOUND, TOOL_DISABLED, INVALID_TOOL_ARGUMENTS -> INVALID_PARAMS;
            default -> INTERNAL_ERROR;
        };
    }

    /**
     * 网关的稳定错误码放在 data 里，message 只放已脱敏的短句（需求 6.6.6 / 6.6.7）。
     * 这样 Agent 既能拿到机器可读的分类，又不会看到 header、令牌或堆栈。
     */
    private static McpSchema.JSONRPCResponse error(Object id, int jsonRpcCode, GatewayException ex) {
        return McpSchema.JSONRPCResponse.error(id, new McpSchema.JSONRPCResponse.JSONRPCError(
                jsonRpcCode, ex.getMessage(), Map.of("errorCode", ex.errorCode().name())));
    }
}
