package com.mcpgateway.security;

import com.mcpgateway.TestMasterKey;
import com.mcpgateway.config.GatewayProperties;
import com.mcpgateway.error.GatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCipherTest {

    private static AesGcmCipher cipherWithKey(String base64Key) {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setMasterKey(base64Key);
        return new AesGcmCipher(properties);
    }

    @Test
    @DisplayName("加解密往返保持原文，含非 ASCII 内容")
    void roundTripsPlaintext() {
        AesGcmCipher cipher = cipherWithKey(TestMasterKey.BASE64);
        String plaintext = "Bearer sk-test-令牌-éü";

        String encrypted = cipher.encrypt(plaintext);

        assertThat(encrypted).isNotNull().isNotEqualTo(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("同一明文两次加密得到不同密文（IV 随机）")
    void producesDistinctCiphertextsForSamePlaintext() {
        AesGcmCipher cipher = cipherWithKey(TestMasterKey.BASE64);

        String first = cipher.encrypt("same-secret");
        String second = cipher.encrypt("same-secret");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(cipher.decrypt(second));
    }

    @Test
    @DisplayName("密文被篡改时 GCM 认证失败，不会返回垃圾明文")
    void rejectsTamperedCiphertext() {
        AesGcmCipher cipher = cipherWithKey(TestMasterKey.BASE64);
        byte[] blob = Base64.getDecoder().decode(cipher.encrypt("secret"));
        blob[blob.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(blob);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("failed to decrypt");
    }

    @Test
    @DisplayName("换一把主密钥后无法解开旧密文")
    void cannotDecryptWithADifferentKey() {
        String encrypted = cipherWithKey(TestMasterKey.BASE64).encrypt("secret");
        AesGcmCipher other = cipherWithKey(TestMasterKey.generate());

        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(GatewayException.class);
    }

    @Test
    @DisplayName("主密钥缺失时启动即失败，不退化成明文落库")
    void failsFastWhenMasterKeyMissing() {
        assertThatThrownBy(() -> cipherWithKey(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_GATEWAY_MASTER_KEY is not configured");

        assertThatThrownBy(() -> cipherWithKey("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_GATEWAY_MASTER_KEY is not configured");
    }

    @Test
    @DisplayName("主密钥长度或编码不对时启动即失败")
    void failsFastWhenMasterKeyMalformed() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> cipherWithKey(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");

        assertThatThrownBy(() -> cipherWithKey("not-base64!!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid Base64");
    }

    @Test
    @DisplayName("null 明文原样返回 null，用于表示未配置 headers")
    void passesNullThrough() {
        AesGcmCipher cipher = cipherWithKey(TestMasterKey.BASE64);

        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }
}
