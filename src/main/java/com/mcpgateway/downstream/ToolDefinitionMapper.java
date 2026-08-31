package com.mcpgateway.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 把 SDK 的 {@link McpSchema.Tool} 转成落库用的 {@link FetchedTool}。 */
@Component
public class ToolDefinitionMapper {

    private final ObjectMapper objectMapper;

    public ToolDefinitionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<FetchedTool> toFetchedTools(List<McpSchema.Tool> tools, String downstreamName) {
        return tools.stream().map(tool -> toFetchedTool(tool, downstreamName)).toList();
    }

    public FetchedTool toFetchedTool(McpSchema.Tool tool, String downstreamName) {
        String inputSchema = writeJson(tool.inputSchema(), downstreamName);
        String outputSchema = writeJson(tool.outputSchema(), downstreamName);
        String annotations = writeJson(tool.annotations(), downstreamName);
        String hash = definitionHash(tool.name(), tool.description(), inputSchema, outputSchema, annotations);
        return new FetchedTool(tool.name(), tool.description(), inputSchema, outputSchema, annotations, hash);
    }

    private String writeJson(Object value, String downstreamName) {
        if (value == null) {
            return null;
        }
        try {
            return this.objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new GatewayException(ErrorCode.DOWNSTREAM_SYNC_FAILED,
                    downstreamName + ": downstream returned a tool definition that could not be stored", ex);
        }
    }

    /**
     * 协议字段的指纹。同步时用它判断这条工具定义有没有真的变化，
     * 没变就跳过 UPDATE，避免每次同步都无谓地推高 updated_at。
     */
    static String definitionHash(String name, String description, String inputSchema, String outputSchema,
            String annotations) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 用 NUL 分隔而不是空格：描述里本来就可能有空格，用它当分隔符会让不同的
            // 字段组合算出同一个指纹，从而漏掉一次本该发生的更新。
            String payload = String.join("\u0000",
                    nullSafe(name), nullSafe(description),
                    nullSafe(inputSchema), nullSafe(outputSchema), nullSafe(annotations));
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new GatewayException(ErrorCode.INTERNAL_ERROR, "hash algorithm unavailable", ex);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
