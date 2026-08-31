package com.mcpgateway.downstream;

import com.mcpgateway.AbstractDataTest;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.domain.SyncStatus;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.GatewayRepository;
import com.mcpgateway.repository.GatewayToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 快照合并算法的直接测试。
 *
 * 与 {@link ToolSyncIntegrationTest} 的分工：那个用真实子 MCP 验证整条链路，
 * 这个直接喂构造好的工具定义，覆盖真实 mock 难以触发的边界 —— 比如超长聚合名
 * （子 MCP 名列宽只有 64，靠真实服务撑不到 128 字符）和下游返回重名工具。
 */
class ToolSnapshotWriterTest extends AbstractDataTest {

    @Autowired
    private GatewayRepository gateways;

    @Autowired
    private DownstreamMcpRepository downstreams;

    @Autowired
    private GatewayToolRepository tools;

    @Autowired
    private ToolSnapshotWriter writer;

    private Gateway gateway;

    private DownstreamMcp downstream;

    @BeforeEach
    void seed() {
        this.gateway = new Gateway(UUID.randomUUID().toString(), "gw",
                "snap-" + Long.toString(System.nanoTime(), 36), null, "hash", Instant.now(), Instant.now());
        this.gateways.insert(this.gateway);
        this.downstream = new DownstreamMcp(UUID.randomUUID().toString(), this.gateway.id(), "kb_a",
                DownstreamMcp.TYPE_STREAMABLE_HTTP, "https://example.com/mcp", null,
                SyncStatus.PENDING, null, null, Instant.now(), Instant.now());
        this.downstreams.insert(this.downstream);
    }

    private static FetchedTool tool(String name, String description) {
        String inputSchema = "{\"type\":\"object\"}";
        String hash = ToolDefinitionMapper.definitionHash(name, description, inputSchema, null, null);
        return new FetchedTool(name, description, inputSchema, null, null, hash);
    }

    private DownstreamMcp renamedTo(String name) {
        return new DownstreamMcp(this.downstream.id(), this.gateway.id(), name, this.downstream.type(),
                this.downstream.url(), null, SyncStatus.SUCCESS, null, null,
                this.downstream.createdAt(), Instant.now());
    }

    @Test
    @DisplayName("首次合并全部按新增处理")
    void firstMergeAddsEverything() {
        var outcome = this.writer.merge(this.downstream,
                List.of(tool("search", "d1"), tool("ping", "d2")), Instant.now());

        assertThat(outcome.added()).isEqualTo(2);
        assertThat(outcome.updated()).isZero();
        assertThat(outcome.removed()).isZero();
        assertThat(this.tools.findByDownstreamMcpId(this.downstream.id()))
                .extracting(GatewayTool::exposedName)
                .containsExactlyInAnyOrder("kb_a__search", "kb_a__ping");
    }

    @Test
    @DisplayName("描述变化被识别为 updated，未变的识别为 unchanged")
    void detectsChangedAndUnchangedDefinitions() {
        Instant first = Instant.now();
        this.writer.merge(this.downstream, List.of(tool("search", "d1"), tool("ping", "d2")), first);

        var outcome = this.writer.merge(this.downstream,
                List.of(tool("search", "改过的描述"), tool("ping", "d2")), first.plusSeconds(1));

        assertThat(outcome.updated()).isEqualTo(1);
        assertThat(outcome.unchanged()).isEqualTo(1);
        assertThat(this.tools.findByGatewayIdAndExposedName(this.gateway.id(), "kb_a__search")
                .orElseThrow().originalDescription()).isEqualTo("改过的描述");
    }

    @Test
    @DisplayName("需求 6.4.6：本次没返回的工具被删除")
    void removesToolsMissingFromTheLatestList() {
        Instant now = Instant.now();
        this.writer.merge(this.downstream, List.of(tool("search", "d"), tool("ping", "d")), now);

        var outcome = this.writer.merge(this.downstream, List.of(tool("search", "d")), now.plusSeconds(1));

        assertThat(outcome.removed()).isEqualTo(1);
        assertThat(this.tools.findByDownstreamMcpId(this.downstream.id()))
                .extracting(GatewayTool::originalName)
                .containsExactly("search");
    }

