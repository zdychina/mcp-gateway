package com.mcpgateway.domain;

/** 调用记录状态（需求 FR-06 的 status 字段）。 */
public enum CallStatus {

    /** 需求 FR-06.1：调用开始时先写入。 */
    STARTED,

    SUCCESS,

    ERROR,

    TIMEOUT
}
