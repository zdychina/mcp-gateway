package com.mcpgateway.error;

import org.springframework.http.HttpStatus;

/**
 * 网关稳定错误码。前 12 个直接对应需求文档 §11，其余是管理 API 必需的补充
 * （§11 原文为"至少定义以下稳定错误"）。
 *
 * 错误码是对外契约的一部分：只能新增，不能改名或改含义。
 */
public enum ErrorCode {

    // ------------------------------------------------------------ §11 必备

    /** 子 MCP JSON 格式错误。 */
    INVALID_MCP_CONFIG(HttpStatus.BAD_REQUEST),

    /** 子 MCP 数量超过 3。 */
    MCP_SERVER_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST),

    /** 不支持的传输类型（stdio / SSE 等）。 */
    UNSUPPORTED_TRANSPORT(HttpStatus.BAD_REQUEST),

    /** 子 MCP 初始化失败。 */
    DOWNSTREAM_INIT_FAILED(HttpStatus.BAD_GATEWAY),

    /** 工具同步失败。 */
    DOWNSTREAM_SYNC_FAILED(HttpStatus.BAD_GATEWAY),

    /** 聚合工具名不合法或超长。 */
    INVALID_TOOL_NAME(HttpStatus.BAD_REQUEST),

    /** 工具不存在。 */
    TOOL_NOT_FOUND(HttpStatus.NOT_FOUND),

    /**
     * 工具已停用。
     *
     * 注意 W0 探针结论：对 Agent 的 MCP 端点而言，停用工具已从 SDK 的工具目录移除，
     * SDK 会返回它自己的 "Unknown tool" 错误，网关无法在 MCP 协议层区分本码与
     * {@link #TOOL_NOT_FOUND}。本码只用于管理 API 和 tool_call_record。
     */
    TOOL_DISABLED(HttpStatus.NOT_FOUND),

    /** 工具参数不满足 Schema。 */
    INVALID_TOOL_ARGUMENTS(HttpStatus.BAD_REQUEST),

    /** 下游调用超时。 */
    DOWNSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),

    /** 下游 MCP 返回错误。 */
    DOWNSTREAM_ERROR(HttpStatus.BAD_GATEWAY),

    /** 网关内部错误。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    // -------------------------------------------------------- 管理 API 补充

    /** 请求体或路径参数不合法。 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),

    /** 请求的路径不存在。 */
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 网关不存在。 */
    GATEWAY_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 网关 slug 已被占用（§6.1.2）。 */
    DUPLICATE_GATEWAY_SLUG(HttpStatus.CONFLICT),

    /** 同一网关内子 MCP 名称重复（§6.2.2）。 */
    DUPLICATE_DOWNSTREAM_NAME(HttpStatus.CONFLICT),

    /** 子 MCP 不存在。 */
    DOWNSTREAM_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 调用记录不存在，或不属于该网关（需求 FR-06.5 的查询接口）。 */
    CALL_RECORD_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 网关访问令牌缺失或不正确（§4.3）；管理端未登录或会话已过期（§12.8）。 */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),

    /**
     * 已认证但请求被拒绝。当前只有一个来源：CSRF 令牌缺失或不匹配。
     *
     * 与 {@link #UNAUTHORIZED} 分开，前端才能区分"该重新登录"和"重取令牌重试"，
     * 否则一次 CSRF 校验失败会把用户莫名其妙地踢回登录页。
     */
    FORBIDDEN(HttpStatus.FORBIDDEN),

    /** 请求体或下游响应体超过配置上限（§12.9）。 */
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),

    /**
     * 管理端登录失败次数过多，该来源已被临时锁定（见 LoginThrottle）。
     *
     * 与 {@link #UNAUTHORIZED} 分开是有意的：告诉对方"你被限速了"不泄漏任何东西，
     * 而运维在排查"密码明明是对的却进不去"时需要能一眼看出是限速而不是口令错。
     */
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return this.httpStatus;
    }
}
