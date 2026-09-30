package com.mcpgateway.repository;

import com.mcpgateway.AbstractDataTest;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.SyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamMcpRepositoryTest extends AbstractDataTest {

    @Autowired
    private GatewayRepository gateways;

    @Autowired
    private DownstreamMcpRepository downstreams;

    private Gateway gateway;

    @BeforeEach
    void seed() {
        this.gateway = TestFixtures.gateway("ds-" + System.nanoTime());
        this.gateways.insert(this.gateway);
    }

    @Test
    @DisplayName("新建的子 MCP 处于 PENDING，尚无同步时间")
    void newDownstreamStartsPending() {
        DownstreamMcp downstream = TestFixtures.downstream(this.gateway.id(), "kb_a");

        this.downstreams.insert(downstream);

        DownstreamMcp stored = this.downstreams.findById(downstream.id()).orElseThrow();
        assertThat(stored.syncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(stored.lastSyncAt()).isNull();
        assertThat(stored.lastSyncError()).isNull();
        assertThat(stored.type()).isEqualTo(DownstreamMcp.TYPE_STREAMABLE_HTTP);
        assertThat(stored.encryptedHeadersJson()).isEqualTo("encrypted-blob");
    }

    @Test
    @DisplayName("需求 6.4.7：同步失败只改状态和错误，不动配置和上次成功时间之外的内容")
    void syncFailureIsRecordedSeparatelyFromConfig() {
        DownstreamMcp downstream = TestFixtures.downstream(this.gateway.id(), "kb_a");
        this.downstreams.insert(downstream);

        Instant syncedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.downstreams.updateSyncResult(downstream.id(), SyncStatus.SUCCESS, syncedAt, null, null, syncedAt);

        Instant failedAt = syncedAt.plusSeconds(60);
        this.downstreams.updateSyncResult(downstream.id(), SyncStatus.FAILED, syncedAt,
                "DOWNSTREAM_SYNC_FAILED: connection refused", null, failedAt);

        DownstreamMcp stored = this.downstreams.findById(downstream.id()).orElseThrow();
        assertThat(stored.syncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(stored.lastSyncError()).contains("DOWNSTREAM_SYNC_FAILED");
        // last_sync_at 仍停在上一次成功的时刻，配置字段未被同步流程改动。
        assertThat(stored.lastSyncAt()).isEqualTo(syncedAt);
        assertThat(stored.url()).isEqualTo(downstream.url());
    }

    @Test
    @DisplayName("再次同步成功会清空上一次的错误")
    void successfulResyncClearsPreviousError() {
        DownstreamMcp downstream = TestFixtures.downstream(this.gateway.id(), "kb_a");
        this.downstreams.insert(downstream);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.downstreams.updateSyncResult(downstream.id(), SyncStatus.FAILED, null, "boom", null, now);

        this.downstreams.updateSyncResult(downstream.id(), SyncStatus.SUCCESS, now, null, null, now);

        DownstreamMcp stored = this.downstreams.findById(downstream.id()).orElseThrow();
        assertThat(stored.syncStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(stored.lastSyncError()).isNull();
    }

    @Test
    @DisplayName("更新配置不会顺手把同步状态改掉")
    void updateConfigDoesNotTouchSyncStatus() {
        DownstreamMcp downstream = TestFixtures.downstream(this.gateway.id(), "kb_a");
        this.downstreams.insert(downstream);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.downstreams.updateSyncResult(downstream.id(), SyncStatus.SUCCESS, now, null, null, now);

        this.downstreams.updateConfig(downstream.id(), "kb_alpha", "https://example.com/mcp/v2",
                "new-encrypted-blob", null, now);

        DownstreamMcp stored = this.downstreams.findById(downstream.id()).orElseThrow();
        assertThat(stored.name()).isEqualTo("kb_alpha");
        assertThat(stored.url()).isEqualTo("https://example.com/mcp/v2");
        assertThat(stored.encryptedHeadersJson()).isEqualTo("new-encrypted-blob");
        assertThat(stored.syncStatus()).isEqualTo(SyncStatus.SUCCESS);
    }

    @Test
    @DisplayName("同步成功写入捕获的原始描述，失败时调用方传旧值即可保留")
    void syncResultCarriesOriginalDescription() {
        DownstreamMcp downstream = TestFixtures.downstream(this.gateway.id(), "kb_a");
        this.downstreams.insert(downstream);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        this.downstreams.updateSyncResult(downstream.id(), SyncStatus.SUCCESS, now, null,
                "下游自述：知识库检索", now);

        DownstreamMcp captured = this.downstreams.findById(downstream.id()).orElseThrow();
        assertThat(captured.originalDescription()).isEqualTo("下游自述：知识库检索");

        // 失败路径按 ToolSyncService 的约定把读到的旧值传回来，捕获因此保留。
        this.downstreams.updateSyncResult(downstream.id(), SyncStatus.FAILED, now, "boom",
                captured.originalDescription(), now.plusSeconds(30));
        assertThat(this.downstreams.findById(downstream.id()).orElseThrow().originalDescription())
                .isEqualTo("下游自述：知识库检索");
    }

    @Test
    @DisplayName("自定义描述随配置写入，且不被同步流程冲掉")
    void updateConfigWritesCustomDescriptionWithoutTouchingOriginal() {
        DownstreamMcp downstream = TestFixtures.downstream(this.gateway.id(), "kb_a",
                "原始自述", null);
        this.downstreams.insert(downstream);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        this.downstreams.updateConfig(downstream.id(), "kb_a", downstream.url(),
                downstream.encryptedHeadersJson(), "运营改写", now);
        DownstreamMcp stored = this.downstreams.findById(downstream.id()).orElseThrow();
        assertThat(stored.customDescription()).isEqualTo("运营改写");
        assertThat(stored.effectiveDescription()).isEqualTo("运营改写");

        // 同步只写 original_description，custom 是操作人配置，必须原样保留。
        this.downstreams.updateSyncResult(downstream.id(), SyncStatus.SUCCESS, now, null,
                "新的原始自述", now);
        DownstreamMcp resynced = this.downstreams.findById(downstream.id()).orElseThrow();
        assertThat(resynced.originalDescription()).isEqualTo("新的原始自述");
        assertThat(resynced.customDescription()).isEqualTo("运营改写");
        assertThat(resynced.effectiveDescription()).isEqualTo("运营改写");
    }

    @Test
    @DisplayName("需求 6.2.1：可以统计网关下的子 MCP 数量以便判断是否超过上限")
    void countsDownstreamsPerGateway() {
        assertThat(this.downstreams.countByGatewayId(this.gateway.id())).isZero();

        this.downstreams.insert(TestFixtures.downstream(this.gateway.id(), "kb_a"));
        this.downstreams.insert(TestFixtures.downstream(this.gateway.id(), "kb_b"));
        this.downstreams.insert(TestFixtures.downstream(this.gateway.id(), "kb_c"));

        assertThat(this.downstreams.countByGatewayId(this.gateway.id())).isEqualTo(3);
        assertThat(this.downstreams.findByGatewayId(this.gateway.id()))
                .extracting(DownstreamMcp::name)
                .containsExactly("kb_a", "kb_b", "kb_c");
    }
}
