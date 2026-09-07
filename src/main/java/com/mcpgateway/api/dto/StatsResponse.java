package com.mcpgateway.api.dto;

import com.mcpgateway.domain.GatewayStatus;

import java.time.Instant;
import java.util.List;

/**
 * 统计页的一次性响应。
 *
 * <p>刻意做成一个接口而不是四五个：这一页上的所有图表都是同一个时间窗、同一份数据的不同切法，
 * 拆开只会带来"总量对得上、分组对不上"的中间态，以及四份各自可能失败的加载状态。
 *
 * <p>比率和分位都由服务端算好，与其他管理 API 的约定一致（见 types.ts 的说明）。
 * 没有样本时一律是 null，而不是 0 —— "成功率 0%" 和"这段时间没有调用"完全是两回事。
 *
 * @param from        窗口起点，含
 * @param to          窗口终点，不含
 * @param bucket      时间桶粒度，HOUR 或 DAY，由窗口长度决定
 * @param totals      窗口内的总量
 * @param series      按时间桶展开，**空桶也在里面**，调用方直接画就行
 * @param gateways    按网关分组；调用量为 0 的网关也在列表里
 * @param downstreams 按子 MCP 分组，只在指定了网关时非空
 * @param tools       调用量最高的若干个聚合工具
 * @param errorCodes  出现最多的若干个错误码
 */
public record StatsResponse(
        Instant from,
        Instant to,
        String bucket,
        Totals totals,
        List<SeriesPoint> series,
        List<GatewayStat> gateways,
        List<NamedStat> downstreams,
        List<NamedStat> tools,
        List<ErrorStat> errorCodes) {

    /**
     * 窗口总量。
     *
     * @param started      仍是 STARTED 的条数：要么正在进行，要么是上次进程异常退出的残留
     * @param successRate  成功 / 总数；没有调用时为 null
     * @param p95DurationMs 95 分位耗时。只统计已结束的调用 —— 把没跑完的算成 0 会把分位拉垮
     */
    public record Totals(
            int calls,
            int success,
            int error,
            int timeout,
            int started,
            Double successRate,
            Double avgDurationMs,
            Double p95DurationMs) {
    }

    /** 时间序列上的一个点。分状态给，因为图上是按状态堆叠的。 */
    public record SeriesPoint(Instant at, int total, int success, int error, int timeout) {
    }

    /** 一个网关的调用情况。status 是网关自身的派生状态（需求 FR-01），与调用量无关。 */
    public record GatewayStat(
            String gatewayId,
            String name,
            String slug,
            GatewayStatus status,
            int calls,
            int failures,
            Double successRate,
            Double avgDurationMs,
            Double p95DurationMs) {
    }

    /** 子 MCP 或工具的调用情况。工具没有 id，这时 id 与 name 相同。 */
    public record NamedStat(
            String id,
            String name,
            int calls,
            int failures,
            Double successRate,
            Double avgDurationMs,
            Double p95DurationMs) {
    }

    public record ErrorStat(String code, int count) {
    }
}
