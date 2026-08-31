package com.mcpgateway.repository;

import com.mcpgateway.AbstractDataTest;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.GatewayTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayToolRepositoryTest extends AbstractDataTest {

    @Autowired
    private GatewayRepository gateways;

    @Autowired
    private DownstreamMcpRepository downstreams;

    @Autowired
    private GatewayToolRepository tools;

    private Gateway gateway;

    private DownstreamMcp downstream;

    @BeforeEach
    void seed() {
        this.gateway = TestFixtures.gateway("tool-" + System.nanoTime());
        this.gateways.insert(this.gateway);
        this.downstream = TestFixtures.downstream(this.gateway.id(), "kb_a");
        this.downstreams.insert(this.downstream);
    }

    @Test
    @DisplayName("需求 6.4.4 / 6.4.5：重新同步覆盖协议字段，但保留启停状态和自定义描述")
    void resyncPreservesOperatorConfiguration() {
        GatewayTool tool = TestFixtures.tool(this.gateway.id(), this.downstream.id(), "kb_a", "search");
        this.tools.insert(tool);

        // 操作人做了两件事：停用工具，并覆盖了描述。
        Instant configuredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.tools.updateEnabled(tool.id(), false, configuredAt);
        this.tools.updateCustomDescription(tool.id(), "运营自定义描述", configuredAt);

        // 随后来了一次重新同步，下游的描述和 Schema 都变了。
        Instant syncedAt = configuredAt.plusSeconds(60);
        this.tools.updateProtocolFields(tool.id(), "下游新的原始描述",
                "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\"}", "{\"readOnlyHint\":true}", "hash-v2", syncedAt, syncedAt);

        GatewayTool reloaded = this.tools.findById(tool.id()).orElseThrow();
        assertThat(reloaded.originalDescription()).isEqualTo("下游新的原始描述");
        assertThat(reloaded.inputSchemaJson()).contains("properties");
        assertThat(reloaded.annotationsJson()).isEqualTo("{\"readOnlyHint\":true}");
        assertThat(reloaded.definitionHash()).isEqualTo("hash-v2");
        // 关键：这两项没有被同步覆盖。
        assertThat(reloaded.enabled()).isFalse();
        assertThat(reloaded.customDescription()).isEqualTo("运营自定义描述");
    }

    @Test
    @DisplayName("需求 6.5.5：自定义描述非空时替换原始描述，清空后回退")
    void effectiveDescriptionFallsBackToOriginal() {
        GatewayTool tool = TestFixtures.tool(this.gateway.id(), this.downstream.id(), "kb_a", "search");
        this.tools.insert(tool);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(this.tools.findById(tool.id()).orElseThrow().effectiveDescription())
                .isEqualTo("original description");

        this.tools.updateCustomDescription(tool.id(), "覆盖后的描述", now);
        assertThat(this.tools.findById(tool.id()).orElseThrow().effectiveDescription())
                .isEqualTo("覆盖后的描述");

        this.tools.updateCustomDescription(tool.id(), null, now);
        assertThat(this.tools.findById(tool.id()).orElseThrow().effectiveDescription())
                .isEqualTo("original description");

        // 空白串也算"未设置"，不能让 Agent 看到一个空描述。
        this.tools.updateCustomDescription(tool.id(), "   ", now);
        assertThat(this.tools.findById(tool.id()).orElseThrow().effectiveDescription())
                .isEqualTo("original description");
    }

    @Test
    @DisplayName("需求 FR-04：tools/list 只取已启用工具")
    void listsOnlyEnabledTools() {
        GatewayTool search = TestFixtures.tool(this.gateway.id(), this.downstream.id(), "kb_a", "search");
        GatewayTool ping = TestFixtures.tool(this.gateway.id(), this.downstream.id(), "kb_a", "ping");
        this.tools.insert(search);
        this.tools.insert(ping);

        this.tools.updateEnabled(ping.id(), false, Instant.now().truncatedTo(ChronoUnit.MICROS));

        assertThat(this.tools.findEnabledByGatewayId(this.gateway.id()))
                .extracting(GatewayTool::exposedName)
                .containsExactly("kb_a__search");
        assertThat(this.tools.findByGatewayId(this.gateway.id())).hasSize(2);
        assertThat(this.tools.countByGatewayIdAndEnabled(this.gateway.id(), true)).isEqualTo(1);
        assertThat(this.tools.countByGatewayIdAndEnabled(this.gateway.id(), false)).isEqualTo(1);
    }

    @Test
    @DisplayName("需求 6.3.1：按 (gateway_id, exposed_name) 精确定位路由目标")
    void looksUpRouteByExposedName() {
        GatewayTool tool = TestFixtures.tool(this.gateway.id(), this.downstream.id(), "kb_a", "search");
        this.tools.insert(tool);

        GatewayTool found = this.tools
                .findByGatewayIdAndExposedName(this.gateway.id(), "kb_a__search")
                .orElseThrow();
        assertThat(found.downstreamMcpId()).isEqualTo(this.downstream.id());
        assertThat(found.originalName()).isEqualTo("search");

        // 换个网关就查不到，路由不会跨网关串。
        assertThat(this.tools.findByGatewayIdAndExposedName(TestFixtures.id(), "kb_a__search")).isEmpty();
    }

    @Test
    @DisplayName("需求 6.3.6：子 MCP 改名后可批量重算聚合名")
    void supportsExposedNameRewriteAfterDownstreamRename() {
        GatewayTool tool = TestFixtures.tool(this.gateway.id(), this.downstream.id(), "kb_a", "search");
        this.tools.insert(tool);

        this.tools.updateExposedName(tool.id(), "kb_alpha__search",
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        assertThat(this.tools.findByGatewayIdAndExposedName(this.gateway.id(), "kb_alpha__search")).isPresent();
        assertThat(this.tools.findByGatewayIdAndExposedName(this.gateway.id(), "kb_a__search")).isEmpty();
    }

    @Test
    @DisplayName("需求 6.4.6：同步后批量移除下游已删除的工具")
    void deletesRemovedToolsInBatch() {
        GatewayTool search = TestFixtures.tool(this.gateway.id(), this.downstream.id(), "kb_a", "search");
        GatewayTool ping = TestFixtures.tool(this.gateway.id(), this.downstream.id(), "kb_a", "ping");
        GatewayTool stale = TestFixtures.tool(this.gateway.id(), this.downstream.id(), "kb_a", "legacy");
        this.tools.insert(search);
        this.tools.insert(ping);
        this.tools.insert(stale);

        assertThat(this.tools.deleteByIds(List.of(stale.id()))).isEqualTo(1);
        assertThat(this.tools.findByDownstreamMcpId(this.downstream.id()))
                .extracting(GatewayTool::originalName)
                .containsExactly("ping", "search");

        // 空集合是同步无变更时的常态，不能当成错误。
        assertThat(this.tools.deleteByIds(List.of())).isZero();
        assertThat(this.tools.deleteByIds(null)).isZero();
    }
}
