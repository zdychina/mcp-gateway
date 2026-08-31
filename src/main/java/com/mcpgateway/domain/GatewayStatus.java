package com.mcpgateway.domain;

/**
 * 网关派生状态（需求 FR-01）。不落库，每次按子 MCP 的同步状态实时计算。
 */
public enum GatewayStatus {

    /** 尚无成功同步的子 MCP。 */
    EMPTY,

    /** 所有子 MCP 最近一次同步成功。 */
    READY,

    /** 至少一个子 MCP 异常，但仍有可用工具。 */
    DEGRADED,

    /**
     * 配置了子 MCP 但全部异常，一个可用工具都没有。
     *
     * 需求 FR-01 只定义了 EMPTY / READY / DEGRADED 三态，其中 DEGRADED 的定义是
     * "仍有可用工具"，因此全挂的情况没有归属。补这一态以免状态计算出现空洞，
     * 是否并入 DEGRADED 待需求方确认。
     */
    UNAVAILABLE
}
