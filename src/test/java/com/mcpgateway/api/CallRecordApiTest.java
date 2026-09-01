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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 调用记录查询 API（需求 FR-06.5）。
 *
 * 这个接口比其他管理接口更敏感：取单条会返回子 MCP 的返回正文，而管理端没有登录。
 * 所以除了功能，还要盯住两条边界 —— 列表不带正文，以及记录严格按网关隔离。
 */
class CallRecordApiTest extends AbstractApiTest {

    /** 出现在入参里的标志串，用来验证列表**没有**把正文带出来。 */
    private static final String REQUEST_MARKER = "SECRET-QUESTION-IN-REQUEST";

    /** 出现在返回里的标志串，代表知识库正文。 */
    private static final String RESPONSE_MARKER = "KNOWLEDGE-BASE-BODY";

    private static final Instant BASE = Instant.parse("2026-08-31T10:00:00Z");

    @Autowired
    private ToolCallRecordRepository records;

    @Autowired
    private DownstreamMcpRepository downstreams;

    private String gatewayId;

    private String downstreamId;

    @BeforeEach
    void seedGateway() throws Exception {
        this.gatewayId = createGateway();
        this.downstreamId = seedDownstream(this.gatewayId, "kb_a");
    }

    // ------------------------------------------------------------------ 夹具

