package com.mcpgateway.security;

import com.mcpgateway.error.GatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenServiceTest {

    private final AccessTokenService service = new AccessTokenService();

    @Test
    @DisplayName("生成的令牌带可识别前缀且每次都不同")
    void generatesUniquePrefixedTokens() {
        AccessTokenService.GeneratedToken first = this.service.generate();
        AccessTokenService.GeneratedToken second = this.service.generate();

        assertThat(first.token()).startsWith(AccessTokenService.TOKEN_PREFIX);
        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(first.hash()).isNotEqualTo(second.hash());
    }

    @Test
    @DisplayName("落库的是哈希而不是明文，且哈希不可逆推出明文")
    void storesHashNotPlaintext() {
        AccessTokenService.GeneratedToken generated = this.service.generate();

        assertThat(generated.hash())
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .doesNotContain(generated.token());
    }

    @Test
    @DisplayName("同一令牌哈希稳定，可用于校验")
    void hashIsStable() {
        AccessTokenService.GeneratedToken generated = this.service.generate();

        assertThat(this.service.hash(generated.token())).isEqualTo(generated.hash());
        assertThat(this.service.matches(generated.token(), generated.hash())).isTrue();
    }

    @Test
    @DisplayName("令牌不匹配、为空或哈希为空时一律拒绝")
    void rejectsMismatchedOrMissingTokens() {
        AccessTokenService.GeneratedToken generated = this.service.generate();

        assertThat(this.service.matches("mcpgw_wrong", generated.hash())).isFalse();
        assertThat(this.service.matches(null, generated.hash())).isFalse();
        assertThat(this.service.matches("  ", generated.hash())).isFalse();
        assertThat(this.service.matches(generated.token(), null)).isFalse();
        assertThat(this.service.matches(generated.token(), "")).isFalse();
    }

    @Test
    @DisplayName("轮换后旧令牌立即失效")
    void rotationInvalidatesTheOldToken() {
        AccessTokenService.GeneratedToken original = this.service.generate();
        AccessTokenService.GeneratedToken rotated = this.service.generate();

        assertThat(this.service.matches(original.token(), rotated.hash())).isFalse();
        assertThat(this.service.matches(rotated.token(), rotated.hash())).isTrue();
    }

    @Test
    @DisplayName("从 Authorization 头解析 Bearer 令牌，大小写不敏感")
    void extractsBearerToken() {
        assertThat(this.service.extractBearerToken("Bearer mcpgw_abc")).isEqualTo("mcpgw_abc");
        assertThat(this.service.extractBearerToken("bearer mcpgw_abc")).isEqualTo("mcpgw_abc");
        assertThat(this.service.extractBearerToken("  Bearer   mcpgw_abc  ")).isEqualTo("mcpgw_abc");
    }

    @Test
    @DisplayName("Authorization 头缺失或格式不对时返回 null")
    void returnsNullForUnusableAuthorizationHeader() {
        assertThat(this.service.extractBearerToken(null)).isNull();
        assertThat(this.service.extractBearerToken("")).isNull();
        assertThat(this.service.extractBearerToken("Basic abc")).isNull();
        assertThat(this.service.extractBearerToken("Bearer")).isNull();
        assertThat(this.service.extractBearerToken("Bearer    ")).isNull();
    }

    @Test
    @DisplayName("对空令牌求哈希直接报 UNAUTHORIZED，不产生一个可用的空哈希")
    void hashRejectsBlankToken() {
        assertThatThrownBy(() -> this.service.hash("")).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> this.service.hash(null)).isInstanceOf(GatewayException.class);
    }
}
