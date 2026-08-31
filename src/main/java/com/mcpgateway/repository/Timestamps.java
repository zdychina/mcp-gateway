package com.mcpgateway.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Instant 与 TIMESTAMP WITH TIME ZONE 之间的唯一转换点。
 *
 * 列类型选 WITH TIME ZONE、绑定值统一归一到 UTC，是为了让 H2 与将来的 PostgreSQL
 * 行为一致，也避免依赖 JVM 默认时区做隐式换算。
 */
final class Timestamps {

    private Timestamps() {
    }

    static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant fromDb(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
