package com.mcpgateway.mcpserver;

import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;

import java.util.List;

/**
 * 把网关描述和有描述的子 MCP 组合成对外 MCP initialize 结果里的 instructions。
 *
 * MCP 协议只有服务级一个 instructions 通道，子 MCP 的说明只能并进来：
 * 网关描述在前，之后跟一段"子 MCP"清单。没有子 MCP 有描述时，组合结果
 * 就是网关描述本身，与只有网关描述时的旧行为完全一致。
 */
public final class AgentInstructions {

    private AgentInstructions() {
    }

    /**
     * @param downstreams 已按名称排序的子 MCP 列表（findByGatewayId 的自然顺序），
     *                    组合结果因此是确定性的
     * @return 组合后的 instructions；两边都没有内容时返回 null
     */
    public static String compose(Gateway gateway, List<DownstreamMcp> downstreams) {
        String gatewayDescription = normalize(gateway.description());

        // 只列有生效描述的子 MCP：原始和自定义都为空的列出来只会是噪音。
        List<String> entries = downstreams.stream()
                .map(downstream -> {
                    String description = normalize(downstream.effectiveDescription());
                    return description == null ? null
                            : "- " + downstream.name() + "：" + description;
                })
                .filter(entry -> entry != null)
                .toList();

        if (gatewayDescription == null && entries.isEmpty()) {
            return null;
        }
        if (entries.isEmpty()) {
            return gatewayDescription;
        }
        if (gatewayDescription == null) {
            return joinSection(entries);
        }
        return gatewayDescription + "\n\n" + joinSection(entries);
    }

    private static String joinSection(List<String> entries) {
        return "子 MCP：\n" + String.join("\n", entries);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
