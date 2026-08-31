package com.mcpgateway.service;

import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.GatewayStatus;
import com.mcpgateway.domain.SyncStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 需求 FR-01 的派生状态。 */
class GatewayStatusCalculatorTest {

    private static DownstreamMcp with(SyncStatus status) {
        return new DownstreamMcp("id", "gw", "kb", DownstreamMcp.TYPE_STREAMABLE_HTTP,
                "https://example.com/mcp", null, status, null, null, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    @DisplayName("没有子 MCP 时为 EMPTY")
    void emptyWithoutDownstreams() {
        assertThat(GatewayStatusCalculator.calculate(List.of())).isEqualTo(GatewayStatus.EMPTY);
        assertThat(GatewayStatusCalculator.calculate(null)).isEqualTo(GatewayStatus.EMPTY);
    }

    @Test
    @DisplayName("配置了但还没同步过时仍为 EMPTY")
    void emptyWhenNothingSyncedYet() {
        assertThat(GatewayStatusCalculator.calculate(List.of(with(SyncStatus.PENDING), with(SyncStatus.PENDING))))
                .isEqualTo(GatewayStatus.EMPTY);
    }

    @Test
    @DisplayName("全部同步成功时为 READY")
    void readyWhenAllSucceeded() {
        assertThat(GatewayStatusCalculator.calculate(List.of(with(SyncStatus.SUCCESS), with(SyncStatus.SUCCESS))))
                .isEqualTo(GatewayStatus.READY);
    }

    @Test
    @DisplayName("部分失败但仍有可用工具时为 DEGRADED")
    void degradedWhenSomeFailed() {
        assertThat(GatewayStatusCalculator.calculate(List.of(with(SyncStatus.SUCCESS), with(SyncStatus.FAILED))))
                .isEqualTo(GatewayStatus.DEGRADED);
        assertThat(GatewayStatusCalculator.calculate(List.of(with(SyncStatus.SUCCESS), with(SyncStatus.PENDING))))
                .isEqualTo(GatewayStatus.DEGRADED);
    }

    @Test
    @DisplayName("全部同步失败时为 UNAVAILABLE，不能伪装成 EMPTY")
    void unavailableWhenEverythingFailed() {
        assertThat(GatewayStatusCalculator.calculate(List.of(with(SyncStatus.FAILED), with(SyncStatus.FAILED))))
                .isEqualTo(GatewayStatus.UNAVAILABLE);
        // 有失败也有待同步，同样一个可用工具都没有
        assertThat(GatewayStatusCalculator.calculate(List.of(with(SyncStatus.FAILED), with(SyncStatus.PENDING))))
                .isEqualTo(GatewayStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("需求 6.1.5：只有 READY 和 DEGRADED 算可用状态")
    void onlyReadyAndDegradedAreUsable() {
        assertThat(GatewayStatusCalculator.isUsable(GatewayStatus.READY)).isTrue();
        assertThat(GatewayStatusCalculator.isUsable(GatewayStatus.DEGRADED)).isTrue();
        assertThat(GatewayStatusCalculator.isUsable(GatewayStatus.EMPTY)).isFalse();
        assertThat(GatewayStatusCalculator.isUsable(GatewayStatus.UNAVAILABLE)).isFalse();
    }
}
