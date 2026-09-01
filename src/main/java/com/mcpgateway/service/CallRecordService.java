package com.mcpgateway.service;

import com.mcpgateway.api.dto.CallRecordDetailResponse;
import com.mcpgateway.api.dto.CallRecordPageResponse;
import com.mcpgateway.api.dto.CallRecordSummaryResponse;
import com.mcpgateway.domain.CallStatus;
import com.mcpgateway.domain.ToolCallRecord;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.repository.ToolCallRecordRepository;
import com.mcpgateway.repository.ToolCallRecordRepository.CallRecordQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

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

    public CallRecordService(ToolCallRecordRepository records, GatewayService gatewayService) {
        this.records = records;
        this.gatewayService = gatewayService;
    }

    /**
     * 分页查询某个网关的调用记录，最近的在前。
     *
     * @param status 终态筛选，大小写不敏感；传不认识的值直接报错而不是静默忽略 ——
     *               静默忽略会让操作人以为"筛出来就这些"，而实际上根本没筛
     */
    @Transactional(readOnly = true)
    public CallRecordPageResponse search(String gatewayId, String downstreamMcpId, String toolName,
            String status, String traceId, String from, String to, Integer page, Integer size) {

        this.gatewayService.requireGateway(gatewayId);

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

        List<CallRecordSummaryResponse> items = this.records.search(query).stream()
                .map(CallRecordSummaryResponse::from)
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

    // ---------------------------------------------------------------- 内部

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
