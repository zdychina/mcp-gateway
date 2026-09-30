package com.mcpgateway.mcpserver;

import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.SyncStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Agent instructions 的组合规则（网关描述 + 有描述的子 MCP）。 */
class AgentInstructionsTest {

    private static Gateway gateway(String description) {
        return new Gateway("gw", "网关", "gw", description, "hash", Instant.EPOCH, Instant.EPOCH);
    }

    private static DownstreamMcp downstream(String name, String originalDescription, String customDescription) {
        return new DownstreamMcp("id-" + name, "gw", name, DownstreamMcp.TYPE_STREAMABLE_HTTP,
                "https://example.com/mcp/" + name, null,
                originalDescription, customDescription,
                SyncStatus.SUCCESS, Instant.EPOCH, null, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    @DisplayName("网关描述 + 有描述的子 MCP 组合成两段式 instructions")
    void composesGatewayDescriptionWithDescribedDownstreams() {
        String instructions = AgentInstructions.compose(
                gateway("网关用途说明"),
                List.of(downstream("kb_a", "知识库检索", null),
                        downstream("kb_b", null, "网页搜索")));

        assertThat(instructions).isEqualTo("网关用途说明\n\n子 MCP：\n- kb_a：知识库检索\n- kb_b：网页搜索");
    }

    @Test
    @DisplayName("没有任何子 MCP 有描述时，instructions 就是网关描述本身")
    void fallsBackToGatewayDescriptionAlone() {
        String instructions = AgentInstructions.compose(
                gateway("网关用途说明"),
                List.of(downstream("kb_a", null, null), downstream("kb_b", null, "  ")));

        assertThat(instructions).isEqualTo("网关用途说明");
    }

    @Test
    @DisplayName("网关没有描述但子 MCP 有时，直接以子 MCP 清单开头")
    void listsDownstreamsWithoutGatewayDescription() {
        String instructions = AgentInstructions.compose(
                gateway(null),
                List.of(downstream("kb_a", "知识库检索", null)));

        assertThat(instructions).isEqualTo("子 MCP：\n- kb_a：知识库检索");
    }

    @Test
    @DisplayName("两边都没有描述时返回 null")
    void nullWhenNothingToSay() {
        assertThat(AgentInstructions.compose(gateway("  "),
                List.of(downstream("kb_a", null, null)))).isNull();
        assertThat(AgentInstructions.compose(gateway(null), List.of())).isNull();
    }

    @Test
    @DisplayName("自定义描述非空时完全替换原始描述，空白时回退原始")
    void customDescriptionOverridesOriginal() {
        assertThat(downstream("kb_a", "原始", "自定义").effectiveDescription()).isEqualTo("自定义");
        assertThat(downstream("kb_a", "原始", " ").effectiveDescription()).isEqualTo("原始");
    }

    @Test
    @DisplayName("多行描述原样嵌入，条目顺序跟随传入列表")
    void keepsMultilineDescriptionsAndGivenOrder() {
        String instructions = AgentInstructions.compose(
                gateway("网关"),
                List.of(downstream("kb_b", "第二行\n第三行", null),
                        downstream("kb_a", "第一条", null)));

        assertThat(instructions).isEqualTo("网关\n\n子 MCP：\n- kb_b：第二行\n第三行\n- kb_a：第一条");
    }
}
