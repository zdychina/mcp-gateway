package com.mcpgateway.downstream;

import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import io.modelcontextprotocol.spec.McpError;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 把下游抛出的各种异常收敛成网关的稳定错误码（需求 6.6.6 / 6.6.7）。
 *
 * 唯一的硬约束：返回的 message 必须是我们自己拼的、不含 header、令牌、堆栈和内网地址的短句。
 * 原始异常只作为 cause 传下去进服务端日志，不进 API 响应。
 */
public final class DownstreamErrorMapper {

    private DownstreamErrorMapper() {
    }

    /**
     * @param ex          下游抛出的异常
     * @param defaultCode 无法归类时使用的错误码，同步场景传 DOWNSTREAM_SYNC_FAILED，
     *                    初始化场景传 DOWNSTREAM_INIT_FAILED
     * @param name        子 MCP 名称，用于定位；这是操作人自己起的名字，可以安全回显
     */
    public static GatewayException map(Throwable ex, ErrorCode defaultCode, String name) {
        // 遍历整条异常链而不是只看根因：SDK 常把底层的 TimeoutException 包在 McpTransportException 里，
        // 而 McpError 本身又往往是最外层，只取根因两边都会错判。
        for (Throwable current = ex; current != null; current = nextCause(current)) {
            if (current instanceof TimeoutException || current instanceof HttpConnectTimeoutException) {
                return new GatewayException(ErrorCode.DOWNSTREAM_TIMEOUT,
                        name + ": downstream call timed out", ex);
            }
            // SDK 客户端侧的 MaxSizeExceededException 是包级私有的，无法按类型匹配，
            // 只能比对类名。改用类名的代价是 SDK 改名时这条会静默失效，
            // 因此 DownstreamErrorMapperTest 里钉了一个断言守住它。
            if (MAX_SIZE_EXCEEDED.equals(current.getClass().getSimpleName())) {
                return new GatewayException(ErrorCode.PAYLOAD_TOO_LARGE,
                        name + ": downstream response exceeded the configured size limit", ex);
            }
            if (current instanceof UnknownHostException || current instanceof ConnectException) {
                // 不回显主机名和端口，避免把内网拓扑透给调用方。
                return new GatewayException(defaultCode, name + ": downstream is unreachable", ex);
            }
            if (current instanceof McpError mcpError) {
                // 下游的业务错误文本对 Agent 有用，但要挡住长度和潜在的凭证回显。
                String detail = mcpError.getJsonRpcError() == null
                        ? mcpError.getMessage()
                        : mcpError.getJsonRpcError().message();
                return new GatewayException(ErrorCode.DOWNSTREAM_ERROR, name + ": " + summarize(detail), ex);
            }
        }
        return new GatewayException(defaultCode, name + ": " + defaultDescription(defaultCode), ex);
    }

    private static Throwable nextCause(Throwable current) {
        Throwable cause = current.getCause();
        return cause == current ? null : cause;
    }

    private static String defaultDescription(ErrorCode code) {
        return code == ErrorCode.DOWNSTREAM_INIT_FAILED
                ? "failed to initialize the downstream MCP session"
                : "failed to read tools from the downstream MCP";
    }

    /** SDK 客户端侧超限异常的类名，见 map() 里的说明。 */
    static final String MAX_SIZE_EXCEEDED = "MaxSizeExceededException";

    /** 下游错误摘要的长度上限，防止一条超长错误撑爆日志和 error_message 列。 */
    private static final int MAX_SUMMARY_LENGTH = 200;

    static String summarize(String message) {
        if (message == null || message.isBlank()) {
            return "downstream returned an error";
        }
        String flattened = message.replaceAll("\\s+", " ").trim();
        return flattened.length() <= MAX_SUMMARY_LENGTH
                ? flattened
                : flattened.substring(0, MAX_SUMMARY_LENGTH) + "...";
    }

}
