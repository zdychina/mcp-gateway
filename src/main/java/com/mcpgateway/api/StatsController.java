package com.mcpgateway.api;

import com.mcpgateway.api.dto.StatsResponse;
import com.mcpgateway.service.CallStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计接口（管理端的总览页）。
 *
 * <p>只读、只回聚合数字：调用量、成功率、耗时分位、按网关/子 MCP/工具/错误码的分布。
 * <b>不返回任何入参或返回正文</b> —— 底下的查询连 request_json / response_json 都不 SELECT，
 * 所以这个接口不在 SECURITY.md 那条"正文出口"的讨论范围内。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final CallStatsService callStatsService;

    public StatsController(CallStatsService callStatsService) {
        this.callStatsService = callStatsService;
    }

    /**
     * 一个时间窗内的全部统计，一次返回。
     *
     * @param gatewayId 只看某个网关；省略表示全部网关。此时响应里的 downstreams 才有内容
     * @param from      起始时间，ISO-8601 instant，含；省略表示 to 往前 24 小时
     * @param to        结束时间，ISO-8601 instant，不含；省略表示现在
     */
    @GetMapping
    public ApiResponse<StatsResponse> stats(
            @RequestParam(required = false) String gatewayId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        return ApiResponse.ok(this.callStatsService.stats(gatewayId, from, to));
    }
}
