package com.mcpgateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpgateway.AbstractApiTest;
import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.SyncStatus;
import com.mcpgateway.domain.ToolCallRecord;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.ToolCallRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统计接口（管理端总览页）。
 *
 * 这一页只回聚合数字，所以用例盯的是"数字对不对"和"没有数据时说的是什么"——
 * 后者同样重要：0 和 null 在这里含义不同，成功率 0% 和这段时间没有调用是两回事。
 */
class StatsApiTest extends AbstractApiTest {

    /** 所有夹具记录都挂在这个时刻附近，断言用的窗口也围着它取。 */
    private static final Instant NOW = Instant.now();

    @Autowired
    private ToolCallRecordRepository records;

    @Autowired
    private DownstreamMcpRepository downstreams;

    @Autowired
    private JdbcClient jdbcClient;

    private String gatewayId;

    private String otherGatewayId;

    private String downstreamA;

    private String downstreamB;

    @BeforeEach
    void seed() throws Exception {
        clearCommittedRecords();
        this.gatewayId = createGateway("统计网关");
        this.otherGatewayId = createGateway("另一个网关");
        this.downstreamA = seedDownstream(this.gatewayId, "kb_a");
        this.downstreamB = seedDownstream(this.gatewayId, "kb_b");
    }

    /**
     * 清掉别的测试类留下的调用记录。
     *
     * 打点服务按需求 FR-06.2 走 REQUIRES_NEW 开独立事务，所以
     * {@code CallRecordingEndToEndTest} 那类不带 {@code @Transactional} 的用例写下的记录
     * 是<b>真提交</b>的，会留在这套共享的内存库里。别处无所谓 —— 那些用例都按自己的
     * gatewayId 查；但统计接口的默认视图是跨网关的，别人的记录会直接混进总量里。
     *
     * 这里的 DELETE 在本用例自己的事务里，测试结束照常回滚，不影响任何其他测试。
     */
    private void clearCommittedRecords() {
        this.jdbcClient.sql("DELETE FROM tool_call_record").update();
    }

    // ------------------------------------------------------------------ 夹具

