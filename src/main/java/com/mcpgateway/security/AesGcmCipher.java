package com.mcpgateway.security;

import com.mcpgateway.config.GatewayProperties;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-GCM 加解密原语，用于子 MCP headers 落库（需求 12.2）。
 *
 * 密文格式：Base64( 版本字节 || 12 字节 IV || 密文+16 字节认证标签 )。
 * 版本字节留给将来换算法或轮换主密钥时做区分，当前恒为 1。
 */
@Component
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int KEY_LENGTH_BYTES = 32;

    private static final int IV_LENGTH_BYTES = 12;

    private static final int TAG_LENGTH_BITS = 128;

    private static final byte FORMAT_VERSION = 1;

    private final SecretKey key;

    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(GatewayProperties properties) {
        this.key = loadKey(properties.getSecurity().getMasterKey());
    }

    /**
     * 需求 12.1：主密钥不得硬编码，只能来自环境变量。缺失或长度不对时直接让应用启动失败，
     * 而不是退化成弱加密或明文落库。
     */
    private static SecretKey loadKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "MCP_GATEWAY_MASTER_KEY is not configured; set it to a Base64-encoded 32-byte key");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalStateException("MCP_GATEWAY_MASTER_KEY is not valid Base64");
        }
        if (raw.length != KEY_LENGTH_BYTES) {
            // 不要把实际长度以外的任何信息写进异常。
            throw new IllegalStateException(
                    "MCP_GATEWAY_MASTER_KEY must decode to exactly " + KEY_LENGTH_BYTES + " bytes");
        }
        SecretKey secretKey = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
        return secretKey;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            this.random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(1 + IV_LENGTH_BYTES + ciphertext.length);
            buffer.put(FORMAT_VERSION).put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        }
        catch (GeneralSecurityException ex) {
            // 明文可能是凭证，异常里绝不能带上它。
            throw new GatewayException(ErrorCode.INTERNAL_ERROR, "failed to encrypt downstream credentials", ex);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] blob = Base64.getDecoder().decode(encoded);
            if (blob.length < 1 + IV_LENGTH_BYTES + 1 || blob[0] != FORMAT_VERSION) {
                throw new GatewayException(ErrorCode.INTERNAL_ERROR, "stored credential blob is malformed");
            }
            byte[] iv = Arrays.copyOfRange(blob, 1, 1 + IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(blob, 1 + IV_LENGTH_BYTES, blob.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, this.key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException | GeneralSecurityException ex) {
            // 认证失败通常意味着主密钥被换了，或者数据被改过。
            throw new GatewayException(ErrorCode.INTERNAL_ERROR, "failed to decrypt downstream credentials", ex);
        }
    }
}