    @Test
    @DisplayName("下游返回空列表时清空该子 MCP 的全部工具")
    void emptyListClearsTheSnapshot() {
        Instant now = Instant.now();
        this.writer.merge(this.downstream, List.of(tool("search", "d"), tool("ping", "d")), now);

        var outcome = this.writer.merge(this.downstream, List.of(), now.plusSeconds(1));

        assertThat(outcome.removed()).isEqualTo(2);
        assertThat(this.tools.findByDownstreamMcpId(this.downstream.id())).isEmpty();
    }

    @Test
    @DisplayName("子 MCP 改名后，合并时顺手把聚合名纠正过来")
    void correctsExposedNameAfterDownstreamRename() {
        Instant now = Instant.now();
        this.writer.merge(this.downstream, List.of(tool("search", "d")), now);

        this.writer.merge(renamedTo("kb_alpha"), List.of(tool("search", "d")), now.plusSeconds(1));

        assertThat(this.tools.findByDownstreamMcpId(this.downstream.id()))
                .extracting(GatewayTool::exposedName)
                .containsExactly("kb_alpha__search");
    }

    @Test
    @DisplayName("需求 6.3.5：聚合名超长时整批失败，一行都不写")
    void overlongExposedNameFailsTheWholeBatch() {
        // 子 MCP 名 4 + 分隔符 2 + 工具名 126 = 132 > 128
        String longToolName = "t".repeat(126);

        assertThatThrownBy(() -> this.writer.merge(this.downstream,
                List.of(tool("search", "d"), tool(longToolName, "d")), Instant.now()))
                .isInstanceOf(GatewayException.class)
                .extracting(ex -> ((GatewayException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_TOOL_NAME);

        // 合法的那条也不能落库
        assertThat(this.tools.findByDownstreamMcpId(this.downstream.id())).isEmpty();
    }

    @Test
    @DisplayName("需求 6.3.4：工具名含非法字符时整批失败")
    void invalidCharactersInToolNameFailTheBatch() {
        assertThatThrownBy(() -> this.writer.merge(this.downstream,
                List.of(tool("bad name!", "d")), Instant.now()))
                .isInstanceOf(GatewayException.class)
                .extracting(ex -> ((GatewayException) ex).errorCode())
                .isEqualTo(ErrorCode.INVALID_TOOL_NAME);
    }

    @Test
    @DisplayName("下游返回重名工具时整批失败，不静默去重")
    void duplicateToolNamesFromDownstreamFailTheBatch() {
        assertThatThrownBy(() -> this.writer.merge(this.downstream,
                List.of(tool("search", "d1"), tool("search", "d2")), Instant.now()))
                .isInstanceOf(GatewayException.class)
                .extracting(ex -> ((GatewayException) ex).errorCode())
                .isEqualTo(ErrorCode.DOWNSTREAM_SYNC_FAILED);

        assertThat(this.tools.findByDownstreamMcpId(this.downstream.id())).isEmpty();
    }

    @Test
    @DisplayName("合并失败不影响同网关下其他子 MCP 已有的快照")
    void failedMergeLeavesOtherDownstreamsAlone() {
        DownstreamMcp other = new DownstreamMcp(UUID.randomUUID().toString(), this.gateway.id(), "kb_b",
                DownstreamMcp.TYPE_STREAMABLE_HTTP, "https://example.com/b", null,
                SyncStatus.PENDING, null, null, Instant.now(), Instant.now());
        this.downstreams.insert(other);
        this.writer.merge(other, List.of(tool("search", "d")), Instant.now());

        assertThatThrownBy(() -> this.writer.merge(this.downstream,
                List.of(tool("t".repeat(126), "d")), Instant.now()))
                .isInstanceOf(GatewayException.class);

        assertThat(this.tools.findByDownstreamMcpId(other.id()))
                .extracting(GatewayTool::exposedName)
                .containsExactly("kb_b__search");
    }
}
