package com.mcpgateway.api;

import com.mcpgateway.api.dto.CallRecordDetailResponse;
import com.mcpgateway.api.dto.CallRecordPageResponse;
import com.mcpgateway.service.CallRecordService;
import com.mcpgateway.service.CallRecordService.ExportPlan;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 调用记录查询 API（需求 FR-06.5）。
 *
 * MVP 阶段这块是空的，排障只能停掉应用连 H2 查库。补上之后要记住：
 * <b>取单条的接口会返回知识库正文</b>（requestJson / responseJson 按 FR-06.4 原样保存），
 * 登录之后它不再是匿名可读，但仍是整个管理 API 里唯一会吐出业务正文的地方：
 * 一把被偷走的会话在这里能捞到的东西远多于别处，所以列表不带正文的约束继续保留，
 * 见 SECURITY.md。
 *
 * 只有 GET：调用记录是打点产生的事实，不接受人工增删改。清理策略见 SECURITY.md 的已知限制。
 */
@RestController
@RequestMapping("/api/gateways/{gatewayId}/call-records")
public class CallRecordController {

    private final CallRecordService callRecordService;

    public CallRecordController(CallRecordService callRecordService) {
        this.callRecordService = callRecordService;
    }

    /**
     * 分页查询，最近的在前。所有筛选条件都可省略。
     *
     * @param downstreamMcpId 按子 MCP 精确筛选
     * @param toolName        按聚合工具名包含匹配，可用子 MCP 前缀（如 {@code kb_a__}）筛出一组
     * @param status          STARTED / SUCCESS / ERROR / TIMEOUT，大小写不敏感
     * @param traceId         按链路精确筛选（需求 15.4.3）
     * @param from            起始时间，ISO-8601 instant，含
     * @param to              结束时间，ISO-8601 instant，不含
     * @param page            页码，从 0 开始
     * @param size            每页条数，默认 20，上限 100
     * @param extract         列表上额外要显示的正文字段，形如 {@code request:/q}、
     *                        {@code response:/content/0/text}，最多 4 个，可重复传也可用逗号分隔。
     *                        只返回抽到的值（每个最长 200 字符），正文本身仍然不会出现在列表里，
     *                        见 {@link com.mcpgateway.service.CallPayloadExtractor}
     */
    @GetMapping
    public ApiResponse<CallRecordPageResponse> search(
            @PathVariable String gatewayId,
            @RequestParam(required = false) String downstreamMcpId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> extract) {

        return ApiResponse.ok(this.callRecordService.search(
                gatewayId, downstreamMcpId, toolName, status, traceId, from, to, page, size, extract));
    }

    /** 文件名里的时间戳，形如 20260904-0130。 */
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    /**
     * 把当前筛选结果导出成 .xlsx。
     *
     * <p>筛选参数与列表接口完全一致；额外的 {@code columns} / {@code labels} 决定导出哪些列、
     * 表头写什么，含冒号的列名（如 {@code request:/q}）按抽取列处理，语义与列表接口的
     * {@code extract} 相同。不给 columns 就导全部内置列。
     *
     * <p><b>最多 {@value com.mcpgateway.service.CallRecordService#MAX_EXPORT_ROWS} 行。</b>
     * 超出时截断，并在 {@code X-Export-Truncated} / {@code X-Export-Total} 两个响应头里说明 ——
     * 静默给一份看着完整的文件比少给几行糟糕得多。这是整个管理 API 里单次能带走内容最多的
     * 接口，见 SECURITY.md。
     *
     * <p>响应体是文件流，不套 {@code {success,data,error}} 信封。校验全部发生在开始写之前，
     * 所以参数错误仍然是一个正常的 JSON 错误响应。
     */
    @GetMapping("/export")
    public void export(
            @PathVariable String gatewayId,
            @RequestParam(required = false) String downstreamMcpId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) List<String> columns,
            @RequestParam(required = false) List<String> labels,
            HttpServletResponse response) throws IOException {

        ExportPlan plan = this.callRecordService.prepareExport(
                gatewayId, downstreamMcpId, toolName, status, traceId, from, to, columns, labels);

        String fileName = "调用记录-%s-%s.xlsx".formatted(plan.gatewaySlug(),
                ZonedDateTime.now(ZoneId.systemDefault()).format(FILE_STAMP));

        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        // 两份文件名：ASCII 回退给老客户端，filename* 给认识 RFC 5987 的浏览器
        response.setHeader("Content-Disposition",
                "attachment; filename=\"call-records-%s.xlsx\"; filename*=UTF-8''%s".formatted(
                        plan.gatewaySlug(),
                        URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")));
        response.setHeader("X-Export-Total", String.valueOf(plan.total()));
        response.setHeader("X-Export-Rows", String.valueOf(plan.rowCount()));
        response.setHeader("X-Export-Truncated", String.valueOf(plan.truncated()));

        this.callRecordService.writeExport(plan, response.getOutputStream());
    }

    /** 单条记录的完整内容，含入参和返回。归属校验在 service 里做。 */
    @GetMapping("/{callId}")
    public ApiResponse<CallRecordDetailResponse> detail(@PathVariable String gatewayId,
            @PathVariable String callId) {
        return ApiResponse.ok(this.callRecordService.detail(gatewayId, callId));
    }
}
