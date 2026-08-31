package com.mcpgateway.error;

/**
 * 网关业务异常。message 会原样返回给调用方，因此**只能**写已脱敏的内容：
 * 不得包含 header、访问令牌、子 MCP 凭证或堆栈（需求 6.6.7 / 12.5）。
 */
public class GatewayException extends RuntimeException {

    private final ErrorCode errorCode;

    public GatewayException(ErrorCode errorCode, String safeMessage) {
        super(safeMessage);
        this.errorCode = errorCode;
    }

    /**
     * cause 只用于服务端日志，不会进入 API 响应。
     */
    public GatewayException(ErrorCode errorCode, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return this.errorCode;
    }

    public static GatewayException of(ErrorCode errorCode, String safeMessage) {
        return new GatewayException(errorCode, safeMessage);
    }
}
