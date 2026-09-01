package com.mcpgateway.api;

import com.mcpgateway.api.dto.CallRecordDetailResponse;
import com.mcpgateway.api.dto.CallRecordPageResponse;
import com.mcpgateway.service.CallRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调用记录查询 API（需求 FR-06.5）。
 *
 * MVP 阶段这块是空的，排障只能停掉应用连 H2 查库。补上之后要记住：
 * <b>取单条的接口会返回知识库正文</b>（requestJson / responseJson 按 FR-06.4 原样保存），
 * 而管理端没有登录 —— 这个接口的暴露面等同于数据库文件本身，见 SECURITY.md。
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
            @RequestParam(required = false) Integer size) {

        return ApiResponse.ok(this.callRecordService.search(
                gatewayId, downstreamMcpId, toolName, status, traceId, from, to, page, size));
    }

    /** 单条记录的完整内容，含入参和返回。归属校验在 service 里做。 */
    @GetMapping("/{callId}")
    public ApiResponse<CallRecordDetailResponse> detail(@PathVariable String gatewayId,
            @PathVariable String callId) {
        return ApiResponse.ok(this.callRecordService.detail(gatewayId, callId));
    }
}
