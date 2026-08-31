package com.mcpgateway.domain;

/** 子 MCP 最近一次同步的结果（downstream_mcp.sync_status）。 */
public enum SyncStatus {

    /** 刚创建，尚未同步过。 */
    PENDING,

    /** 最近一次同步成功，工具快照有效。 */
    SUCCESS,

    /** 最近一次同步失败。需求 6.4.7：保留上一次成功快照，子 MCP 标记为异常。 */
    FAILED
}
