package com.mcpgateway.api.dto;

/**
 * 一次"测试并同步"的结果。
 *
 * 即使同步失败，HTTP 状态也是 200：请求本身处理成功了，失败的是与下游的交互，
 * 详情在 succeeded / errorCode 里。这样前端能一次拿到"哪个成功了、哪个失败了"，
 * 而不用从错误响应里猜。
 */
public record SyncResponse(
        String downstreamId,
        String downstreamName,
        boolean succeeded,
        int added,
        int updated,
        int unchanged,
        int removed,
        String errorCode,
        String errorMessage) {
}
