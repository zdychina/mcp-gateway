package com.mcpgateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    @DisplayName("需求 12.4：所有 header 值一律遮罩，名称与顺序保留")
    void masksEveryValue() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer sk-secret");
        headers.put("X-Tenant-Id", "acme");
        headers.put("Accept-Language", "zh-CN");

        Map<String, String> masked = SensitiveDataMasker.maskValues(headers);

        assertThat(masked.keySet()).containsExactly("Authorization", "X-Tenant-Id", "Accept-Language");
        assertThat(masked.values()).containsOnly(SensitiveDataMasker.MASK);
    }

    @Test
    @DisplayName("空输入返回空映射而不是 null")
    void handlesEmptyInput() {
        assertThat(SensitiveDataMasker.maskValues(null)).isEmpty();
        assertThat(SensitiveDataMasker.maskValues(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("已知凭证 header 判断忽略大小写和空白")
    void recognisesWellKnownCredentialHeaders() {
        assertThat(SensitiveDataMasker.isWellKnownCredentialHeader("Authorization")).isTrue();
        assertThat(SensitiveDataMasker.isWellKnownCredentialHeader("  COOKIE  ")).isTrue();
        assertThat(SensitiveDataMasker.isWellKnownCredentialHeader("x-api-key")).isTrue();
        assertThat(SensitiveDataMasker.isWellKnownCredentialHeader("Accept")).isFalse();
        assertThat(SensitiveDataMasker.isWellKnownCredentialHeader(null)).isFalse();
    }

    @Test
    @DisplayName("需求 12.5：日志描述只出现 header 名称，不出现任何值")
    void logDescriptionNeverContainsValues() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer sk-secret");
        headers.put("X-Tenant-Id", "acme");

        String description = SensitiveDataMasker.describeForLog(headers);

        assertThat(description)
                .contains("Authorization")
                .contains("X-Tenant-Id")
                .doesNotContain("sk-secret")
                .doesNotContain("acme");
        assertThat(SensitiveDataMasker.describeForLog(null)).isEqualTo("{}");
        assertThat(SensitiveDataMasker.describeForLog(Map.of())).isEqualTo("{}");
    }

    @Test
    @DisplayName("需求 FR-05.3：令牌占位符只保留前 4 位，短串整体遮罩")
    void masksTokenForPlaceholderDisplay() {
        assertThat(SensitiveDataMasker.maskToken("mcpgw_abcdefghijklmn"))
                .startsWith("mcpg")
                .endsWith(SensitiveDataMasker.MASK)
                .doesNotContain("abcdefghijklmn");
        assertThat(SensitiveDataMasker.maskToken("short")).isEqualTo(SensitiveDataMasker.MASK);
        assertThat(SensitiveDataMasker.maskToken(null)).isEqualTo(SensitiveDataMasker.MASK);
    }
}
