package com.mcpgateway.mcpserver;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 需求 12.6 的 Origin 校验。 */
class OriginValidatorTest {

    private static Map<String, List<String>> headers(String name, String value) {
        return Map.of(name, List.of(value));
    }

    @Test
    @DisplayName("没有 Origin 头时放行 —— Agent 之类的非浏览器客户端不会带这个头")
    void allowsRequestsWithoutOrigin() {
        var validator = new OriginValidator(List.of());

        assertThatCode(() -> validator.validateHeaders(Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateHeaders(null)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateHeaders(headers("Accept", "application/json")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("本机部署默认列表为空，任何浏览器来源都被拒")
    void rejectsAnyOriginWhenNothingIsAllowed() {
        var validator = new OriginValidator(List.of());

        assertThatThrownBy(() -> validator.validateHeaders(headers("Origin", "http://evil.example.com")))
                .isInstanceOf(ServerTransportSecurityException.class)
                .extracting(ex -> ((ServerTransportSecurityException) ex).getStatusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("配置的来源放行，其他一律拒绝")
    void allowsOnlyConfiguredOrigins() {
        var validator = new OriginValidator(List.of("http://localhost:8080", "https://admin.internal"));

        assertThatCode(() -> validator.validateHeaders(headers("Origin", "http://localhost:8080")))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateHeaders(headers("Origin", "https://admin.internal")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateHeaders(headers("Origin", "http://localhost:9999")))
                .isInstanceOf(ServerTransportSecurityException.class);
    }

    @Test
    @DisplayName("header 名和 Origin 值的大小写都不敏感")
    void matchingIsCaseInsensitive() {
        var validator = new OriginValidator(List.of("HTTP://Localhost:8080"));

        assertThatCode(() -> validator.validateHeaders(headers("origin", "http://localhost:8080")))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateHeaders(headers("ORIGIN", "HTTP://LOCALHOST:8080")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("拒绝时不把收到的 Origin 反射进错误信息")
    void doesNotReflectTheRejectedOrigin() {
        var validator = new OriginValidator(List.of("http://localhost:8080"));

        assertThatThrownBy(() -> validator.validateHeaders(headers("Origin", "http://attacker.example.com")))
                .hasMessage("origin is not allowed")
                .hasMessageNotContaining("attacker.example.com");
    }

    @Test
    @DisplayName("配置里的空白项被忽略，不会变成一个可匹配的空来源")
    void ignoresBlankConfiguredOrigins() {
        var validator = new OriginValidator(java.util.Arrays.asList("  ", null, "http://ok.example.com"));

        assertThatCode(() -> validator.validateHeaders(headers("Origin", "http://ok.example.com")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateHeaders(headers("Origin", "  ")))
                .isInstanceOf(ServerTransportSecurityException.class);
    }
}
