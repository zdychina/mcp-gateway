package com.mcpgateway.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子 MCP headers 的落库编解码：整个 header 映射序列化成 JSON 后作为一个整体加密
 * （对应 downstream_mcp.encrypted_headers_json）。
 *
 * 整体加密而非逐值加密，是因为 header 名称本身也可能泄露信息（例如 X-Tenant-Id），
 * 而且整体加密只需一次 GCM 运算。
 */
@Component
public class DownstreamHeaderCodec {

    private static final TypeReference<LinkedHashMap<String, String>> HEADER_MAP_TYPE = new TypeReference<>() {
    };

    private final AesGcmCipher cipher;

    private final ObjectMapper objectMapper;

    public DownstreamHeaderCodec(AesGcmCipher cipher, ObjectMapper objectMapper) {
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    /** 明文 header 映射 -> 落库密文。空映射存 null，避免加密一个空对象。 */
    public String encrypt(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return this.cipher.encrypt(this.objectMapper.writeValueAsString(new LinkedHashMap<>(headers)));
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // 不要把 headers 内容写进异常。
            throw new GatewayException(ErrorCode.INTERNAL_ERROR, "failed to serialize downstream headers", ex);
        }
    }

    /** 落库密文 -> 明文 header 映射。仅供实际发起下游调用时使用。 */
    public Map<String, String> decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return Map.of();
        }
        String json = this.cipher.decrypt(encrypted);
        try {
            Map<String, String> headers = this.objectMapper.readValue(json, HEADER_MAP_TYPE);
            return headers == null ? Map.of() : headers;
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new GatewayException(ErrorCode.INTERNAL_ERROR, "failed to parse stored downstream headers", ex);
        }
    }

    /**
     * 需求 12.4：配置查询接口只返回 header 名称及遮罩值。
     * 这是 API 层唯一允许触碰 headers 的入口。
     */
    public Map<String, String> maskedView(String encrypted) {
        return SensitiveDataMasker.maskValues(decrypt(encrypted));
    }
}
