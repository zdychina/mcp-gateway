package com.mcpgateway.service;

import com.mcpgateway.api.dto.CallRecordDetailResponse;
import com.mcpgateway.api.dto.CallRecordPageResponse;
import com.mcpgateway.api.dto.CallRecordSummaryResponse;
import com.mcpgateway.api.dto.ExtractedValue;
import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.ToolCallRecord;
import com.mcpgateway.domain.ToolCallSummary;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.ToolCallRecordRepository;
import com.mcpgateway.repository.ToolCallRecordRepository.CallRecordQuery;
import com.mcpgateway.repository.ToolCallRecordRepository.PayloadSlice;
import com.mcpgateway.service.CallPayloadExtractor.Spec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * 调用记录查询（需求 FR-06.5）。
 *
 * MVP originally 不提供这个能力，排障只能直接连库。补上它之后有两条硬约束：
 *
 * <ol>
 *   <li><b>按网关隔离</b>：每次查询都带上 gatewayId，取单条时还要再校验归属。
 *       否则拿到任意一个 callId 就能读到别的网关的调用内容。</li>
 *   <li><b>列表不返回正文</b>：requestJson / responseJson 只在取单条时返回。
 *       见 {@link com.mcpgateway.domain.ToolCallSummary} 的说明。</li>
 * </ol>
 */
@Service
public class CallRecordService {

    /** 每页默认条数。 */
    static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 每页上限。
     *
     * 卡这个上限不只是为了响应体大小：调用记录是这套系统里内容最敏感的数据，
     * 一次能捞走多少应该有个明确的天花板。
     */
    static final int MAX_PAGE_SIZE = 100;

    private final ToolCallRecordRepository records;

    private final GatewayService gatewayService;

    private final CallPayloadExtractor extractor;

    private final DownstreamMcpRepository downstreams;

    private final CallRecordExcelWriter excelWriter;

    public CallRecordService(ToolCallRecordRepository records, GatewayService gatewayService,
            CallPayloadExtractor extractor, DownstreamMcpRepository downstreams,
            CallRecordExcelWriter excelWriter) {
        this.records = records;
        this.gatewayService = gatewayService;
        this.extractor = extractor;
        this.downstreams = downstreams;
        this.excelWriter = excelWriter;
    }

