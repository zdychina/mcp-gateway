package com.mcpgateway.service;

import com.mcpgateway.api.dto.GatewaySummaryResponse;
import com.mcpgateway.api.dto.StatsResponse;
import com.mcpgateway.api.dto.StatsResponse.ErrorStat;
import com.mcpgateway.api.dto.StatsResponse.GatewayStat;
import com.mcpgateway.api.dto.StatsResponse.NamedStat;
import com.mcpgateway.api.dto.StatsResponse.SeriesPoint;
import com.mcpgateway.api.dto.StatsResponse.Totals;
import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.repository.CallStatsRepository;
import com.mcpgateway.repository.CallStatsRepository.Bucket;
import com.mcpgateway.repository.CallStatsRepository.Grouped;
import com.mcpgateway.repository.CallStatsRepository.SeriesRow;
import com.mcpgateway.repository.CallStatsRepository.Window;
import com.mcpgateway.repository.DownstreamMcpRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统计页的数据装配。
 *
 * <p>这一页回答的是"最近怎么样"，不是"某一次调用怎么了"——后者是调用记录页的事。
 * 所以这里只有聚合数字，一条正文、一个参数都不返回。
 */
@Service
public class CallStatsService {

    /** 默认时间窗：最近 24 小时。排障和巡检问的绝大多数是"今天怎么样"。 */
    static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    /**
     * 时间窗上限。
     *
     * 不是性能考虑（有索引），是产品考虑：这一页给的是趋势，跨度超过三个月的趋势
     * 用日桶画出来就是一条毛刺线，看不出任何东西，该去查库做报表了。
     */
    static final Duration MAX_WINDOW = Duration.ofDays(92);

    /** 超过这个跨度就按天分桶。48 小时以内用小时桶，最多 48 个点，图上还看得清。 */
    static final Duration HOURLY_UP_TO = Duration.ofHours(48);

    /** 工具榜和错误码榜各取前几名。再多列表就没人看了，剩下的去调用记录页筛。 */
    static final int TOP_TOOLS = 10;

    static final int TOP_ERROR_CODES = 8;

    private final CallStatsRepository stats;

    private final GatewayService gatewayService;

    private final DownstreamMcpRepository downstreams;

    public CallStatsService(CallStatsRepository stats, GatewayService gatewayService,
            DownstreamMcpRepository downstreams) {
        this.stats = stats;
        this.gatewayService = gatewayService;
        this.downstreams = downstreams;
    }

