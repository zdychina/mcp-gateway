package com.mcpgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpgateway.api.dto.UpdateDownstreamRequest;
import com.mcpgateway.config.GatewayProperties;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.domain.SyncStatus;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.GatewayToolRepository;
import com.mcpgateway.security.DownstreamHeaderCodec;
import com.mcpgateway.security.SensitiveDataMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 子 MCP 的导入、编辑与删除（需求 FR-02）。工具同步在 W4 接入，这里只管配置。 */
@Service
public class DownstreamMcpService {

    private static final Logger log = LoggerFactory.getLogger(DownstreamMcpService.class);

    private final DownstreamMcpRepository downstreams;

    private final GatewayToolRepository tools;

    private final McpServersImportParser parser;

    private final DownstreamHeaderCodec headerCodec;

    private final GatewayService gatewayService;

    private final GatewayProperties properties;

    public DownstreamMcpService(DownstreamMcpRepository downstreams, GatewayToolRepository tools,
            McpServersImportParser parser, DownstreamHeaderCodec headerCodec, GatewayService gatewayService,
            GatewayProperties properties) {
        this.downstreams = downstreams;
        this.tools = tools;
        this.parser = parser;
        this.headerCodec = headerCodec;
        this.gatewayService = gatewayService;
        this.properties = properties;
    }

    /**
     * 需求 FR-02：粘贴 mcpServers JSON 一次导入 1–3 个子 MCP。
     *
     * 整批要么全部成功要么全部失败 —— 部分成功会让操作人拿到一个说不清楚状态的网关。
     */
    @Transactional
    public List<String> importServers(String gatewayId, JsonNode body) {
        this.gatewayService.requireGateway(gatewayId);

        int existing = this.downstreams.countByGatewayId(gatewayId);
        int remaining = this.properties.getServer().getMaxDownstreamPerGateway() - existing;
        if (remaining <= 0) {
            throw GatewayException.of(ErrorCode.MCP_SERVER_LIMIT_EXCEEDED,
                    "gateway already has the maximum of "
                            + this.properties.getServer().getMaxDownstreamPerGateway()
                            + " downstream MCP servers");
        }

        List<McpServersImportParser.ParsedDownstream> parsed = this.parser.parse(body, remaining);

        // 同一批里重名，以及与已有子 MCP 重名，都要在写库之前挡住。
        Set<String> batchNames = new HashSet<>();
        for (McpServersImportParser.ParsedDownstream server : parsed) {
            if (!batchNames.add(server.name())) {
                throw GatewayException.of(ErrorCode.DUPLICATE_DOWNSTREAM_NAME,
                        "duplicate server name in payload: " + server.name());
            }
            if (this.downstreams.existsByGatewayIdAndName(gatewayId, server.name())) {
                throw GatewayException.of(ErrorCode.DUPLICATE_DOWNSTREAM_NAME,
                        "server name already used in this gateway: " + server.name());
            }
        }

        Instant now = Instant.now();
        List<String> createdIds = new ArrayList<>();
        for (McpServersImportParser.ParsedDownstream server : parsed) {
            DownstreamMcp downstream = new DownstreamMcp(UUID.randomUUID().toString(), gatewayId,
                    server.name(), server.type(), server.url(),
                    this.headerCodec.encrypt(server.headers()),
                    SyncStatus.PENDING, null, null, now, now);
            this.downstreams.insert(downstream);
            createdIds.add(downstream.id());

            log.info("imported downstream [{}] into gateway {} with headers {}", server.name(), gatewayId,
                    SensitiveDataMasker.describeForLog(server.headers()));
        }
        return createdIds;
    }

    /**
     * 编辑单个子 MCP。
     *
     * 需求 6.3.6：改名会连带改掉所有聚合工具名，这是对 Agent 的破坏性变更。
     * 这里同步重算 exposed_name，保留启停状态和自定义描述。
     */
    @Transactional
    public void update(String gatewayId, String downstreamId, UpdateDownstreamRequest request) {
        this.gatewayService.requireGateway(gatewayId);
        DownstreamMcp existing = requireDownstream(gatewayId, downstreamId);

        String name = request.name().trim();
        validateName(name);
        DownstreamUrlValidator.validate(request.url(), name);

        if (!existing.name().equals(name) && this.downstreams.existsByGatewayIdAndName(gatewayId, name)) {
            throw GatewayException.of(ErrorCode.DUPLICATE_DOWNSTREAM_NAME,
                    "server name already used in this gateway: " + name);
        }

        // headers 为 null 表示保持原样。前端拿到的是遮罩值，原样提交回来会把真凭证毁掉，
        // 所以必须用"不传"表达"不改"。
        String encryptedHeaders = request.headers() == null
                ? existing.encryptedHeadersJson()
                : this.headerCodec.encrypt(request.headers());

        Instant now = Instant.now();
        this.downstreams.updateConfig(downstreamId, name, request.url().trim(), encryptedHeaders, now);

        if (!existing.name().equals(name)) {
            renameExposedTools(downstreamId, name, now);
            log.warn("downstream {} renamed from {} to {}; every exposed tool name changed, "
                    + "which is a breaking change for connected agents", downstreamId, existing.name(), name);
        }
    }

    /** 需求 6.2.10：删除子 MCP 后其工具立即从快照移除，由外键级联完成。 */
    @Transactional
    public void delete(String gatewayId, String downstreamId) {
        this.gatewayService.requireGateway(gatewayId);
        requireDownstream(gatewayId, downstreamId);
        this.downstreams.deleteById(downstreamId);
        log.info("deleted downstream {} from gateway {}; its tools are no longer exposed",
                downstreamId, gatewayId);
    }

    // ---------------------------------------------------------------- 内部

    private void renameExposedTools(String downstreamId, String newName, Instant now) {
        for (GatewayTool tool : this.tools.findByDownstreamMcpId(downstreamId)) {
            String exposedName = GatewayTool.buildExposedName(newName, tool.originalName());
            if (exposedName.length() > GatewayTool.MAX_EXPOSED_NAME_LENGTH) {
                // 需求 6.3.5：不静默截断，整个改名操作回滚。
                throw GatewayException.of(ErrorCode.INVALID_TOOL_NAME,
                        "renaming would make tool name exceed "
                                + GatewayTool.MAX_EXPOSED_NAME_LENGTH + " characters: " + exposedName);
            }
            this.tools.updateExposedName(tool.id(), exposedName, now);
        }
    }

    private void validateName(String name) {
        if (!name.matches(DownstreamMcp.NAME_PATTERN)) {
            throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG,
                    "server name must match " + DownstreamMcp.NAME_PATTERN);
        }
        if (DownstreamMcp.containsDoubleUnderscore(name)) {
            throw GatewayException.of(ErrorCode.INVALID_MCP_CONFIG, name + ": server name must not contain '__'");
        }
    }

    /** 同时校验归属，避免用别的网关的 id 越权改到这个子 MCP。 */
    public DownstreamMcp requireDownstream(String gatewayId, String downstreamId) {
        DownstreamMcp downstream = this.downstreams.findById(downstreamId)
                .orElseThrow(() -> GatewayException.of(ErrorCode.DOWNSTREAM_NOT_FOUND, "no such downstream MCP"));
        if (!downstream.gatewayId().equals(gatewayId)) {
            throw GatewayException.of(ErrorCode.DOWNSTREAM_NOT_FOUND, "no such downstream MCP");
        }
        return downstream;
    }
}
