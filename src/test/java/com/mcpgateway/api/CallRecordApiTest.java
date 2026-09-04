package com.mcpgateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpgateway.AbstractApiTest;
import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.SyncStatus;
import com.mcpgateway.domain.ToolCallRecord;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.ToolCallRecordRepository;
import com.mcpgateway.service.CallPayloadExtractor;
import com.mcpgateway.service.CallRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 调用记录查询 API（需求 FR-06.5）。
 *
 * 这个接口比其他管理接口更敏感：取单条会返回子 MCP 的返回正文 ——
 * 登录挡住了匿名访问，但一把被偷走的会话在这里能捞到的东西远多于别处。
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

    @Autowired
    private JdbcClient jdbcClient;

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
     * 单条就可能接近 1 MiB。列表一次几十条把它们带上，等于让一次请求就能
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

    // ------------------------------------------------ 抽取列（需求 FR-06.5）

    /*
     * extract 是"列表不带正文"这条约束上唯一的开口：调用方点名要哪几个字段，
     * 服务端只回抽到的值。所以这一组用例既要证明它有用，也要证明它没把闸门拆了。
     */

    /** 写一条带指定正文的记录，用来验证抽取。 */
    private String recordWithPayload(String requestJson, String responseJson) {
        String callId = UUID.randomUUID().toString();
        this.records.insertStarted(ToolCallRecord.started(callId, "t-extract", this.gatewayId,
                this.downstreamId, "kb_a__search", "search", requestJson, BASE));
        this.records.complete(callId, CallStatus.SUCCESS, responseJson, null, null,
                BASE.plusMillis(120), 120);
        return callId;
    }

    @Test
    @DisplayName("按 JSON Pointer 从入参和返回里抽字段到列表上")
    void extractsConfiguredFieldsFromPayloads() throws Exception {
        recordWithPayload("{\"q\":\"" + REQUEST_MARKER + "\",\"top\":5}",
                "{\"content\":[{\"text\":\"" + RESPONSE_MARKER + "\"}]}");

        JsonNode page = search("?extract=request:/q&extract=response:/content/0/text");

        assertThat(page.at("/items/0/extracted/request:~1q/value").asText()).isEqualTo(REQUEST_MARKER);
        assertThat(page.at("/items/0/extracted/request:~1q/state").asText()).isEqualTo("OK");
        assertThat(page.at("/items/0/extracted/response:~1content~10~1text/value").asText())
                .isEqualTo(RESPONSE_MARKER);
    }

    /** 逗号分隔和重复传参是同一回事 —— 前端拼哪种都不该有区别。 */
    @Test
    @DisplayName("extract 可以逗号分隔，也可以重复传")
    void acceptsBothParameterStyles() throws Exception {
        recordWithPayload("{\"q\":\"" + REQUEST_MARKER + "\"}", "{\"ok\":true}");

        JsonNode page = search("?extract=request:/q,response:/ok");

        assertThat(page.at("/items/0/extracted/request:~1q/value").asText()).isEqualTo(REQUEST_MARKER);
        assertThat(page.at("/items/0/extracted/response:~1ok/value").asText()).isEqualTo("true");
    }

    @Test
    @DisplayName("不要求抽取时行为和从前完全一致：extracted 是空的")
    void extractionIsOptIn() throws Exception {
        recordWithPayload("{\"q\":\"" + REQUEST_MARKER + "\"}", "{\"ok\":true}");

        JsonNode page = search("");

        assertThat(page.at("/items/0/extracted").isObject()).isTrue();
        assertThat(page.at("/items/0/extracted")).isEmpty();
    }

    /**
     * 开了抽取列，正文本身依然不进列表。
     *
     * 这条是整个功能的边界：抽取列放出去的是"调用方点名的一个字段、最多 200 字符"，
     * 而不是把 SECURITY.md 里那条闸门拆掉。
     */
    @Test
    @DisplayName("即使开着抽取列，列表也不返回正文字段本身")
    void extractionStillNeverCarriesWholePayloads() throws Exception {
        recordWithPayload("{\"q\":\"" + REQUEST_MARKER + "\",\"secret\":\"" + RESPONSE_MARKER + "\"}",
                "{\"content\":[{\"text\":\"" + RESPONSE_MARKER + "\"}]}");

        MvcResult result = this.mockMvc.perform(get("/api/gateways/" + this.gatewayId
                        + "/call-records?extract=request:/q"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();

        // 点名要的字段在
        assertThat(body).contains(REQUEST_MARKER);
        // 没点名的一律不在：既没有正文字段，也没有正文里的其他内容
        assertThat(body).doesNotContain("requestJson").doesNotContain("responseJson");
        assertThat(body).doesNotContain(RESPONSE_MARKER);
    }

    @Test
    @DisplayName("抽到的值超过 200 字符会被截断，并标出来")
    void truncatesLongValues() throws Exception {
        recordWithPayload("{\"q\":\"" + "x".repeat(500) + "\"}", "{}");

        JsonNode value = search("?extract=request:/q").at("/items/0/extracted/request:~1q");

        assertThat(value.get("value").asText()).hasSize(200);
        assertThat(value.get("truncated").asBoolean()).isTrue();
    }

    /**
     * 抽不到值有好几种原因，界面上要能分开 ——
     * "路径写错了"和"这条正文太大没解析"该做的下一步完全不同。
     */
    @Test
    @DisplayName("抽不到时说明原因：字段不存在 / 不是 JSON / 正文过大")
    void reportsWhyAValueIsMissing() throws Exception {
        recordWithPayload("{\"q\":\"hi\"}", "{}");
        JsonNode missing = search("?extract=request:/no_such_field")
                .at("/items/0/extracted/request:~1no_such_field");
        assertThat(missing.get("state").asText()).isEqualTo("MISSING");
        assertThat(missing.get("value").isNull()).isTrue();

        // 打点时序列化失败写下的占位符，或历史遗留的坏数据
        String callId = UUID.randomUUID().toString();
        this.records.insertStarted(ToolCallRecord.started(callId, "t-bad", this.gatewayId,
                this.downstreamId, "kb_a__broken", "broken", "这不是 JSON", BASE.plusSeconds(1)));
        JsonNode notJson = search("?toolName=broken&extract=request:/q")
                .at("/items/0/extracted/request:~1q");
        assertThat(notJson.get("state").asText()).isEqualTo("NOT_JSON");

        // 超过解析上限的正文一个字符都不读进来
        String bigCallId = UUID.randomUUID().toString();
        String big = "{\"q\":\"" + "y".repeat(CallPayloadExtractor.MAX_PAYLOAD_CHARS) + "\"}";
        this.records.insertStarted(ToolCallRecord.started(bigCallId, "t-big", this.gatewayId,
                this.downstreamId, "kb_a__big", "big", big, BASE.plusSeconds(2)));
        JsonNode tooLarge = search("?toolName=big&extract=request:/q")
                .at("/items/0/extracted/request:~1q");
        assertThat(tooLarge.get("state").asText()).isEqualTo("TOO_LARGE");
        assertThat(tooLarge.get("value").isNull()).isTrue();
    }

    @Test
    @DisplayName("空 pointer 抽整个文档，相当于一个截断到 200 字符的预览列")
    void emptyPointerYieldsAPreview() throws Exception {
        recordWithPayload("{\"q\":\"" + REQUEST_MARKER + "\"}", "{}");

        JsonNode value = search("?extract=request:").at("/items/0/extracted/request:");

        assertThat(value.get("value").asText()).isEqualTo("{\"q\":\"" + REQUEST_MARKER + "\"}");
    }

    @Test
    @DisplayName("路径写错直接报错，不是给一整列空白")
    void rejectsMalformedExtract() throws Exception {
        // 静默忽略会让人对着一整列"—"以为是数据里没有这个字段
        assertThat(searchStatus("?extract=req:/q")).isEqualTo(400);
        assertThat(searchStatus("?extract=request")).isEqualTo(400);
        // 点号路径是最常见的手误
        assertThat(searchStatus("?extract=request:q")).isEqualTo(400);
        // 超过数量上限
        assertThat(searchStatus("?extract=request:/a&extract=request:/b&extract=request:/c"
                + "&extract=request:/d&extract=request:/e")).isEqualTo(400);

        this.mockMvc.perform(get("/api/gateways/" + this.gatewayId + "/call-records?extract=req:/q"))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private int searchStatus(String query) throws Exception {
        return this.mockMvc.perform(get("/api/gateways/" + this.gatewayId + "/call-records" + query))
                .andReturn().getResponse().getStatus();
    }

    // ------------------------------------------------ 导出 Excel（列表的衍生）

    /*
     * 导出是整个管理 API 里单次能带走内容最多的接口，所以这一组用例盯三件事：
     * 导出的确实是筛选后的那些行、列由调用方说了算、以及行数上限真的生效且说得出口。
     */

    private byte[] exportBytes(String query) throws Exception {
        MvcResult result = this.mockMvc.perform(
                get("/api/gateways/" + this.gatewayId + "/call-records/export" + query))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsByteArray();
    }

    /** 把工作簿读成纯文本的行，日期按单元格格式渲染。 */
    private static List<List<String>> rowsOf(byte[] xlsx) throws IOException {
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int index = 0; index < row.getLastCellNum(); index++) {
                    Cell cell = row.getCell(index);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(cells);
            }
            return rows;
        }
    }

    @Test
    @DisplayName("导出当前筛选结果，表头 + 数据行都在")
    void exportsFilteredRecordsAsXlsx() throws Exception {
        record(this.gatewayId, this.downstreamId, "kb_a__search", CallStatus.SUCCESS, "t-1", 30);
        record(this.gatewayId, this.downstreamId, "kb_a__ping", CallStatus.ERROR, "t-2", 20);
        record(this.gatewayId, this.downstreamId, "kb_a__lookup", CallStatus.SUCCESS, "t-3", 10);

        List<List<String>> rows = rowsOf(exportBytes("?status=ERROR"));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).contains("聚合工具", "状态", "trace_id");
        assertThat(rows.get(1)).contains("kb_a__ping", "ERROR", "t-2");
        // 时间是真正的日期单元格，按 yyyy-mm-dd hh:mm:ss 渲染，Excel 里能排序能筛选
        assertThat(rows.get(1).get(0)).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("导出哪些列、表头写什么，由调用方决定")
    void exportRespectsRequestedColumnsAndLabels() throws Exception {
        record(this.gatewayId, this.downstreamId, "kb_a__search", CallStatus.SUCCESS, "t-1", 5);

        List<List<String>> rows = rowsOf(exportBytes(
                "?columns=status&columns=traceId&labels=状态&labels=链路"));

        assertThat(rows.get(0)).containsExactly("状态", "链路");
        assertThat(rows.get(1)).containsExactly("SUCCESS", "t-1");
    }

    @Test
    @DisplayName("正文抽取列在导出里同样可用")
    void exportCanIncludePayloadFields() throws Exception {
        recordWithPayload("{\"q\":\"" + REQUEST_MARKER + "\"}",
                "{\"content\":[{\"text\":\"" + RESPONSE_MARKER + "\"}]}");

        List<List<String>> rows = rowsOf(exportBytes(
                "?columns=exposedToolName&columns=request:/q&labels=工具&labels=查询词"));

        assertThat(rows.get(0)).containsExactly("工具", "查询词");
        assertThat(rows.get(1)).containsExactly("kb_a__search", REQUEST_MARKER);
    }

    /**
     * 导出内容里有 Agent 传来的参数和下游返回的正文。
     *
     * 以 = + - @ 开头的单元格在 CSV 里会被 Excel 当公式执行 —— 这正是这个功能没做成 CSV
     * 的原因。xlsx 的公式是独立的单元格类型，只要不主动写就不存在这条路。
     */
    @Test
    @DisplayName("单元格一律是文本，不会变成公式")
    void exportNeverWritesFormulas() throws Exception {
        String injection = "=cmd|' /C calc'!A0";
        recordWithPayload("{\"q\":\"" + injection + "\"}", "{}");

        byte[] xlsx = exportBytes("?columns=request:/q&labels=查询词");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Cell cell = workbook.getSheetAt(0).getRow(1).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo(injection);
        }
    }

    @Test
    @DisplayName("超过上限就截断，并在响应头里说清楚 —— 不给一份看着完整的文件")
    void exportCapsRowsAndSaysSo() throws Exception {
        seedBulkRecords(CallRecordService.MAX_EXPORT_ROWS + 1);

        MvcResult result = this.mockMvc.perform(
                get("/api/gateways/" + this.gatewayId + "/call-records/export?columns=callId"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Truncated", "true"))
                .andExpect(header().string("X-Export-Rows",
                        String.valueOf(CallRecordService.MAX_EXPORT_ROWS)))
                .andExpect(header().string("X-Export-Total",
                        String.valueOf(CallRecordService.MAX_EXPORT_ROWS + 1)))
                .andReturn();

        // 表头 + 5000 行
        assertThat(rowsOf(result.getResponse().getContentAsByteArray()))
                .hasSize(CallRecordService.MAX_EXPORT_ROWS + 1);
    }

    @Test
    @DisplayName("没截断时也把总数和行数说出来")
    void exportReportsCountsWhenComplete() throws Exception {
        record(this.gatewayId, this.downstreamId, "kb_a__search", CallStatus.SUCCESS, "t-1", 5);

        this.mockMvc.perform(get("/api/gateways/" + this.gatewayId + "/call-records/export"))
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andExpect(header().string("X-Export-Total", "1"))
                .andExpect(header().string("X-Export-Rows", "1"));
    }

    @Test
    @DisplayName("列名写错直接报错，不是悄悄少导一列")
    void rejectsBadExportColumns() throws Exception {
        this.mockMvc.perform(get("/api/gateways/" + this.gatewayId
                        + "/call-records/export?columns=noSuchColumn"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        // labels 给了就必须和 columns 一一对应，否则表头会错位
        this.mockMvc.perform(get("/api/gateways/" + this.gatewayId
                        + "/call-records/export?columns=status&columns=traceId&labels=状态"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        // 抽取列的路径同样走列表接口那套校验
        this.mockMvc.perform(get("/api/gateways/" + this.gatewayId
                        + "/call-records/export?columns=request:q"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("文件名带上网关 slug，中文名走 RFC 5987")
    void exportFileNameCarriesGatewaySlug() throws Exception {
        record(this.gatewayId, this.downstreamId, "kb_a__search", CallStatus.SUCCESS, "t-1", 5);

        MvcResult result = this.mockMvc.perform(
                get("/api/gateways/" + this.gatewayId + "/call-records/export"))
                .andExpect(status().isOk())
                .andReturn();

        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).startsWith("attachment;");
        // ASCII 回退 + UTF-8 版本各一份
        assertThat(disposition).contains("filename=\"call-records-").contains("filename*=UTF-8''");
        assertThat(result.getResponse().getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    /** 一条 INSERT 造出很多行，逐条插 5001 次太慢。 */
    private void seedBulkRecords(int count) {
        this.jdbcClient.sql("""
                INSERT INTO tool_call_record (call_id, trace_id, gateway_id, downstream_mcp_id,
                        exposed_tool_name, original_tool_name, request_json, response_json,
                        status, error_code, error_message, started_at, finished_at, duration_ms)
                SELECT 'bulk-' || X, 'bulk-trace', :gatewayId, :downstreamId,
                       'kb_a__bulk', 'bulk', '{}', '{}', 'SUCCESS', NULL, NULL,
                       DATEADD('SECOND', -X, CURRENT_TIMESTAMP),
                       DATEADD('SECOND', -X, CURRENT_TIMESTAMP), 10
                  FROM SYSTEM_RANGE(1, :count)
                """)
                .param("gatewayId", this.gatewayId)
                .param("downstreamId", this.downstreamId)
                .param("count", count)
                .update();
    }
}