    private String createGateway() throws Exception {
        MvcResult result = this.mockMvc.perform(post("/api/gateways")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"记录网关\",\"slug\":\"%s\"}".formatted(uniqueSlug("rec"))))
                .andExpect(status().isCreated())
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/gateway/id").asText();
    }

    private String seedDownstream(String owner, String name) {
        DownstreamMcp downstream = new DownstreamMcp(UUID.randomUUID().toString(), owner, name,
                DownstreamMcp.TYPE_STREAMABLE_HTTP, "https://kb.example.com/mcp", null,
                SyncStatus.SUCCESS, BASE, null, BASE, BASE);
        this.downstreams.insert(downstream);
        return downstream.id();
    }

    /** 写一条已经结束的记录。minutesAgo 越大越早。 */
    private String record(String owner, String downstream, String toolName, CallStatus status,
            String traceId, int minutesAgo) {
        String callId = UUID.randomUUID().toString();
        Instant startedAt = BASE.minus(minutesAgo, ChronoUnit.MINUTES);
        this.records.insertStarted(ToolCallRecord.started(callId, traceId, owner, downstream,
                toolName, toolName.contains("__") ? toolName.split("__")[1] : toolName,
                "{\"q\":\"" + REQUEST_MARKER + "\"}", startedAt));

        if (status != CallStatus.STARTED) {
            String response = status == CallStatus.SUCCESS
                    ? "{\"content\":[{\"text\":\"" + RESPONSE_MARKER + "\"}]}"
                    : null;
            String errorCode = status == CallStatus.SUCCESS ? null : "DOWNSTREAM_TIMEOUT";
            String errorMessage = status == CallStatus.SUCCESS ? null : "kb_a: downstream call timed out";
            this.records.complete(callId, status, response, errorCode, errorMessage,
                    startedAt.plusMillis(120), 120);
        }
        return callId;
    }

    private JsonNode search(String query) throws Exception {
        MvcResult result = this.mockMvc.perform(
                get("/api/gateways/" + this.gatewayId + "/call-records" + query))
                .andExpect(status().isOk())
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("需求 FR-06.5：分页返回调用记录，最近的在前")
    void returnsRecordsNewestFirst() throws Exception {
        record(this.gatewayId, this.downstreamId, "kb_a__search", CallStatus.SUCCESS, "t-1", 30);
        record(this.gatewayId, this.downstreamId, "kb_a__ping", CallStatus.ERROR, "t-2", 10);
        record(this.gatewayId, this.downstreamId, "kb_a__lookup", CallStatus.SUCCESS, "t-3", 20);

        JsonNode page = search("");

        assertThat(page.get("total").asInt()).isEqualTo(3);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("items")).hasSize(3);
        // 10 分钟前的最新，30 分钟前的最旧
        assertThat(page.at("/items/0/exposedToolName").asText()).isEqualTo("kb_a__ping");
        assertThat(page.at("/items/1/exposedToolName").asText()).isEqualTo("kb_a__lookup");
        assertThat(page.at("/items/2/exposedToolName").asText()).isEqualTo("kb_a__search");
    }

    /**
     * 这条是这个接口最重要的边界。
     *
     * request_json / response_json 按 FR-06.4 原样保存不截断，装的是知识库正文，
     * 单条就可能接近 1 MiB。列表一次几十条把它们带上，等于在一个没有登录的接口上
     * 成批摊开业务内容。
     */
    @Test
    @DisplayName("列表不返回入参和返回正文 —— 那是单条接口才给的")
    void listNeverCarriesPayloads() throws Exception {
        record(this.gatewayId, this.downstreamId, "kb_a__search", CallStatus.SUCCESS, "t-1", 5);

        MvcResult result = this.mockMvc.perform(
                get("/api/gateways/" + this.gatewayId + "/call-records"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(body).doesNotContain(REQUEST_MARKER).doesNotContain(RESPONSE_MARKER);
        assertThat(body).doesNotContain("requestJson").doesNotContain("responseJson");
        // 但摘要字段该有的都有
        assertThat(body).contains("kb_a__search").contains("SUCCESS").contains("durationMs");
    }

    @Test
    @DisplayName("取单条返回完整入参和返回")
    void detailCarriesPayloads() throws Exception {
        String callId = record(this.gatewayId, this.downstreamId, "kb_a__search",
                CallStatus.SUCCESS, "t-detail", 5);

        this.mockMvc.perform(get("/api/gateways/" + this.gatewayId + "/call-records/" + callId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.callId").value(callId))
                .andExpect(jsonPath("$.data.traceId").value("t-detail"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.requestJson").value("{\"q\":\"" + REQUEST_MARKER + "\"}"))
                .andExpect(jsonPath("$.data.responseJson")
                        .value("{\"content\":[{\"text\":\"" + RESPONSE_MARKER + "\"}]}"));
    }

    /**
     * callId 是 UUID，猜不到 —— 但"猜不到"不是访问控制。
     * 拿着别的网关的 callId 来查，必须和"不存在"一个待遇。
     */
    @Test
    @DisplayName("记录按网关隔离：拿别的网关的 callId 查不到")
    void recordsAreScopedToTheirGateway() throws Exception {
        String otherGateway = createGateway();
        String otherDownstream = seedDownstream(otherGateway, "kb_b");
        String foreignCallId = record(otherGateway, otherDownstream, "kb_b__search",
                CallStatus.SUCCESS, "t-foreign", 5);

        // 列表里看不到
        assertThat(search("").get("total").asInt()).isZero();

        // 单条也拿不到，且文案不透露"它存在但属于别人"
        this.mockMvc.perform(get("/api/gateways/" + this.gatewayId + "/call-records/" + foreignCallId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CALL_RECORD_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("no such call record"));
    }

    @Test
    @DisplayName("按状态、工具名、trace_id 和时间范围筛选")
    void supportsFiltering() throws Exception {
        record(this.gatewayId, this.downstreamId, "kb_a__search", CallStatus.SUCCESS, "trace-aaa", 30);
        record(this.gatewayId, this.downstreamId, "kb_a__search", CallStatus.ERROR, "trace-bbb", 20);
        record(this.gatewayId, this.downstreamId, "kb_a__ping", CallStatus.TIMEOUT, "trace-ccc", 10);

        assertThat(search("?status=ERROR").get("total").asInt()).isEqualTo(1);
        // 大小写不敏感
        assertThat(search("?status=timeout").get("total").asInt()).isEqualTo(1);

        // 工具名是包含匹配，可以用子 MCP 前缀一次筛出一组
        assertThat(search("?toolName=kb_a__").get("total").asInt()).isEqualTo(3);
        assertThat(search("?toolName=search").get("total").asInt()).isEqualTo(2);

        assertThat(search("?traceId=trace-bbb").get("total").asInt()).isEqualTo(1);

        // from 含、to 不含
        String from = BASE.minus(25, ChronoUnit.MINUTES).toString();
        assertThat(search("?from=" + from).get("total").asInt()).isEqualTo(2);
        String to = BASE.minus(15, ChronoUnit.MINUTES).toString();
        assertThat(search("?to=" + to).get("total").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("分页：page 从 0 开始，total 是筛选后的总数")
    void supportsPaging() throws Exception {
        for (int i = 0; i < 5; i++) {
            record(this.gatewayId, this.downstreamId, "kb_a__search", CallStatus.SUCCESS, "t-" + i, i);
        }

        JsonNode first = search("?size=2");
        assertThat(first.get("total").asInt()).isEqualTo(5);
        assertThat(first.get("items")).hasSize(2);

        JsonNode last = search("?size=2&page=2");
        assertThat(last.get("items")).hasSize(1);
        assertThat(last.get("page").asInt()).isEqualTo(2);

        assertThat(search("?size=2&page=99").get("items")).isEmpty();
    }

    /**
     * statusCounts 是给界面做分面筛选的：已经筛了 ERROR 还只显示 ERROR 的数量，
     * 就没法用它切换到别的状态了。
     */
    @Test
    @DisplayName("状态分布不套用 status 筛选，但套用其他筛选")
    void statusCountsIgnoreTheStatusFilterOnly() throws Exception {
        record(this.gatewayId, this.downstreamId, "kb_a__search", CallStatus.SUCCESS, "t-1", 30);
        record(this.gatewayId, this.downstreamId, "kb_a__search", CallStatus.ERROR, "t-2", 20);
        record(this.gatewayId, this.downstreamId, "kb_a__ping", CallStatus.TIMEOUT, "t-3", 10);

        JsonNode filtered = search("?status=ERROR");
        assertThat(filtered.get("total").asInt()).isEqualTo(1);
        // 分布仍是全量
        assertThat(filtered.at("/statusCounts/SUCCESS").asInt()).isEqualTo(1);
        assertThat(filtered.at("/statusCounts/ERROR").asInt()).isEqualTo(1);
        assertThat(filtered.at("/statusCounts/TIMEOUT").asInt()).isEqualTo(1);

        // 但工具名这类条件照常参与
        JsonNode byTool = search("?toolName=ping&status=SUCCESS");
        assertThat(byTool.at("/statusCounts/TIMEOUT").asInt()).isEqualTo(1);
        assertThat(byTool.at("/statusCounts/SUCCESS").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("未知工具的调用同样留下记录，只是没有子 MCP 和原工具名")
    void keepsRecordsWithoutARoutingTarget() throws Exception {
        String callId = UUID.randomUUID().toString();
        this.records.insertStarted(ToolCallRecord.started(callId, "t-unknown", this.gatewayId,
                null, "no_such_tool", null, "{}", BASE));
        this.records.complete(callId, CallStatus.ERROR, null, "TOOL_NOT_FOUND",
                "tool not found or disabled: no_such_tool", BASE.plusMillis(3), 3);

        JsonNode page = search("?toolName=no_such_tool");

        assertThat(page.get("total").asInt()).isEqualTo(1);
        assertThat(page.at("/items/0/downstreamMcpId").isNull()).isTrue();
        assertThat(page.at("/items/0/originalToolName").isNull()).isTrue();
        assertThat(page.at("/items/0/errorCode").asText()).isEqualTo("TOOL_NOT_FOUND");
    }

    @Test
    @DisplayName("非法参数明确报错，不静默忽略")
    void rejectsInvalidParameters() throws Exception {
        // 静默忽略会让操作人以为"筛出来就这些"，而实际上根本没筛
        this.mockMvc.perform(get("/api/gateways/" + this.gatewayId + "/call-records?status=NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        this.mockMvc.perform(get("/api/gateways/" + this.gatewayId + "/call-records?from=yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        this.mockMvc.perform(get("/api/gateways/" + this.gatewayId
                        + "/call-records?from=2026-08-31T10:00:00Z&to=2026-08-30T10:00:00Z"))
                .andExpect(status().isBadRequest());

        this.mockMvc.perform(get("/api/gateways/" + this.gatewayId + "/call-records?page=-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("每页条数有上限，超了收敛到上限而不是报错")
    void capsPageSize() throws Exception {
        JsonNode page = search("?size=5000");

        assertThat(page.get("size").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("网关不存在时返回 GATEWAY_NOT_FOUND")
    void unknownGatewayIsRejected() throws Exception {
        this.mockMvc.perform(get("/api/gateways/no-such-gateway/call-records"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GATEWAY_NOT_FOUND"));
    }
}
