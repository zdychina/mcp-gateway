package com.mcpgateway.api;

import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.lang.reflect.Method;
import org.springframework.core.MethodParameter;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("业务异常按错误码映射到对应 HTTP 状态")
    void mapsGatewayExceptionToItsHttpStatus() {
        ResponseEntity<ApiResponse<Void>> response = this.handler.handleGatewayException(
                GatewayException.of(ErrorCode.MCP_SERVER_LIMIT_EXCEEDED, "at most 3 downstream MCP servers"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("MCP_SERVER_LIMIT_EXCEEDED");
        assertThat(response.getBody().error().message()).isEqualTo("at most 3 downstream MCP servers");
    }

    @Test
    @DisplayName("5xx 业务异常同样返回稳定错误码，不带堆栈")
    void serverSideGatewayExceptionStaysOpaque() {
        ResponseEntity<ApiResponse<Void>> response = this.handler.handleGatewayException(
                new GatewayException(ErrorCode.DOWNSTREAM_INIT_FAILED, "downstream init failed",
                        new IllegalStateException("connection refused to 10.0.0.5:8443")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().error().message()).isEqualTo("downstream init failed");
        // cause 只进日志，不能出现在响应里
        assertThat(response.getBody().error().message()).doesNotContain("10.0.0.5");
    }

    @Test
    @DisplayName("需求 §8：数据库异常折叠为 INTERNAL_ERROR，SQL 与约束名不外泄")
    void hidesDataAccessDetails() {
        ResponseEntity<ApiResponse<Void>> response = this.handler.handleDataAccess(
                new DuplicateKeyException("Unique index or primary key violation: UK_MCP_GATEWAY_SLUG"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().error().message()).isEqualTo("internal error");
        assertThat(response.getBody().error().message()).doesNotContain("UK_MCP_GATEWAY_SLUG");
    }

    @Test
    @DisplayName("未预期异常一律折叠为 INTERNAL_ERROR，不回显异常消息")
    void hidesUnexpectedExceptionDetails() {
        ResponseEntity<ApiResponse<Void>> response = this.handler.handleUnexpected(
                new RuntimeException("master key /etc/secrets/mcp.key unreadable"));

        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().error().message()).doesNotContain("mcp.key");
    }

    @Test
    @DisplayName("请求体不是合法 JSON 时不回显请求体内容")
    void doesNotEchoUnreadableBody() {
        ResponseEntity<ApiResponse<Void>> response = this.handler.handleUnreadableBody(
                new HttpMessageNotReadableException("bad json: {\"Authorization\":\"Bearer sk-leak\"}",
                        (org.springframework.http.HttpInputMessage) null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().error().message()).doesNotContain("sk-leak");
    }

    @Test
    @DisplayName("字段校验失败只回字段名和约束描述，不回提交的值")
    void reportsFieldNamesWithoutSubmittedValues() throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "createGatewayRequest");
        bindingResult.addError(new FieldError("createGatewayRequest", "slug",
                "sk-secret-value", false, null, null, "只能包含字母、数字、短横线和下划线"));

        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", String.class);
        ResponseEntity<ApiResponse<Void>> response = this.handler.handleValidation(
                new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().error().message())
                .contains("slug")
                .contains("只能包含字母")
                .doesNotContain("sk-secret-value");
    }

    @Test
    @DisplayName("未知路径返回 404 而不是 500 —— 兜底分支不能把 404 吃成内部错误")
    void reportsUnknownEndpointAsNotFound() {
        ResponseEntity<ApiResponse<Void>> byHandler = this.handler.handleNotFound(
                new NoHandlerFoundException("GET", "/api/nope", org.springframework.http.HttpHeaders.EMPTY));
        assertThat(byHandler.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(byHandler.getBody().error().code()).isEqualTo("ENDPOINT_NOT_FOUND");

        ResponseEntity<ApiResponse<Void>> byResource = this.handler.handleNotFound(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "/actuator/env"));
        assertThat(byResource.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(byResource.getBody().error().code()).isEqualTo("ENDPOINT_NOT_FOUND");
    }

    @Test
    @DisplayName("方法不支持返回 405，同样不落到兜底分支")
    void reportsMethodNotAllowed() {
        ResponseEntity<ApiResponse<Void>> response = this.handler.handleMethodNotSupported(
                new org.springframework.web.HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().error().code()).isEqualTo("INVALID_REQUEST");
    }

    @SuppressWarnings("unused")
    private void dummy(String argument) {
    }
}