    private String createGateway(String name) throws Exception {
        MvcResult result = this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"slug\":\"%s\"}".formatted(name, uniqueSlug("st"))))
                .andExpect(status().isCreated())
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/gateway/id").asText();
    }

    private String seedDownstream(String owner, String name) {
        DownstreamMcp downstream = new DownstreamMcp(UUID.randomUUID().toString(), owner, name,
                DownstreamMcp.TYPE_STREAMABLE_HTTP, "https://kb.example.com/mcp", null,
                SyncStatus.SUCCESS, NOW, null, NOW, NOW);
        this.downstreams.insert(downstream);
        return downstream.id();
    }

    /** 写一条已经结束的记录。minutesAgo 越大越早。 */
    private void record(String owner, String downstream, String tool, CallStatus status,
            int minutesAgo, long durationMs) {
        String callId = UUID.randomUUID().toString();
        Instant startedAt = NOW.minus(minutesAgo, ChronoUnit.MINUTES);
        this.records.insertStarted(ToolCallRecord.started(callId, "t-" + callId, owner, downstream,
                tool, tool, "{}", startedAt));
        if (status != CallStatus.STARTED) {
            String errorCode = switch (status) {
                case ERROR -> "DOWNSTREAM_ERROR";
                case TIMEOUT -> "DOWNSTREAM_TIMEOUT";
                default -> null;
            };
            this.records.complete(callId, status, status == CallStatus.SUCCESS ? "{}" : null,
                    errorCode, errorCode == null ? null : "downstream failed",
                    startedAt.plusMillis(durationMs), durationMs);
        }
    }

    private JsonNode stats(String query) throws Exception {
        MvcResult result = this.mockMvc.perform(get("/api/stats" + query))
                .andExpect(status().isOk())
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("总量、各状态条数、成功率和耗时分位")
    void reportsTotals() throws Exception {
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.SUCCESS, 10, 100);
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.SUCCESS, 20, 200);
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.ERROR, 30, 50);
        record(this.gatewayId, this.downstreamB, "kb_b__fetch", CallStatus.TIMEOUT, 40, 30000);

        JsonNode totals = stats("").get("totals");

        assertThat(totals.get("calls").asInt()).isEqualTo(4);
        assertThat(totals.get("success").asInt()).isEqualTo(2);
        assertThat(totals.get("error").asInt()).isEqualTo(1);
        assertThat(totals.get("timeout").asInt()).isEqualTo(1);
        assertThat(totals.get("successRate").asDouble()).isEqualTo(0.5);
        // 最慢的那条 30 秒，P95 必须落在它附近而不是被平均掉
        assertThat(totals.get("p95DurationMs").asDouble()).isGreaterThan(20000);
        assertThat(totals.get("avgDurationMs").asDouble()).isCloseTo(7587.5, within(1.0));
    }

    /**
     * 没有调用时成功率是 null 而不是 0。
     *
     * "成功率 0%"是"调用全失败了"，和"这段时间一条调用都没有"完全是两回事，
     * 界面上要画成不同的东西。
     */
    @Test
    @DisplayName("没有调用时成功率是 null，不是 0")
    void emptyWindowHasNullRate() throws Exception {
        JsonNode totals = stats("").get("totals");

        assertThat(totals.get("calls").asInt()).isZero();
        assertThat(totals.get("successRate").isNull()).isTrue();
        assertThat(totals.get("p95DurationMs").isNull()).isTrue();
    }

    /** 还没结束的调用没有耗时，不能被当成 0 毫秒拉低分位。 */
    @Test
    @DisplayName("进行中的调用不参与耗时统计")
    void inFlightCallsDoNotSkewDurations() throws Exception {
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.SUCCESS, 10, 500);
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.STARTED, 5, 0);

        JsonNode totals = stats("").get("totals");

        assertThat(totals.get("calls").asInt()).isEqualTo(2);
        assertThat(totals.get("started").asInt()).isEqualTo(1);
        assertThat(totals.get("avgDurationMs").asDouble()).isEqualTo(500.0);
    }

    /**
     * 空桶必须补齐。
     *
     * 只把有数据的桶画出来，图上会把"这一小时没有调用"和"这一小时不存在"混成一件事。
     */
    @Test
    @DisplayName("时间序列补齐空桶，粒度随窗口长度切换")
    void seriesFillsEmptyBuckets() throws Exception {
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.SUCCESS, 30, 100);

        JsonNode hourly = stats("?from=" + NOW.minus(6, ChronoUnit.HOURS) + "&to=" + NOW.plusSeconds(1));
        assertThat(hourly.get("bucket").asText()).isEqualTo("HOUR");
        // 6 小时窗口 → 7 个整点桶（含两端所在的小时）
        assertThat(hourly.get("series")).hasSizeBetween(6, 8);
        int totalInSeries = 0;
        for (JsonNode point : hourly.get("series")) {
            totalInSeries += point.get("total").asInt();
        }
        assertThat(totalInSeries).isEqualTo(1);

        JsonNode daily = stats("?from=" + NOW.minus(10, ChronoUnit.DAYS) + "&to=" + NOW.plusSeconds(1));
        assertThat(daily.get("bucket").asText()).isEqualTo("DAY");
        assertThat(daily.get("series")).hasSizeBetween(10, 12);
    }

    /**
     * 一条调用都没有的网关也要留在列表里。
     *
     * 平时很忙的网关这段时间一条都没有，恰恰是最该看见的情况。
     */
    @Test
    @DisplayName("按网关分组，调用量为 0 的网关也在")
    void groupsByGatewayIncludingIdleOnes() throws Exception {
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.SUCCESS, 10, 100);
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.ERROR, 20, 100);

        JsonNode gateways = stats("").get("gateways");

        // 调用量高的排在最前
        assertThat(gateways.get(0).get("gatewayId").asText()).isEqualTo(this.gatewayId);

        JsonNode busy = findGateway(gateways, this.gatewayId);
        assertThat(busy.get("calls").asInt()).isEqualTo(2);
        assertThat(busy.get("failures").asInt()).isEqualTo(1);
        assertThat(busy.get("successRate").asDouble()).isEqualTo(0.5);
        // 派生状态也带上，一眼能看出"没调用"是因为网关本身就不可用
        assertThat(busy.get("status").asText()).isNotEmpty();

        JsonNode idle = findGateway(gateways, this.otherGatewayId);
        assertThat(idle.get("calls").asInt()).isZero();
        assertThat(idle.get("successRate").isNull()).isTrue();
    }

    @Test
    @DisplayName("指定网关后才有子 MCP 分布，且只统计这个网关")
    void downstreamBreakdownOnlyWhenScoped() throws Exception {
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.SUCCESS, 10, 100);
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.SUCCESS, 12, 100);
        record(this.gatewayId, this.downstreamB, "kb_b__fetch", CallStatus.ERROR, 15, 100);
        record(this.otherGatewayId, null, "other__tool", CallStatus.SUCCESS, 10, 100);

        assertThat(stats("").get("downstreams")).isEmpty();

        JsonNode scoped = stats("?gatewayId=" + this.gatewayId);
        assertThat(scoped.get("totals").get("calls").asInt()).isEqualTo(3);

        JsonNode downstreamStats = scoped.get("downstreams");
        assertThat(downstreamStats).hasSize(2);
        assertThat(downstreamStats.get(0).get("name").asText()).isEqualTo("kb_a");
        assertThat(downstreamStats.get(0).get("calls").asInt()).isEqualTo(2);
        assertThat(downstreamStats.get(1).get("name").asText()).isEqualTo("kb_b");
        assertThat(downstreamStats.get(1).get("failures").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("工具榜按调用量排序，错误码榜按出现次数排序")
    void ranksToolsAndErrorCodes() throws Exception {
        // 故意让两个工具的调用量不同，否则并列时只能按名字排，测不出"按调用量排序"
        for (int i = 0; i < 2; i++) {
            record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.SUCCESS, 10 + i, 100);
        }
        record(this.gatewayId, this.downstreamB, "kb_b__fetch", CallStatus.TIMEOUT, 20, 30000);
        record(this.gatewayId, this.downstreamB, "kb_b__fetch", CallStatus.ERROR, 21, 100);
        record(this.gatewayId, this.downstreamB, "kb_b__fetch", CallStatus.ERROR, 22, 100);

        JsonNode data = stats("");

        JsonNode tools = data.get("tools");
        assertThat(tools.get(0).get("name").asText()).isEqualTo("kb_b__fetch");
        assertThat(tools.get(0).get("calls").asInt()).isEqualTo(3);
        assertThat(tools.get(0).get("failures").asInt()).isEqualTo(3);
        assertThat(tools.get(1).get("name").asText()).isEqualTo("kb_a__search");
        assertThat(tools.get(1).get("calls").asInt()).isEqualTo(2);
        assertThat(tools.get(1).get("failures").asInt()).isZero();

        JsonNode errors = data.get("errorCodes");
        assertThat(errors.get(0).get("code").asText()).isEqualTo("DOWNSTREAM_ERROR");
        assertThat(errors.get(0).get("count").asInt()).isEqualTo(2);
        assertThat(errors.get(1).get("code").asText()).isEqualTo("DOWNSTREAM_TIMEOUT");
    }

    @Test
    @DisplayName("窗口外的调用不算进来")
    void windowIsRespected() throws Exception {
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.SUCCESS, 10, 100);
        record(this.gatewayId, this.downstreamA, "kb_a__search", CallStatus.SUCCESS, 60 * 24 * 3, 100);

        assertThat(stats("").get("totals").get("calls").asInt()).isEqualTo(1);
        assertThat(stats("?from=" + NOW.minus(5, ChronoUnit.DAYS))
                .get("totals").get("calls").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("参数非法明确报错，不给一页看着正常的空图")
    void rejectsInvalidParameters() throws Exception {
        this.mockMvc.perform(get("/api/stats?from=yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        this.mockMvc.perform(get("/api/stats?from=" + NOW + "&to=" + NOW.minusSeconds(60)))
                .andExpect(status().isBadRequest());

        // 超过窗口上限：跨度太大时日桶画出来只是一条毛刺线
        this.mockMvc.perform(get("/api/stats?from=" + NOW.minus(200, ChronoUnit.DAYS)))
                .andExpect(status().isBadRequest());

        this.mockMvc.perform(get("/api/stats?gatewayId=no-such-gateway"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GATEWAY_NOT_FOUND"));
    }

    /** 从网关列表里挑出某一个。别的测试类也会留下网关，不能按下标取。 */
    private static JsonNode findGateway(JsonNode gateways, String gatewayId) {
        for (JsonNode gateway : gateways) {
            if (gatewayId.equals(gateway.get("gatewayId").asText())) {
                return gateway;
            }
        }
        throw new AssertionError("gateway not in stats: " + gatewayId);
    }

    /** 统计接口只回聚合数字，正文连出现在 SELECT 里的机会都没有。 */
    @Test
    @DisplayName("统计响应里不含任何调用正文")
    void statsNeverCarryPayloads() throws Exception {
        String callId = UUID.randomUUID().toString();
        this.records.insertStarted(ToolCallRecord.started(callId, "t-1", this.gatewayId,
                this.downstreamA, "kb_a__search", "search",
                "{\"q\":\"SECRET-IN-REQUEST\"}", NOW.minusSeconds(60)));
        this.records.complete(callId, CallStatus.SUCCESS, "{\"text\":\"KB-BODY\"}", null, null,
                NOW.minusSeconds(59), 1000);

        String body = this.mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("SECRET-IN-REQUEST").doesNotContain("KB-BODY");
        assertThat(body).doesNotContain("requestJson").doesNotContain("responseJson");
    }
}
