package com.mcpgateway.mcpserver;

import com.mcpgateway.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * instructions 模板的启动期校验（GatewayMcpRegistry 构造器里的 fail-fast）。
 *
 * 与 AdminAccountTest 守的是同一条线：坏配置宁可让应用起不来，也不要把字面
 * {@code {{...}}} 静默发给 Agent、等有人从 Agent 行为倒查回来。
 */
class GatewayMcpRegistryTest {

    private static GatewayProperties propertiesWithTemplate(String template) {
        GatewayProperties properties = new GatewayProperties();
        properties.getServer().setAgentInstructionsTemplate(template);
        return properties;
    }

    // 构造器目前只做赋值 + 模板校验，传 null 依赖是安全的；
    // 哪天构造器多了副作用，这里就得换成真的依赖实例。
    private static void construct(GatewayProperties properties) {
        new GatewayMcpRegistry(null, null, properties, null, null, null);
    }

    @Test
    @DisplayName("模板含未知占位符时应用起不来，文案点名环境变量")
    void refusesToStartOnUnknownPlaceholder() {
        assertThatThrownBy(() -> construct(propertiesWithTemplate("{{nope}}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_GATEWAY_SERVER_AGENT_INSTRUCTIONS_TEMPLATE")
                .hasMessageContaining("{{nope}}")
                .hasMessageContaining("{{gatewayDescription}}")
                .hasMessageContaining("{{downstreams}}");
    }

    @Test
    @DisplayName("模板残缺（少右括号）同样拒绝启动 —— 它只会被当字面文本发出去")
    void refusesToStartOnMalformedPlaceholder() {
        assertThatThrownBy(() -> construct(propertiesWithTemplate("网关说明\n\n{{downstreams")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    @DisplayName("默认模板、空白模板、合法自定义模板都能正常构造")
    void constructsFineWithValidTemplates() {
        assertThatCode(() -> construct(new GatewayProperties())).doesNotThrowAnyException();
        assertThatCode(() -> construct(propertiesWithTemplate(null))).doesNotThrowAnyException();
        assertThatCode(() -> construct(propertiesWithTemplate("  "))).doesNotThrowAnyException();
        assertThatCode(() -> construct(propertiesWithTemplate("{{ gatewayDescription }} — {{downstreams}}")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GatewayProperties 的字段默认值就是内置默认模板 —— 单一事实源")
    void propertiesDefaultIsTheBuiltInTemplate() {
        GatewayProperties properties = new GatewayProperties();

        assertThat(properties.getServer().getAgentInstructionsTemplate())
                .isEqualTo(AgentInstructions.DEFAULT_TEMPLATE);
    }
}
