package com.mcpgateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.TestMasterKey;
import com.mcpgateway.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamHeaderCodecTest {

    private final DownstreamHeaderCodec codec = newCodec();

    private static DownstreamHeaderCodec newCodec() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setMasterKey(TestMasterKey.BASE64);
        return new DownstreamHeaderCodec(new AesGcmCipher(properties), new ObjectMapper());
    }

    @Test
    @DisplayName("headers 往返保持内容和顺序")
    void roundTripsHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer sk-secret-value");
        headers.put("X-Tenant-Id", "acme");

        String stored = this.codec.encrypt(headers);

        assertThat(this.codec.decrypt(stored)).containsExactlyEntriesOf(headers);
    }

    @Test
    @DisplayName("密文里不出现任何明文片段")
    void ciphertextLeaksNothing() {
        String stored = this.codec.encrypt(Map.of("Authorization", "Bearer sk-secret-value"));

        assertThat(stored)
                .doesNotContain("Authorization")
                .doesNotContain("Bearer")
                .doesNotContain("sk-secret-value");
    }

    @Test
    @DisplayName("空 headers 存 null，不加密一个空对象")
    void treatsEmptyHeadersAsNull() {
        assertThat(this.codec.encrypt(null)).isNull();
        assertThat(this.codec.encrypt(Map.of())).isNull();
        assertThat(this.codec.decrypt(null)).isEmpty();
        assertThat(this.codec.decrypt("")).isEmpty();
    }

    @Test
    @DisplayName("遮罩视图只暴露 header 名称，不暴露任何真实值")
    void maskedViewHidesEveryValue() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer sk-secret-value");
        headers.put("X-Tenant-Id", "acme");
        String stored = this.codec.encrypt(headers);

        Map<String, String> masked = this.codec.maskedView(stored);

        assertThat(masked).containsOnlyKeys("Authorization", "X-Tenant-Id");
        assertThat(masked.values()).containsOnly(SensitiveDataMasker.MASK);
        // 连非敏感名的值也一并遮罩：任何 header 都可能被子 MCP 当成凭证。
        assertThat(masked.get("X-Tenant-Id")).isNotEqualTo("acme");
    }
}
