package com.mcpgateway.mcpserver;

import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.recording.ToolCallRecorder;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * handler 装饰器的分支覆盖，重点在错误路径。
 *
 * 这个类是网关对 Agent 的唯一出口，任何一条错误分支泄漏了下游细节都是安全问题，
 * 所以逐条钉住返回的 code、data 和 message。
 */
class GatewayMcpHandlerTest {

    private static final McpSchema.JSONRPCResponse DELEGATED =
            McpSchema.JSONRPCResponse.result("delegated", Map.of());

    private final AtomicInteger delegateRequests = new AtomicInteger();

    private final AtomicInteger delegateNotifications = new AtomicInteger();

    private final McpStatelessServerHandler delegate = new McpStatelessServerHandler() {
        @Override
        public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
                McpSchema.JSONRPCRequest request) {
            GatewayMcpHandlerTest.this.delegateRequests.incrementAndGet();
            return Mono.just(DELEGATED);
        }

        @Override
        public Mono<Void> handleNotification(McpTransportContext context,
                McpSchema.JSONRPCNotification notification) {
            GatewayMcpHandlerTest.this.delegateNotifications.incrementAndGet();
            return Mono.empty();
        }
    };

    private GatewayToolRouter router;

    private ToolCallRecorder recorder;

    private GatewayMcpHandler handler;

    @BeforeEach
    void setUp() {
        this.router = mock(GatewayToolRouter.class);
        this.recorder = mock(ToolCallRecorder.class);
        this.handler = new GatewayMcpHandler(this.delegate, "gw-1", "slug-1", this.router, this.recorder);
    }

    private McpSchema.JSONRPCResponse handle(String method, Object params) {
        return this.handler.handleRequest(McpTransportContext.EMPTY,
                new McpSchema.JSONRPCRequest("2.0", method, "req-1", params)).block();
    }

    private static String errorCodeOf(McpSchema.JSONRPCResponse response) {
        return ((Map<?, ?>) response.error().data()).get("errorCode").toString();
    }

    // ------------------------------------------------------------ 委派

    @Test
    @DisplayName("需求 16.1：除 tools/list 和 tools/call 外的方法原样交给 SDK，不自研协议栈")
    void delegatesEverythingElse() {
        assertThat(handle("initialize", Map.of())).isSameAs(DELEGATED);
        assertThat(handle("ping", null)).isSameAs(DELEGATED);
        assertThat(handle("resources/list", null)).isSameAs(DELEGATED);
        assertThat(this.delegateRequests.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("通知一律交给 SDK")
    void delegatesNotifications() {
        this.handler.handleNotification(McpTransportContext.EMPTY,
                new McpSchema.JSONRPCNotification("2.0", "notifications/initialized", null)).block();

        assertThat(this.delegateNotifications.get()).isEqualTo(1);
    }

    // ---------------------------------------------------------- tools/list

    @Test
    @DisplayName("tools/list 从路由器取快照结果")
    void listToolsReturnsSnapshot() {
        var expected = new McpSchema.ListToolsResult(List.of(), null);
        when(this.router.listTools("gw-1")).thenReturn(expected);

        assertThat(handle(McpSchema.METHOD_TOOLS_LIST, null).result()).isSameAs(expected);
    }

    @Test
    @DisplayName("tools/list 内部出错时返回 INTERNAL_ERROR，不泄漏异常内容")
    void listToolsFailureIsOpaque() {
        when(this.router.listTools("gw-1"))
                .thenThrow(new IllegalStateException("connection pool exhausted at 10.0.0.5"));

        McpSchema.JSONRPCResponse response = handle(McpSchema.METHOD_TOOLS_LIST, null);

        assertThat(response.error().code()).isEqualTo(-32603);
        assertThat(errorCodeOf(response)).isEqualTo("INTERNAL_ERROR");
        assertThat(response.error().message()).isEqualTo("internal error");
        assertThat(response.error().message()).doesNotContain("10.0.0.5");
    }

    // ---------------------------------------------------------- tools/call

    @Test
    @DisplayName("params 解析失败返回 INVALID_PARAMS 和 INVALID_TOOL_ARGUMENTS")
    void malformedParamsAreRejected() {
        when(this.router.parseCallRequest(any()))
                .thenThrow(GatewayException.of(ErrorCode.INVALID_TOOL_ARGUMENTS, "malformed tools/call parameters"));

        McpSchema.JSONRPCResponse response = handle(McpSchema.METHOD_TOOLS_CALL, "not-an-object");

        assertThat(response.error().code()).isEqualTo(-32602);
        assertThat(errorCodeOf(response)).isEqualTo("INVALID_TOOL_ARGUMENTS");
    }

    @Test
    @DisplayName("工具名缺失或空白时返回 INVALID_PARAMS")
    void missingToolNameIsRejected() {
        when(this.router.parseCallRequest(any()))
                .thenReturn(new McpSchema.CallToolRequest("   ", Map.of()));

        McpSchema.JSONRPCResponse response = handle(McpSchema.METHOD_TOOLS_CALL, Map.of());

        assertThat(response.error().code()).isEqualTo(-32602);
        assertThat(errorCodeOf(response)).isEqualTo("INVALID_TOOL_ARGUMENTS");
        assertThat(response.error().message()).isEqualTo("tool name is required");
    }

    @Test
    @DisplayName("未知工具与停用工具映射到 -32602，各带自己的稳定错误码")
    void unknownAndDisabledToolsMapToInvalidParams() {
        when(this.router.parseCallRequest(any()))
                .thenReturn(new McpSchema.CallToolRequest("kb_a__search", Map.of()));

        when(this.router.resolve(anyString(), anyString()))
                .thenThrow(GatewayException.of(ErrorCode.TOOL_NOT_FOUND, "tool not found or disabled: kb_a__search"));
        McpSchema.JSONRPCResponse notFound = handle(McpSchema.METHOD_TOOLS_CALL, Map.of());
        assertThat(notFound.error().code()).isEqualTo(-32602);
        assertThat(errorCodeOf(notFound)).isEqualTo("TOOL_NOT_FOUND");

        this.router = mock(GatewayToolRouter.class);
        this.handler = new GatewayMcpHandler(this.delegate, "gw-1", "slug-1", this.router, this.recorder);
        when(this.router.parseCallRequest(any()))
                .thenReturn(new McpSchema.CallToolRequest("kb_a__search", Map.of()));
        when(this.router.resolve(anyString(), anyString()))
                .thenThrow(GatewayException.of(ErrorCode.TOOL_DISABLED, "tool not found or disabled: kb_a__search"));
        McpSchema.JSONRPCResponse disabled = handle(McpSchema.METHOD_TOOLS_CALL, Map.of());
        assertThat(disabled.error().code()).isEqualTo(-32602);
        assertThat(errorCodeOf(disabled)).isEqualTo("TOOL_DISABLED");

        // 两者对外文案完全相同，不暴露工具是否存在
        assertThat(disabled.error().message()).isEqualTo(notFound.error().message());
    }

    @Test
    @DisplayName("下游超时映射为 -32603 并带上 DOWNSTREAM_TIMEOUT")
    void downstreamTimeoutIsReported() {
        when(this.router.parseCallRequest(any()))
                .thenReturn(new McpSchema.CallToolRequest("kb_a__search", Map.of()));
        when(this.router.resolve(anyString(), anyString()))
                .thenThrow(GatewayException.of(ErrorCode.DOWNSTREAM_TIMEOUT, "kb_a: downstream call timed out"));

        McpSchema.JSONRPCResponse response = handle(McpSchema.METHOD_TOOLS_CALL, Map.of());

        assertThat(response.error().code()).isEqualTo(-32603);
        assertThat(errorCodeOf(response)).isEqualTo("DOWNSTREAM_TIMEOUT");
    }

    @Test
    @DisplayName("未预期的异常折叠为 INTERNAL_ERROR，异常内容不外泄")
    void unexpectedFailureIsOpaque() {
        when(this.router.parseCallRequest(any()))
                .thenReturn(new McpSchema.CallToolRequest("kb_a__search", Map.of()));
        when(this.router.resolve(anyString(), anyString()))
                .thenThrow(new IllegalStateException("Bearer sk-secret-value rejected by 10.0.0.5"));

        McpSchema.JSONRPCResponse response = handle(McpSchema.METHOD_TOOLS_CALL, Map.of());

        assertThat(response.error().code()).isEqualTo(-32603);
        assertThat(errorCodeOf(response)).isEqualTo("INTERNAL_ERROR");
        assertThat(response.error().message())
                .isEqualTo("internal error")
                .doesNotContain("sk-secret-value")
                .doesNotContain("10.0.0.5");
    }

    @Test
    @DisplayName("需求 FR-06：未知工具也走完 recordStarted + 终态两步，不留缺口")
    void failedRoutingStillRecordsBothPhases() {
        when(this.router.parseCallRequest(any()))
                .thenReturn(new McpSchema.CallToolRequest("kb_a__nope", Map.of()));
        when(this.router.resolve(anyString(), anyString()))
                .thenThrow(GatewayException.of(ErrorCode.TOOL_NOT_FOUND, "tool not found or disabled: kb_a__nope"));

        handle(McpSchema.METHOD_TOOLS_CALL, Map.of());

        // 路由没解析出来，所以子 MCP 和原工具名为空，但记录本身必须存在
        org.mockito.Mockito.verify(this.recorder).recordStarted(anyString(), anyString(), eq("gw-1"),
                isNull(), eq("kb_a__nope"), isNull(), any(), any());
        org.mockito.Mockito.verify(this.recorder).recordFailure(anyString(), eq(ErrorCode.TOOL_NOT_FOUND),
                anyString(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("成功调用记录成功终态，且带上解析出来的子 MCP 与原工具名")
    void successfulCallRecordsBothPhasesWithRouteDetails() {
        var route = mock(GatewayToolRouter.Route.class);
        var downstream = mock(com.mcpgateway.domain.DownstreamMcp.class);
        var tool = mock(com.mcpgateway.domain.GatewayTool.class);
        when(downstream.id()).thenReturn("ds-1");
        when(tool.originalName()).thenReturn("search");
        when(route.downstream()).thenReturn(downstream);
        when(route.tool()).thenReturn(tool);
        var result = new McpSchema.CallToolResult(List.of(), false, null, null);
        when(this.router.parseCallRequest(any()))
                .thenReturn(new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "x")));
        when(this.router.resolve("gw-1", "kb_a__search")).thenReturn(route);
        when(this.router.call(any(), any())).thenReturn(result);

        handle(McpSchema.METHOD_TOOLS_CALL, Map.of());

        org.mockito.Mockito.verify(this.recorder).recordStarted(anyString(), anyString(), eq("gw-1"),
                eq("ds-1"), eq("kb_a__search"), eq("search"), any(), any());
        org.mockito.Mockito.verify(this.recorder).recordSuccess(anyString(), eq(result), any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("tools/list 不触发任何打点")
    void listingToolsIsNotRecorded() {
        when(this.router.listTools("gw-1")).thenReturn(new McpSchema.ListToolsResult(List.of(), null));

        handle(McpSchema.METHOD_TOOLS_LIST, null);

        org.mockito.Mockito.verifyNoInteractions(this.recorder);
    }

    @Test
    @DisplayName("需求 6.6.3：下游结果原样返回，包括它自己标记的业务错误")
    void downstreamResultIsPassedThroughUnchanged() {
        var route = mock(GatewayToolRouter.Route.class);
        var downstream = mock(com.mcpgateway.domain.DownstreamMcp.class);
        var tool = mock(com.mcpgateway.domain.GatewayTool.class);
        when(downstream.id()).thenReturn("ds-1");
        when(tool.originalName()).thenReturn("search");
        when(route.downstream()).thenReturn(downstream);
        when(route.tool()).thenReturn(tool);
        var downstreamResult = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("下游说这次不行")), true, null, null);
        when(this.router.parseCallRequest(any()))
                .thenReturn(new McpSchema.CallToolRequest("kb_a__search", Map.of("q", "x")));
        when(this.router.resolve("gw-1", "kb_a__search")).thenReturn(route);
        when(this.router.call(any(), any())).thenReturn(downstreamResult);

        McpSchema.JSONRPCResponse response = handle(McpSchema.METHOD_TOOLS_CALL, Map.of());

        // 业务错误不转成 JSON-RPC 错误，原样作为结果带回（MCP 的约定）
        assertThat(response.error()).isNull();
        assertThat(response.result()).isSameAs(downstreamResult);
    }
}
