package com.mcpgateway.mcpserver;

import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.SyncStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Agent instructions 的模板渲染规则（默认模板 = 网关描述 + 有描述的子 MCP）。 */
class AgentInstructionsTest {

    /** 自定义模板用例共用的格式：标题换了、清单标题换了，覆盖两侧占位符。 */
    private static final String CUSTOM_TEMPLATE = "【{{gatewayDescription}}】\n\n可用子服务：\n{{downstreams}}";

    private static Gateway gateway(String description) {
        return new Gateway("gw", "网关", "gw", description, "hash", Instant.EPOCH, Instant.EPOCH);
    }

    private static DownstreamMcp downstream(String name, String originalDescription, String customDescription) {
        return new DownstreamMcp("id-" + name, "gw", name, DownstreamMcp.TYPE_STREAMABLE_HTTP,
                "https://example.com/mcp/" + name, null,
                originalDescription, customDescription,
                SyncStatus.SUCCESS, Instant.EPOCH, null, Instant.EPOCH, Instant.EPOCH);
    }

    /**
     * 既有用例统一走默认模板。下面所有"与旧版输出相等"的断言就是
     * DEFAULT_TEMPLATE 逐字节复刻 1.3 写死拼接的背书 —— 升级零影响全靠它们。
     */
    private static String compose(Gateway gateway, List<DownstreamMcp> downstreams) {
        return AgentInstructions.compose(AgentInstructions.DEFAULT_TEMPLATE, gateway, downstreams);
    }

    @Test
    @DisplayName("网关描述 + 有描述的子 MCP 组合成两段式 instructions")
    void composesGatewayDescriptionWithDescribedDownstreams() {
        String instructions = compose(
                gateway("网关用途说明"),
                List.of(downstream("kb_a", "知识库检索", null),
                        downstream("kb_b", null, "网页搜索")));

        assertThat(instructions).isEqualTo("网关用途说明\n\n子 MCP：\n- kb_a：知识库检索\n- kb_b：网页搜索");
    }

    @Test
    @DisplayName("没有任何子 MCP 有描述时，instructions 就是网关描述本身")
    void fallsBackToGatewayDescriptionAlone() {
        String instructions = compose(
                gateway("网关用途说明"),
                List.of(downstream("kb_a", null, null), downstream("kb_b", null, "  ")));

        assertThat(instructions).isEqualTo("网关用途说明");
    }

    @Test
    @DisplayName("网关没有描述但子 MCP 有时，直接以子 MCP 清单开头")
    void listsDownstreamsWithoutGatewayDescription() {
        String instructions = compose(
                gateway(null),
                List.of(downstream("kb_a", "知识库检索", null)));

        assertThat(instructions).isEqualTo("子 MCP：\n- kb_a：知识库检索");
    }

    @Test
    @DisplayName("两边都没有描述时返回 null")
    void nullWhenNothingToSay() {
        assertThat(compose(gateway("  "),
                List.of(downstream("kb_a", null, null)))).isNull();
        assertThat(compose(gateway(null), List.of())).isNull();
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
        String instructions = compose(
                gateway("网关"),
                List.of(downstream("kb_b", "第二行\n第三行", null),
                        downstream("kb_a", "第一条", null)));

        assertThat(instructions).isEqualTo("网关\n\n子 MCP：\n- kb_b：第二行\n第三行\n- kb_a：第一条");
    }

    @Test
    @DisplayName("自定义模板两侧占位符都渲染")
    void rendersCustomTemplateWithBothPlaceholders() {
        String instructions = AgentInstructions.compose(CUSTOM_TEMPLATE,
                gateway("网关用途说明"),
                List.of(downstream("kb_a", "知识库检索", null),
                        downstream("kb_b", null, "网页搜索")));

        assertThat(instructions)
                .isEqualTo("【网关用途说明】\n\n可用子服务：\n- kb_a：知识库检索\n- kb_b：网页搜索");
    }

    @Test
    @DisplayName("子 MCP 都没描述时，含 {{downstreams}} 的段落连同段内标题整段丢弃")
    void dropsParagraphWhosePlaceholderResolvesEmpty() {
        String instructions = AgentInstructions.compose(CUSTOM_TEMPLATE,
                gateway("网关用途说明"),
                List.of(downstream("kb_a", null, null)));

        assertThat(instructions).isEqualTo("【网关用途说明】");
    }

    @Test
    @DisplayName("网关没有描述时，含 {{gatewayDescription}} 的段落整段丢弃")
    void dropsParagraphWhenGatewayDescriptionMissing() {
        String instructions = AgentInstructions.compose(CUSTOM_TEMPLATE,
                gateway(null),
                List.of(downstream("kb_a", "知识库检索", null)));

        assertThat(instructions).isEqualTo("可用子服务：\n- kb_a：知识库检索");
    }

    @Test
    @DisplayName("自定义模板下两边都为空返回 null")
    void nullWhenCustomTemplateEverythingDropped() {
        assertThat(AgentInstructions.compose(CUSTOM_TEMPLATE,
                gateway(" "), List.of(downstream("kb_a", null, null)))).isNull();
    }

