package com.mcpgateway.repository;

import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.domain.SyncStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 数据层测试用的构造器。时间统一截到微秒，与 TIMESTAMP(6) 的精度对齐。 */
final class TestFixtures {

    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private TestFixtures() {
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    static Gateway gateway(String slug) {
        return new Gateway(id(), "Gateway " + slug, slug, "描述 " + slug,
                "hash-" + slug, NOW, NOW);
    }

    static DownstreamMcp downstream(String gatewayId, String name) {
        return new DownstreamMcp(id(), gatewayId, name, DownstreamMcp.TYPE_STREAMABLE_HTTP,
                "https://example.com/mcp/" + name, "encrypted-blob",
                SyncStatus.PENDING, null, null, NOW, NOW);
    }

    static GatewayTool tool(String gatewayId, String downstreamId, String downstreamName, String originalName) {
        return new GatewayTool(id(), gatewayId, downstreamId, originalName,
                GatewayTool.buildExposedName(downstreamName, originalName),
                "original description", null,
                "{\"type\":\"object\"}", null, null,
                true, "hash-v1", NOW, NOW, NOW);
    }
}
