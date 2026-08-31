package com.mcpgateway.downstream;

/**
 * 从子 MCP 的 {@code tools/list} 取回的一条工具定义，Schema 已序列化成 JSON 文本。
 *
 * 之所以在这里就转成字符串而不是一路传 {@code McpSchema.Tool}：合并算法要做的是
 * "把协议字段整体覆盖到快照行上"，用文本比对和落库都最直接，也让合并逻辑不依赖 SDK 类型。
 */
public record FetchedTool(
        String originalName,
        String description,
        String inputSchemaJson,
        String outputSchemaJson,
        String annotationsJson,
        String definitionHash) {
}