    /**
     * 取一个时间窗内的全部统计。
     *
     * @param gatewayId 只看某个网关；为空表示全部网关
     * @param from      ISO-8601 instant，含；为空表示 to 往前 24 小时
     * @param to        ISO-8601 instant，不含；为空表示现在
     */
    @Transactional(readOnly = true)
    public StatsResponse stats(String gatewayId, String from, String to) {
        String scopedGatewayId = blankToNull(gatewayId);
        if (scopedGatewayId != null) {
            // 网关不存在要报 GATEWAY_NOT_FOUND，而不是回一页全是 0 的图
            this.gatewayService.requireGateway(scopedGatewayId);
        }

        Instant toInstant = parseInstant(to, "to", Instant.now());
        Instant fromInstant = parseInstant(from, "from", toInstant.minus(DEFAULT_WINDOW));
        if (!fromInstant.isBefore(toInstant)) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST, "from must be earlier than to");
        }
        if (Duration.between(fromInstant, toInstant).compareTo(MAX_WINDOW) > 0) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST,
                    "the window must not exceed " + MAX_WINDOW.toDays() + " days");
        }

        Bucket bucket = Duration.between(fromInstant, toInstant).compareTo(HOURLY_UP_TO) <= 0
                ? Bucket.HOUR : Bucket.DAY;
        Window window = new Window(scopedGatewayId, fromInstant, toInstant);

        return new StatsResponse(
                fromInstant, toInstant, bucket.name(),
                totals(window),
                series(window, bucket),
                gateways(window),
                scopedGatewayId == null ? List.of() : downstreams(window, scopedGatewayId),
                tools(window),
                errorCodes(window));
    }

    // ---------------------------------------------------------------- 各部分

    private Totals totals(Window window) {
        Map<CallStatus, Integer> byStatus = this.stats.countByStatus(window);
        Grouped totals = this.stats.totals(window);

        int success = byStatus.getOrDefault(CallStatus.SUCCESS, 0);
        return new Totals(totals.calls(), success,
                byStatus.getOrDefault(CallStatus.ERROR, 0),
                byStatus.getOrDefault(CallStatus.TIMEOUT, 0),
                byStatus.getOrDefault(CallStatus.STARTED, 0),
                rate(success, totals.calls()),
                totals.avgDurationMs(), totals.p95DurationMs());
    }

    /**
     * 时间序列，**空桶补齐**。
     *
     * 只把有数据的桶画出来，图上就会把"这一小时没有调用"和"这一小时不存在"混成一件事：
     * 折线会直接连过去，柱状图会把 14 点和 17 点挨着画。补零是这张图不撒谎的前提。
     */
    private List<SeriesPoint> series(Window window, Bucket bucket) {
        Map<Instant, Map<CallStatus, Integer>> byBucket = new LinkedHashMap<>();
        for (SeriesRow row : this.stats.series(window, bucket)) {
            byBucket.computeIfAbsent(row.at(), key -> new EnumMap<>(CallStatus.class))
                    .merge(row.status(), row.count(), Integer::sum);
        }

        ChronoUnit unit = bucket == Bucket.HOUR ? ChronoUnit.HOURS : ChronoUnit.DAYS;
        List<SeriesPoint> points = new ArrayList<>();
        for (Instant at = window.from().truncatedTo(unit); at.isBefore(window.to());
                at = at.plus(1, unit)) {
            Map<CallStatus, Integer> counts = byBucket.getOrDefault(at, Map.of());
            int success = counts.getOrDefault(CallStatus.SUCCESS, 0);
            int error = counts.getOrDefault(CallStatus.ERROR, 0);
            int timeout = counts.getOrDefault(CallStatus.TIMEOUT, 0);
            int started = counts.getOrDefault(CallStatus.STARTED, 0);
            points.add(new SeriesPoint(at, success + error + timeout + started,
                    success, error, timeout));
        }
        return points;
    }

    /**
     * 按网关分组。
     *
     * 调用量为 0 的网关也留在列表里：一个平时很忙的网关这段时间一条调用都没有，
     * 恰恰是最该看见的情况，把它从列表里去掉等于把问题藏起来。
     *
     * <p><b>刻意用不带网关过滤的窗口查。</b>这一块是跨网关的导航（界面上是"全部网关"表和
     * "各网关调用量"图），选中某个网关之后它仍然要如实显示别的网关 —— 沿用被过滤的窗口，
     * 表里会出现一排 0，而那些网关这段时间明明有流量；图上也只剩一根柱子，
     * "点柱子切到那个网关"这个动作直接没了。被过滤的是总量、趋势、子 MCP、工具和错误码。
     */
    private List<GatewayStat> gateways(Window window) {
        Window allGateways = new Window(null, window.from(), window.to());
        Map<String, Grouped> byId = this.stats.byGateway(allGateways).stream()
                .collect(Collectors.toMap(Grouped::key, Function.identity(), (a, b) -> a));

        List<GatewayStat> result = new ArrayList<>();
        for (GatewaySummaryResponse gateway : this.gatewayService.list()) {
            Grouped grouped = byId.get(gateway.id());
            int calls = grouped == null ? 0 : grouped.calls();
            int failures = grouped == null ? 0 : grouped.failures();
            result.add(new GatewayStat(gateway.id(), gateway.name(), gateway.slug(),
                    gateway.status(), calls, failures,
                    // 用真实的 SUCCESS 数，不是 calls - failures ——
                    // 后者会把还没结束的 STARTED 算成成功，和上面的总成功率对不上
                    rate(grouped == null ? 0 : grouped.successes(), calls),
                    grouped == null ? null : grouped.avgDurationMs(),
                    grouped == null ? null : grouped.p95DurationMs()));
        }
        // 调用量高的在前；一条都没有的沉到底部，但仍然在
        result.sort((a, b) -> Integer.compare(b.calls(), a.calls()));
        return result;
    }

    private List<NamedStat> downstreams(Window window, String gatewayId) {
        Map<String, String> names = this.downstreams.findByGatewayId(gatewayId).stream()
                .collect(Collectors.toMap(DownstreamMcp::id, DownstreamMcp::name, (a, b) -> a));

        return this.stats.byDownstream(window).stream()
                .map(grouped -> named(grouped.key(),
                        names.getOrDefault(grouped.key(), grouped.key()), grouped))
                .toList();
    }

    private List<NamedStat> tools(Window window) {
        return this.stats.byTool(window, TOP_TOOLS).stream()
                .map(grouped -> named(grouped.key(), grouped.key(), grouped))
                .toList();
    }

    private List<ErrorStat> errorCodes(Window window) {
        return this.stats.byErrorCode(window, TOP_ERROR_CODES).stream()
                .map(grouped -> new ErrorStat(grouped.key(), grouped.calls()))
                .toList();
    }

    // ------------------------------------------------------------------ 内部

    private static NamedStat named(String id, String name, Grouped grouped) {
        return new NamedStat(id, name, grouped.calls(), grouped.failures(),
                // 同上：STARTED 既不算成功也不算失败，不能用减法推
                rate(grouped.successes(), grouped.calls()),
                grouped.avgDurationMs(), grouped.p95DurationMs());
    }

    /** 没有样本时是 null 而不是 0 —— "成功率 0%"和"这段时间没有调用"是两回事。 */
    private static Double rate(int part, int total) {
        return total == 0 ? null : (double) part / total;
    }

    private static Instant parseInstant(String value, String field, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value.trim());
        }
        catch (DateTimeException ex) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST,
                    field + " must be an ISO-8601 instant, for example 2026-08-31T12:00:00Z");
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
