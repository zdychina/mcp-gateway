-- MCP 聚合网关初始表结构。对应需求文档 §9 数据模型草案。
-- 目标数据库：H2 2.x 文件库。

-- ---------------------------------------------------------------- §9.1 网关

CREATE TABLE mcp_gateway (
    id                VARCHAR(36)   NOT NULL,
    name              VARCHAR(64)   NOT NULL,
    slug              VARCHAR(64)   NOT NULL,
    description       VARCHAR(4000),
    access_token_hash VARCHAR(64)   NOT NULL,
    created_at        TIMESTAMP(6) WITH TIME ZONE  NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE  NOT NULL,
    CONSTRAINT pk_mcp_gateway PRIMARY KEY (id),
    -- 需求 6.1.2：slug 系统内唯一。
    CONSTRAINT uk_mcp_gateway_slug UNIQUE (slug)
);

-- ------------------------------------------------------------- §9.2 子 MCP

CREATE TABLE downstream_mcp (
    id                     VARCHAR(36)   NOT NULL,
    gateway_id             VARCHAR(36)   NOT NULL,
    name                   VARCHAR(64)   NOT NULL,
    type                   VARCHAR(32)   NOT NULL,
    url                    VARCHAR(2048) NOT NULL,
    -- 需求 12.2：headers 整体以 AES-GCM 加密后落库，明文不入库。
    encrypted_headers_json CLOB,
    sync_status            VARCHAR(16)   NOT NULL,
    last_sync_at           TIMESTAMP(6) WITH TIME ZONE,
    last_sync_error        VARCHAR(1000),
    created_at             TIMESTAMP(6) WITH TIME ZONE  NOT NULL,
    updated_at             TIMESTAMP(6) WITH TIME ZONE  NOT NULL,
    CONSTRAINT pk_downstream_mcp PRIMARY KEY (id),
    -- 需求 6.2.2：同一网关内子 MCP 名称唯一。
    CONSTRAINT uk_downstream_mcp_gateway_name UNIQUE (gateway_id, name),
    -- 需求 6.1.7：删除网关级联删除子 MCP。
    CONSTRAINT fk_downstream_mcp_gateway FOREIGN KEY (gateway_id)
        REFERENCES mcp_gateway (id) ON DELETE CASCADE
);

CREATE INDEX ix_downstream_mcp_gateway ON downstream_mcp (gateway_id);

-- ------------------------------------------------------------ §9.3 工具快照

CREATE TABLE gateway_tool (
    id                   VARCHAR(36)  NOT NULL,
    gateway_id           VARCHAR(36)  NOT NULL,
    downstream_mcp_id    VARCHAR(36)  NOT NULL,
    original_name        VARCHAR(128) NOT NULL,
    -- 需求 6.3.4：聚合工具名不超过 128 字符。
    exposed_name         VARCHAR(128) NOT NULL,
    original_description CLOB,
    custom_description   CLOB,
    input_schema_json    CLOB,
    output_schema_json   CLOB,
    annotations_json     CLOB,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    definition_hash      VARCHAR(64),
    last_synced_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_gateway_tool PRIMARY KEY (id),
    -- 需求 9.3：两组唯一约束。前者保证对 Agent 暴露的名字唯一，
    -- 后者保证一个子 MCP 的同一原工具只有一行快照。
    CONSTRAINT uk_gateway_tool_exposed UNIQUE (gateway_id, exposed_name),
    CONSTRAINT uk_gateway_tool_original UNIQUE (downstream_mcp_id, original_name),
    CONSTRAINT fk_gateway_tool_gateway FOREIGN KEY (gateway_id)
        REFERENCES mcp_gateway (id) ON DELETE CASCADE,
    -- 需求 6.2.10：删除子 MCP 后其工具立即从快照消失。
    CONSTRAINT fk_gateway_tool_downstream FOREIGN KEY (downstream_mcp_id)
        REFERENCES downstream_mcp (id) ON DELETE CASCADE
);

CREATE INDEX ix_gateway_tool_gateway ON gateway_tool (gateway_id);
CREATE INDEX ix_gateway_tool_downstream ON gateway_tool (downstream_mcp_id);

-- ------------------------------------------------------------ §9.4 调用打点

CREATE TABLE tool_call_record (
    call_id            VARCHAR(36)  NOT NULL,
    trace_id           VARCHAR(64)  NOT NULL,
    gateway_id         VARCHAR(36)  NOT NULL,
    -- 可空：未知工具或停用工具的调用无法确定目标子 MCP。
    downstream_mcp_id  VARCHAR(36),
    exposed_tool_name  VARCHAR(128) NOT NULL,
    original_tool_name VARCHAR(128),
    request_json       CLOB,
    response_json      CLOB,
    status             VARCHAR(16)  NOT NULL,
    error_code         VARCHAR(64),
    error_message      VARCHAR(1000),
    started_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    finished_at        TIMESTAMP(6) WITH TIME ZONE,
    duration_ms        BIGINT,
    CONSTRAINT pk_tool_call_record PRIMARY KEY (call_id),
    -- 需求 6.1.7：删除网关级联删除调用记录。
    CONSTRAINT fk_tool_call_record_gateway FOREIGN KEY (gateway_id)
        REFERENCES mcp_gateway (id) ON DELETE CASCADE
);

-- 需求 9.4：为 call_id、gateway_id、started_at 建立必要索引。
-- call_id 已是主键；trace_id 供 15.4.3 的关联查询使用。
CREATE INDEX ix_tool_call_record_gateway_started ON tool_call_record (gateway_id, started_at);
CREATE INDEX ix_tool_call_record_trace ON tool_call_record (trace_id);
CREATE INDEX ix_tool_call_record_status ON tool_call_record (status);
