package com.mcpgateway.repository;

import com.mcpgateway.AbstractDataTest;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.domain.ToolCallRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 迁移脚本里那几条外键和唯一约束的行为验证。
 * 这些规则只存在于数据库，代码怎么写都替代不了，必须真库跑。
 */
class CascadeAndConstraintTest extends AbstractDataTest {

    @Autowired
    private GatewayRepository gateways;

    @Autowired
    private DownstreamMcpRepository downstreams;

    @Autowired
    private GatewayToolRepository tools;

    @Autowired
    private ToolCallRecordRepository callRecords;

    @Test
    @DisplayName("需求 6.1.7：删除网关级联删除子 MCP、工具快照和调用记录")
    void deletingGatewayCascadesEverything() {
        Gateway gateway = TestFixtures.gateway("csc-" + System.nanoTime());
        this.gateways.insert(gateway);
        DownstreamMcp downstream = TestFixtures.downstream(gateway.id(), "kb_a");
        this.downstreams.insert(downstream);
        GatewayTool tool = TestFixtures.tool(gateway.id(), downstream.id(), "kb_a", "search");
        this.tools.insert(tool);
        String callId = TestFixtures.id();
        this.callRecords.insertStarted(ToolCallRecord.started(callId, "trace-1", gateway.id(), downstream.id(),
                tool.exposedName(), tool.originalName(), "{}", TestFixtures.NOW));

        this.gateways.deleteById(gateway.id());

        assertThat(this.gateways.findById(gateway.id())).isEmpty();
        assertThat(this.downstreams.findById(downstream.id())).isEmpty();
        assertThat(this.tools.findById(tool.id())).isEmpty();
        assertThat(this.callRecords.findByCallId(callId)).isEmpty();
    }

    @Test
    @DisplayName("需求 6.2.10：删除子 MCP 只带走它的工具，调用记录保留")
    void deletingDownstreamRemovesToolsButKeepsCallRecords() {
        Gateway gateway = TestFixtures.gateway("dsd-" + System.nanoTime());
        this.gateways.insert(gateway);
        DownstreamMcp downstream = TestFixtures.downstream(gateway.id(), "kb_a");
        this.downstreams.insert(downstream);
        GatewayTool tool = TestFixtures.tool(gateway.id(), downstream.id(), "kb_a", "search");
        this.tools.insert(tool);
        String callId = TestFixtures.id();
        this.callRecords.insertStarted(ToolCallRecord.started(callId, "trace-2", gateway.id(), downstream.id(),
                tool.exposedName(), tool.originalName(), "{}", TestFixtures.NOW));

        this.downstreams.deleteById(downstream.id());

        assertThat(this.tools.findById(tool.id())).isEmpty();
        // 历史调用记录是审计资料，不能因为删掉一个子 MCP 就消失。
        assertThat(this.callRecords.findByCallId(callId)).isPresent();
    }

    @Test
    @DisplayName("需求 6.2.2：同一网关内子 MCP 名称唯一，且区分大小写")
    void enforcesDownstreamNameUniquenessPerGateway() {
        Gateway gateway = TestFixtures.gateway("dnu-" + System.nanoTime());
        this.gateways.insert(gateway);
        this.downstreams.insert(TestFixtures.downstream(gateway.id(), "kb_a"));

        assertThatThrownBy(() -> this.downstreams.insert(TestFixtures.downstream(gateway.id(), "kb_a")))
                .isInstanceOf(DuplicateKeyException.class);

        // 大小写不同视为不同名称
        this.downstreams.insert(TestFixtures.downstream(gateway.id(), "KB_A"));
        assertThat(this.downstreams.countByGatewayId(gateway.id())).isEqualTo(2);
        assertThat(this.downstreams.existsByGatewayIdAndName(gateway.id(), "kb_a")).isTrue();
        assertThat(this.downstreams.existsByGatewayIdAndName(gateway.id(), "kb_b")).isFalse();
    }

    @Test
    @DisplayName("不同网关可以用相同的子 MCP 名称")
    void downstreamNamesAreScopedToGateway() {
        Gateway first = TestFixtures.gateway("sc1-" + System.nanoTime());
        Gateway second = TestFixtures.gateway("sc2-" + System.nanoTime());
        this.gateways.insert(first);
        this.gateways.insert(second);

        this.downstreams.insert(TestFixtures.downstream(first.id(), "kb_a"));
        this.downstreams.insert(TestFixtures.downstream(second.id(), "kb_a"));

        assertThat(this.downstreams.countByGatewayId(first.id())).isEqualTo(1);
        assertThat(this.downstreams.countByGatewayId(second.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("需求 9.3：同一网关内聚合工具名唯一")
    void enforcesExposedNameUniquenessPerGateway() {
        Gateway gateway = TestFixtures.gateway("exu-" + System.nanoTime());
        this.gateways.insert(gateway);
        DownstreamMcp first = TestFixtures.downstream(gateway.id(), "kb_a");
        DownstreamMcp second = TestFixtures.downstream(gateway.id(), "kb_b");
        this.downstreams.insert(first);
        this.downstreams.insert(second);

        this.tools.insert(TestFixtures.tool(gateway.id(), first.id(), "kb_a", "search"));

        // 需求 6.3.2：两个子 MCP 的同名工具不冲突，因为聚合名带了子 MCP 前缀。
        this.tools.insert(TestFixtures.tool(gateway.id(), second.id(), "kb_b", "search"));
        assertThat(this.tools.findByGatewayId(gateway.id()))
                .extracting(GatewayTool::exposedName)
                .containsExactly("kb_a__search", "kb_b__search");

        // 但同一个聚合名不能出现两次。
        GatewayTool clash = TestFixtures.tool(gateway.id(), second.id(), "kb_a", "search");
        assertThatThrownBy(() -> this.tools.insert(clash)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("需求 9.3：同一子 MCP 的同一原工具只允许一行快照")
    void enforcesOriginalNameUniquenessPerDownstream() {
        Gateway gateway = TestFixtures.gateway("oru-" + System.nanoTime());
        this.gateways.insert(gateway);
        DownstreamMcp downstream = TestFixtures.downstream(gateway.id(), "kb_a");
        this.downstreams.insert(downstream);
        this.tools.insert(TestFixtures.tool(gateway.id(), downstream.id(), "kb_a", "search"));

        GatewayTool duplicate = new GatewayTool(TestFixtures.id(), gateway.id(), downstream.id(), "search",
                "kb_a__search_alias", "d", null, null, null, null, true, null,
                TestFixtures.NOW, TestFixtures.NOW, TestFixtures.NOW);

        assertThatThrownBy(() -> this.tools.insert(duplicate)).isInstanceOf(DuplicateKeyException.class);
    }
}
