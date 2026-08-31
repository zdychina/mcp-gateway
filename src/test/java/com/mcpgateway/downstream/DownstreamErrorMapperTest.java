package com.mcpgateway.downstream;

import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamErrorMapperTest {

    private static ErrorCode codeOf(Throwable ex) {
        return DownstreamErrorMapper.map(ex, ErrorCode.DOWNSTREAM_SYNC_FAILED, "kb_a").errorCode();
    }

    @Test
    @DisplayName("超时映射为 DOWNSTREAM_TIMEOUT，即使被包在传输异常里")
    void mapsTimeoutThroughWrappers() {
        assertThat(codeOf(new TimeoutException("timed out"))).isEqualTo(ErrorCode.DOWNSTREAM_TIMEOUT);
        // SDK 常把底层异常包一层，只看根因或只看最外层都会错判
        assertThat(codeOf(new McpTransportException("send failed", new TimeoutException())))
                .isEqualTo(ErrorCode.DOWNSTREAM_TIMEOUT);
    }

    @Test
    @DisplayName("连不上和域名解析失败映射为默认错误码，且不回显主机端口")
    void mapsUnreachableWithoutLeakingTopology() {
        GatewayException ex = DownstreamErrorMapper.map(
                new McpTransportException("failed", new ConnectException("Connection refused: 10.0.0.5:8443")),
                ErrorCode.DOWNSTREAM_INIT_FAILED, "kb_a");

        assertThat(ex.errorCode()).isEqualTo(ErrorCode.DOWNSTREAM_INIT_FAILED);
        assertThat(ex.getMessage()).isEqualTo("kb_a: downstream is unreachable");
        assertThat(ex.getMessage()).doesNotContain("10.0.0.5").doesNotContain("8443");

        assertThat(codeOf(new UnknownHostException("no-such-host.internal")))
                .isEqualTo(ErrorCode.DOWNSTREAM_SYNC_FAILED);
        assertThat(DownstreamErrorMapper.map(new UnknownHostException("no-such-host.internal"),
                ErrorCode.DOWNSTREAM_SYNC_FAILED, "kb_a").getMessage())
                .doesNotContain("no-such-host.internal");
    }

    @Test
    @DisplayName("下游 JSON-RPC 错误映射为 DOWNSTREAM_ERROR 并保留可理解的摘要")
    void mapsDownstreamJsonRpcError() {
        McpError error = new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(
                -32602, "Unknown tool: nope", null));

        GatewayException mapped = DownstreamErrorMapper.map(error, ErrorCode.DOWNSTREAM_SYNC_FAILED, "kb_a");

        assertThat(mapped.errorCode()).isEqualTo(ErrorCode.DOWNSTREAM_ERROR);
        assertThat(mapped.getMessage()).contains("Unknown tool: nope");
    }

    @Test
    @DisplayName("无法归类的异常落到调用方给的默认错误码，消息是我们自己的文案")
    void fallsBackToTheProvidedDefault() {
        GatewayException sync = DownstreamErrorMapper.map(new IllegalStateException("boom"),
                ErrorCode.DOWNSTREAM_SYNC_FAILED, "kb_a");
        GatewayException init = DownstreamErrorMapper.map(new IllegalStateException("boom"),
                ErrorCode.DOWNSTREAM_INIT_FAILED, "kb_a");

        assertThat(sync.errorCode()).isEqualTo(ErrorCode.DOWNSTREAM_SYNC_FAILED);
        assertThat(sync.getMessage()).doesNotContain("boom").contains("failed to read tools");
        assertThat(init.getMessage()).contains("failed to initialize");
    }

    @Test
    @DisplayName("超长下游错误被截断，避免撑爆日志和 error_message 列")
    void summarisesOverlongMessages() {
        String summary = DownstreamErrorMapper.summarize("x".repeat(500));

        assertThat(summary).hasSize(203).endsWith("...");
        assertThat(DownstreamErrorMapper.summarize("  多行\n错误  ")).isEqualTo("多行 错误");
        assertThat(DownstreamErrorMapper.summarize(null)).isEqualTo("downstream returned an error");
        assertThat(DownstreamErrorMapper.summarize("  ")).isEqualTo("downstream returned an error");
    }

    @Test
    @DisplayName("自引用的异常链不会导致死循环")
    void handlesSelfReferencingCause() {
        RuntimeException self = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(codeOf(self)).isEqualTo(ErrorCode.DOWNSTREAM_SYNC_FAILED);
    }

    /**
     * SDK 客户端侧的超限异常是包级私有的，只能按类名匹配。这个断言守住那个类名：
     * SDK 一旦改名，这里会先失败，而不是让大响应体被静默归到"未知错误"里。
     */
    @Test
    @DisplayName("SDK 客户端超限异常的类名未发生变化")
    void sdkMaxSizeExceededClassNameIsStable() {
        Class<?> clazz = assertClassExists(
                "io.modelcontextprotocol.client.transport." + DownstreamErrorMapper.MAX_SIZE_EXCEEDED);
        assertThat(clazz.getSimpleName()).isEqualTo(DownstreamErrorMapper.MAX_SIZE_EXCEEDED);
        assertThat(Throwable.class).isAssignableFrom(clazz);
    }

    private static Class<?> assertClassExists(String name) {
        try {
            return Class.forName(name);
        }
        catch (ClassNotFoundException ex) {
            throw new AssertionError("SDK class no longer exists: " + name
                    + "; DownstreamErrorMapper.MAX_SIZE_EXCEEDED needs updating", ex);
        }
    }
}