    /**
     * 分页查询某个网关的调用记录，最近的在前。
     *
     * @param status  终态筛选，大小写不敏感；传不认识的值直接报错而不是静默忽略 ——
     *                静默忽略会让操作人以为"筛出来就这些"，而实际上根本没筛
     * @param extract 列表上要额外显示的正文字段，形如 request:/q；可空
     */
    @Transactional(readOnly = true)
    public CallRecordPageResponse search(String gatewayId, String downstreamMcpId, String toolName,
            String status, String traceId, String from, String to, Integer page, Integer size,
            List<String> extract) {

        this.gatewayService.requireGateway(gatewayId);

        // 先解析抽取列：路径写错了要在查库之前就报出来
        List<Spec> specs = this.extractor.parse(extract);

        int pageNumber = normalizePage(page);
        int pageSize = normalizeSize(size);
        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");
        if (fromInstant != null && toInstant != null && toInstant.isBefore(fromInstant)) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST, "to must not be earlier than from");
        }

        CallRecordQuery query = new CallRecordQuery(
                gatewayId,
                blankToNull(downstreamMcpId),
                blankToNull(toolName),
                parseStatus(status),
                blankToNull(traceId),
                fromInstant,
                toInstant,
                pageNumber * pageSize,
                pageSize);

        List<ToolCallSummary> summaries = this.records.search(query);
        Map<String, Map<String, ExtractedValue>> extracted = extractFor(gatewayId, summaries, specs);

        List<CallRecordSummaryResponse> items = summaries.stream()
                .map(summary -> CallRecordSummaryResponse.from(summary,
                        extracted.getOrDefault(summary.callId(), Map.of())))
                .toList();

        return new CallRecordPageResponse(items, pageNumber, pageSize,
                this.records.count(query), this.records.countByStatus(query));
    }

    /**
     * 取单条记录的完整内容，含入参和返回。
     *
     * 校验归属而不是只按 callId 查：callId 是 UUID，猜不到，但"猜不到"不是访问控制。
     */
    @Transactional(readOnly = true)
    public CallRecordDetailResponse detail(String gatewayId, String callId) {
        this.gatewayService.requireGateway(gatewayId);

        ToolCallRecord record = this.records.findByCallId(callId)
                .orElseThrow(() -> GatewayException.of(ErrorCode.CALL_RECORD_NOT_FOUND, "no such call record"));
        if (!record.gatewayId().equals(gatewayId)) {
            // 文案与"不存在"保持一致，不透露"这条记录存在但属于别的网关"
            throw GatewayException.of(ErrorCode.CALL_RECORD_NOT_FOUND, "no such call record");
        }
        return CallRecordDetailResponse.from(record);
    }

    // ---------------------------------------------------- 导出（列表的衍生）

    /**
     * 一次导出最多多少行。
     *
     * 和每页 100 条的上限是同一类约束，只是尺度不同：调用记录是这套系统里最敏感的数据，
     * 一次能带走多少必须有个明确的天花板。超出的部分截断，并在响应头和界面上明说，
     * 不静默给一份看着完整的文件。
     */
    public static final int MAX_EXPORT_ROWS = 5000;

    /** 分批查库的批大小。整页 5000 条连同正文一次性读进内存没有必要。 */
    private static final int EXPORT_CHUNK = 500;

    /** 内置列：键 → 表头、列宽、取值。顺序就是不指定 columns 时的默认顺序。 */
    private static final Map<String, BuiltInColumn> BUILT_IN_COLUMNS = builtInColumns();

    private record BuiltInColumn(String label, int width, BiFunction<ToolCallSummary,
            Map<String, String>, Object> value) {
    }

    private static Map<String, BuiltInColumn> builtInColumns() {
        String zone = ZoneId.systemDefault().getId();
        Map<String, BuiltInColumn> columns = new LinkedHashMap<>();
        // 时间写成真正的日期单元格，时区标在表头 —— 页面上是浏览器本地时间，两者可能不一致
        columns.put("startedAt", new BuiltInColumn("开始时间（" + zone + "）", 21,
                (summary, names) -> summary.startedAt()));
        columns.put("exposedToolName", new BuiltInColumn("聚合工具", 24,
                (summary, names) -> summary.exposedToolName()));
        columns.put("downstream", new BuiltInColumn("子 MCP", 14,
                (summary, names) -> summary.downstreamMcpId() == null
                        ? "（无路由目标）"
                        : names.getOrDefault(summary.downstreamMcpId(), summary.downstreamMcpId())));
        columns.put("originalToolName", new BuiltInColumn("原工具名", 18,
                (summary, names) -> summary.originalToolName()));
        columns.put("status", new BuiltInColumn("状态", 10,
                (summary, names) -> summary.status().name()));
        columns.put("durationMs", new BuiltInColumn("耗时(ms)", 11,
                (summary, names) -> summary.durationMs()));
        columns.put("errorCode", new BuiltInColumn("错误码", 20,
                (summary, names) -> summary.errorCode()));
        columns.put("errorMessage", new BuiltInColumn("错误摘要", 40,
                (summary, names) -> summary.errorMessage()));
        columns.put("finishedAt", new BuiltInColumn("结束时间（" + zone + "）", 21,
                (summary, names) -> summary.finishedAt()));
        columns.put("traceId", new BuiltInColumn("trace_id", 34,
                (summary, names) -> summary.traceId()));
        columns.put("callId", new BuiltInColumn("call_id", 38,
                (summary, names) -> summary.callId()));
        return Collections.unmodifiableMap(columns);
    }

    /** 导出的一列：内置列的 field 非空，抽取列的 spec 非空，两者互斥。 */
    private record ExportColumn(String label, int width, BuiltInColumn field, Spec spec) {
    }

    /**
     * 一次导出的计划。
     *
     * 先算好总数再开始写：{@code truncated} 要作为响应头发出去，而响应头必须在正文之前落定。
     */
    public static final class ExportPlan {

        private final String gatewayId;

        private final String gatewaySlug;

        private final CallRecordQuery query;

        private final List<ExportColumn> columns;

        private final int total;

        private ExportPlan(String gatewayId, String gatewaySlug, CallRecordQuery query,
                List<ExportColumn> columns, int total) {
            this.gatewayId = gatewayId;
            this.gatewaySlug = gatewaySlug;
            this.query = query;
            this.columns = columns;
            this.total = total;
        }

        /** 满足筛选条件的总条数，可能大于实际导出的行数。 */
        public int total() {
            return this.total;
        }

        /** 实际会写进文件的行数。 */
        public int rowCount() {
            return Math.min(this.total, MAX_EXPORT_ROWS);
        }

        /** 是否因为上限而截断。 */
        public boolean truncated() {
            return this.total > MAX_EXPORT_ROWS;
        }

        public String gatewaySlug() {
            return this.gatewaySlug;
        }
    }

    /**
     * 校验并规划一次导出，不查数据、不写文件。
     *
     * @param columns 要导出的列，按顺序；内置列用字段名，抽取列用 {@code request:/q} 这种
     *                （与列表接口的 extract 同一套语法）。为空则导出全部内置列
     * @param labels  列的表头文案，按同样的顺序；给了就必须一一对应。为空则用内置文案
     */
    @Transactional(readOnly = true)
    public ExportPlan prepareExport(String gatewayId, String downstreamMcpId, String toolName,
            String status, String traceId, String from, String to,
            List<String> columns, List<String> labels) {

        Gateway gateway = this.gatewayService.requireGateway(gatewayId);

        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");
        if (fromInstant != null && toInstant != null && toInstant.isBefore(fromInstant)) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST, "to must not be earlier than from");
        }
        if (toInstant == null) {
            /*
             * 没给上界就钉在"现在"。
             *
             * 导出是分批查的，中途新写进来的记录会把后面每一批的 offset 往后顶，
             * 边界上就会重复或漏掉一行。钉住上界之后这个窗口是稳定的。
             */
            toInstant = Instant.now();
        }

        CallRecordQuery query = new CallRecordQuery(gatewayId, blankToNull(downstreamMcpId),
                blankToNull(toolName), parseStatus(status), blankToNull(traceId),
                fromInstant, toInstant, 0, EXPORT_CHUNK);

        return new ExportPlan(gatewayId, gateway.slug(), query,
                resolveColumns(columns, labels), this.records.count(query));
    }

    private List<ExportColumn> resolveColumns(List<String> columns, List<String> labels) {
        List<String> keys = (columns == null || columns.isEmpty())
                ? List.copyOf(BUILT_IN_COLUMNS.keySet())
                : columns.stream().map(String::trim).filter(key -> !key.isEmpty()).toList();
        if (keys.isEmpty()) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST, "columns must not be empty");
        }
        if (labels != null && !labels.isEmpty() && labels.size() != keys.size()) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST,
                    "labels must have the same number of entries as columns");
        }

        // 带冒号的一律按抽取列解析，顺带复用列表接口那套校验（格式、数量上限）
        List<String> extractKeys = keys.stream().filter(key -> key.contains(":")).toList();
        Map<String, Spec> specs = new LinkedHashMap<>();
        for (Spec spec : this.extractor.parse(extractKeys)) {
            specs.put(spec.key(), spec);
        }

        List<ExportColumn> resolved = new ArrayList<>();
        for (int index = 0; index < keys.size(); index++) {
            String key = keys.get(index);
            String label = (labels == null || labels.isEmpty()) ? null : labels.get(index).trim();

            Spec spec = specs.get(key);
            if (spec != null) {
                resolved.add(new ExportColumn(
                        (label == null || label.isEmpty()) ? key : label, 40, null, spec));
                continue;
            }
            BuiltInColumn field = BUILT_IN_COLUMNS.get(key);
            if (field == null) {
                // 不静默丢掉：静默丢会导出一份少了列的文件，而没人会注意到少的是哪一列
                throw GatewayException.of(ErrorCode.INVALID_REQUEST, "unknown column: " + key);
            }
            resolved.add(new ExportColumn(
                    (label == null || label.isEmpty()) ? field.label() : label,
                    field.width(), field, null));
        }
        return resolved;
    }

    /**
     * 按计划把记录写成 .xlsx。
     *
     * 分批查库、分批写，内存占用与总行数无关。刻意不套事务：这一路要边查边往
     * HTTP 响应里写，把连接攥在一个长事务里等客户端收完不划算；查询窗口的稳定性
     * 由 {@link #prepareExport} 钉死的时间上界保证。
     */
    public void writeExport(ExportPlan plan, OutputStream out) throws IOException {
        List<Spec> specs = plan.columns.stream()
                .map(ExportColumn::spec).filter(Objects::nonNull).toList();
        Map<String, String> downstreamNames = this.downstreams.findByGatewayId(plan.gatewayId).stream()
                .collect(Collectors.toMap(DownstreamMcp::id, DownstreamMcp::name, (a, b) -> a));

        List<CallRecordExcelWriter.Column> header = plan.columns.stream()
                .map(column -> new CallRecordExcelWriter.Column(column.label(), column.width()))
                .toList();

        this.excelWriter.write(header, sink -> {
            int written = 0;
            int limit = plan.rowCount();
            while (written < limit) {
                int batch = Math.min(EXPORT_CHUNK, limit - written);
                CallRecordQuery chunkQuery = withWindow(plan.query, written, batch);
                List<ToolCallSummary> summaries = this.records.search(chunkQuery);
                if (summaries.isEmpty()) {
                    return;
                }
                Map<String, Map<String, ExtractedValue>> extracted =
                        extractFor(plan.gatewayId, summaries, specs);

                for (ToolCallSummary summary : summaries) {
                    sink.row(rowFor(summary, plan.columns, downstreamNames,
                            extracted.getOrDefault(summary.callId(), Map.of())));
                }
                written += summaries.size();
            }
        }, out);
    }

    private static CallRecordQuery withWindow(CallRecordQuery query, int offset, int limit) {
        return new CallRecordQuery(query.gatewayId(), query.downstreamMcpId(), query.toolName(),
                query.status(), query.traceId(), query.from(), query.to(), offset, limit);
    }

    private static List<Object> rowFor(ToolCallSummary summary, List<ExportColumn> columns,
            Map<String, String> downstreamNames, Map<String, ExtractedValue> extracted) {
        List<Object> cells = new ArrayList<>(columns.size());
        for (ExportColumn column : columns) {
            if (column.spec() != null) {
                cells.add(exportValue(extracted.get(column.spec().key())));
            }
            else {
                cells.add(column.field().value().apply(summary, downstreamNames));
            }
        }
        return cells;
    }

    /**
     * 抽取值在表格里的写法。
     *
     * 抽不到的三种原因区别对待：字段本来就没有 → 空单元格；另外两种要写出来，
     * 否则看表的人会以为下游返回里真的没这个内容。
     */
    private static Object exportValue(ExtractedValue value) {
        if (value == null) {
            return null;
        }
        return switch (value.state()) {
            case OK -> value.truncated() ? value.value() + "…" : value.value();
            case MISSING -> null;
            case NOT_JSON -> "（正文不是 JSON）";
            case TOO_LARGE -> "（正文过大，未抽取）";
        };
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 取回本页记录的正文并抽值。
     *
     * 没配抽取列时一次库都不多查 —— 不带 extract 参数的请求，行为与这个功能存在之前完全一致。
     */
    private Map<String, Map<String, ExtractedValue>> extractFor(String gatewayId,
            List<ToolCallSummary> summaries, List<Spec> specs) {
        if (specs.isEmpty() || summaries.isEmpty()) {
            return Map.of();
        }
        List<String> callIds = summaries.stream().map(ToolCallSummary::callId).toList();
        Map<String, PayloadSlice> slices = this.records.payloadsForExtraction(
                gatewayId, callIds, CallPayloadExtractor.MAX_PAYLOAD_CHARS);
        return this.extractor.extractAll(specs, slices);
    }

    private static int normalizePage(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST, "page must not be negative");
        }
        return page;
    }

    private static int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST, "size must be at least 1");
        }
        // 超过上限直接收敛到上限，不报错 —— 调用方拿到的 size 字段会说明实际用了多少
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static CallStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return CallStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST,
                    "status must be one of STARTED, SUCCESS, ERROR, TIMEOUT");
        }
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
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