    @Test
    @DisplayName("两个占位符同一段落且其一为空时，整段（含另一侧内容）一起丢弃")
    void dropsWholeParagraphWhenOneOfTwoPlaceholdersEmpty() {
        String instructions = AgentInstructions.compose(
                "{{gatewayDescription}} 与 {{downstreams}}\n\n备用说明",
                gateway("网关用途说明"),
                List.of(downstream("kb_a", null, null)));

        assertThat(instructions).isEqualTo("备用说明");
    }

    @Test
    @DisplayName("不含占位符的纯文案模板原样输出（trim 后）")
    void passesStaticTemplateThrough() {
        String instructions = AgentInstructions.compose("  本网关不提供任何说明。  ",
                gateway("被无视的网关描述"),
                List.of(downstream("kb_a", "被无视的子 MCP 描述", null)));

        assertThat(instructions).isEqualTo("本网关不提供任何说明。");
    }

    @Test
    @DisplayName("同一占位符出现两次都完成替换")
    void substitutesRepeatedPlaceholders() {
        String instructions = AgentInstructions.compose(
                "{{gatewayDescription}}（{{gatewayDescription}}）\n\n{{downstreams}}",
                gateway("网关用途说明"),
                List.of(downstream("kb_a", "知识库检索", null)));

        assertThat(instructions).isEqualTo("网关用途说明（网关用途说明）\n\n- kb_a：知识库检索");
    }

    @Test
    @DisplayName("占位符花括号内侧的空白被容忍")
    void toleratesWhitespaceInsidePlaceholder() {
        String instructions = AgentInstructions.compose(
                "{{ gatewayDescription }}\n\n{{ downstreams }}",
                gateway("网关用途说明"),
                List.of(downstream("kb_a", "知识库检索", null)));

        assertThat(instructions).isEqualTo("网关用途说明\n\n- kb_a：知识库检索");
    }

    @Test
    @DisplayName("Windows 风格的 \\r\\n 同样分段，行尾统一归一成 \\n")
    void splitsParagraphsOnWindowsLineBreaks() {
        String instructions = AgentInstructions.compose(
                "【{{gatewayDescription}}】\r\n\r\n可用子服务：\r\n{{downstreams}}",
                gateway("网关用途说明"),
                List.of(downstream("kb_a", "知识库检索", null)));

        // 模板里的 \r\n（含段内单换行）渲染成 \n：一个 Windows 环境变量设置的模板
        // 不会因为行尾风格不同而产生另一份输出。
        assertThat(instructions).isEqualTo("【网关用途说明】\n\n可用子服务：\n- kb_a：知识库检索");
    }

    @Test
    @DisplayName("描述里的 $ 和 \\ 原样渲染，不被当作替换语法吃掉")
    void rendersDollarAndBackslashLiterally() {
        String instructions = AgentInstructions.compose(
                "{{gatewayDescription}}\n\n{{downstreams}}",
                gateway("价格 $5 起"),
                List.of(downstream("kb_a", "路径 C:\\tools 不转义", null)));

        assertThat(instructions).isEqualTo("价格 $5 起\n\n- kb_a：路径 C:\\tools 不转义");
    }

    @Test
    @DisplayName("模板为 null 或空白时回退默认模板")
    void blankTemplateFallsBackToDefault() {
        Gateway gateway = gateway("网关用途说明");
        List<DownstreamMcp> downstreams = List.of(downstream("kb_a", "知识库检索", null));

        assertThat(AgentInstructions.compose(null, gateway, downstreams))
                .isEqualTo(compose(gateway, downstreams));
        assertThat(AgentInstructions.compose("  \n\t ", gateway, downstreams))
                .isEqualTo(compose(gateway, downstreams));
    }

    @Test
    @DisplayName("validate 接受默认模板与空白模板")
    void validateAcceptsKnownAndBlankTemplates() {
        assertThatCode(() -> AgentInstructions.validate(AgentInstructions.DEFAULT_TEMPLATE))
                .doesNotThrowAnyException();
        assertThatCode(() -> AgentInstructions.validate(null)).doesNotThrowAnyException();
        assertThatCode(() -> AgentInstructions.validate("  ")).doesNotThrowAnyException();
        assertThatCode(() -> AgentInstructions.validate(CUSTOM_TEMPLATE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validate 拒绝未知与残缺占位符，错误信息点名环境变量和两个合法名字")
    void validateRejectsUnknownAndMalformedPlaceholders() {
        for (String bad : new String[] { "{{nope}}", "{{downstreams", "{{a b}}" }) {
            assertThatThrownBy(() -> AgentInstructions.validate(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MCP_GATEWAY_SERVER_AGENT_INSTRUCTIONS_TEMPLATE")
                    .hasMessageContaining("{{gatewayDescription}}")
                    .hasMessageContaining("{{downstreams}}");
        }
    }

    @Test
    @DisplayName("compose 与 validate 走同一套检查：坏模板在渲染入口就炸")
    void composeRejectsBadTemplateTheSameWay() {
        assertThatThrownBy(() -> AgentInstructions.compose("{{nope}}",
                gateway("网关用途说明"), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{{nope}}");
    }
}
