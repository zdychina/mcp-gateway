package com.mcpgateway.api.dto;

import com.mcpgateway.domain.CallStatus;

import java.util.List;
import java.util.Map;

/**
 * 调用记录的一页结果。
 *
 * @param items        本页记录，最近的在前
 * @param page         页码，从 0 开始
 * @param size         每页条数
 * @param total        满足筛选条件的总条数
 * @param statusCounts 各终态的条数分布。**不套用 status 筛选条件** ——
 *                     它是给界面做分面筛选用的，已经筛了 ERROR 还只显示 ERROR 的数量没有意义
 */
public record CallRecordPageResponse(
        List<CallRecordSummaryResponse> items,
        int page,
        int size,
        int total,
        Map<CallStatus, Integer> statusCounts) {
}
