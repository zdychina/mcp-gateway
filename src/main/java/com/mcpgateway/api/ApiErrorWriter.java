package com.mcpgateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 从过滤器链里写出与 {@link ApiResponse} 完全一致的错误信封。
 *
 * 为什么需要这个类：{@link GlobalExceptionHandler} 挂在 DispatcherServlet 之后，而认证失败
 * 发生在 Spring Security 的过滤器链里，**根本走不到它**。不管的话浏览器收到的是 Boot 默认的
 * HTML 错误页，前端 client.ts 解析失败后只会抛一句莫名其妙的 INVALID_RESPONSE。
 *
 * 信封结构必须与 {@link ApiResponse} 逐字段一致 —— 前端只有一条解析路径，
 * 这里漂移一个字段名，401 就变成了"服务端返回了无法解析的响应"。
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param message 必须是预先构造好的安全文案。这里和 GlobalExceptionHandler 一样，
     *                绝不把异常内容写进响应。
     */
    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        this.objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(code, message));
    }
}
