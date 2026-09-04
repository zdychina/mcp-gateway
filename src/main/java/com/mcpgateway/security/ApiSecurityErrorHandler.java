package com.mcpgateway.security;

import com.mcpgateway.api.ApiErrorWriter;
import com.mcpgateway.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 过滤器链里的 401 / 403 出口。
 *
 * {@code GlobalExceptionHandler} 挂在 DispatcherServlet 之后，而认证与 CSRF 失败都发生在
 * 过滤器链里，走不到它。这个类补上那一段，写出与管理 API 完全一致的 {@code ApiResponse} 信封 ——
 * 前端只有一条解析路径，返回 HTML 错误页会让 401 变成一句莫名其妙的 INVALID_RESPONSE。
 *
 * 两个身份合在一个类里是因为它们只差一个错误码，分成两个文件反而更难看出这一点。
 */
class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ApiErrorWriter errorWriter;

    ApiSecurityErrorHandler(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    /**
     * 未登录、会话过期，或访问了 denyAll 覆盖的路径。
     *
     * 三种情况给同一个响应：区分它们对合法用户没有价值，对探测者却是免费的信息。
     * 也不发 {@code WWW-Authenticate} —— 那会让浏览器弹出原生的 Basic 认证框，
     * 而登录页是前端自己渲染的。
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        this.errorWriter.write(response, ErrorCode.UNAUTHORIZED, "authentication required");
    }

    /** 已登录但被拒。当前唯一的来源是 CSRF 令牌缺失或不匹配。 */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        this.errorWriter.write(response, ErrorCode.FORBIDDEN, "request rejected");
    }
}
