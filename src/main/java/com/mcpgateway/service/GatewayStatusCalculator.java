package com.mcpgateway.service;

import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.GatewayStatus;
import com.mcpgateway.domain.SyncStatus;

import java.util.List;

/**
 * 网关派生状态计算（需求 FR-01）。状态不落库，每次按子 MCP 的同步结果实时算。
 *
 * 需求只定义了 EMPTY / READY / DEGRADED 三态，且 DEGRADED 的定义是"至少一个子 MCP 异常，
 * 但仍有可用工具"。当配置了子 MCP 但**全部**同步失败时，三态里没有一个说得通：说 EMPTY
 * 会让操作人以为还没配，说 DEGRADED 又与"仍有可用工具"矛盾。这里补 UNAVAILABLE 填这个洞，
 * 是否并入 DEGRADED 待需求方确认。
 */
public final class GatewayStatusCalculator {

    private GatewayStatusCalculator() {
    }

    public static GatewayStatus calculate(List<DownstreamMcp> downstreams) {
        if (downstreams == null || downstreams.isEmpty()) {
            return GatewayStatus.EMPTY;
        }
        long succeeded = downstreams.stream().filter(d -> d.syncStatus() == SyncStatus.SUCCESS).count();
        if (succeeded == downstreams.size()) {
            return GatewayStatus.READY;
        }
        if (succeeded > 0) {
            return GatewayStatus.DEGRADED;
        }
        // 一个都没同步成功：区分"还没同步过"和"同步过但全挂了"。
        boolean anyFailed = downstreams.stream().anyMatch(d -> d.syncStatus() == SyncStatus.FAILED);
        return anyFailed ? GatewayStatus.UNAVAILABLE : GatewayStatus.EMPTY;
    }

    /** 需求 6.1.5：网关必须至少有 1 个成功同步的子 MCP 才处于可用状态。 */
    public static boolean isUsable(GatewayStatus status) {
        return status == GatewayStatus.READY || status == GatewayStatus.DEGRADED;
    }
}
