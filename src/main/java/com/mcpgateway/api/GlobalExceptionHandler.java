package com.mcpgateway.api;

import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 管理 API 的统一异常出口。
 *
 * 需求 §8：失败时返回稳定错误码和可理解消息，不返回 Java 堆栈。
 * 因此这里的每条分支都只把预先构造好的安全文案写进响应，异常本身只进服务端日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ApiResponse<Void>> handleGatewayException(GatewayException ex) {
        ErrorCode code = ex.errorCode();
        if (code.httpStatus().is5xxServerError()) {
            log.error("gateway error {}: {}", code, ex.getMessage(), ex);
        }
        else {
            log.warn("gateway error {}: {}", code, ex.getMessage());
        }
        return respond(code, ex.getMessage());
    }

    /** @Valid 校验失败。只回字段名和约束描述，不回提交的值。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        return respond(ErrorCode.INVALID_REQUEST, detail.isEmpty() ? "request validation failed" : detail);
    }

    /** 请求体不是合法 JSON。不回显请求体内容，它可能带凭证。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("unreadable request body: {}", ex.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, "request body is not valid JSON");
    }

    /**
     * 路径不存在。
     *
     * 这两个分支必须显式写出来：下面那个兜底的 Exception 处理器会连 Spring 抛出的
     * NoResourceFoundException 一起吃掉，把所有 404 变成 500 INTERNAL_ERROR ——
     * 既误导排查，也让人以为服务坏了。
     */
    @ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
        return respond(ErrorCode.ENDPOINT_NOT_FOUND, "no such endpoint");
    }

    /** 方法不被支持时返回 405，同样不能落到兜底分支里变成 500。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, "method not allowed"));
    }

    /** 数据库异常一律折叠为 INTERNAL_ERROR，SQL 和约束名不外泄。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(DataAccessException ex) {
        log.error("data access failure", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "internal error");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("unexpected failure", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "internal error");
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.fail(code, message));
    }
}
