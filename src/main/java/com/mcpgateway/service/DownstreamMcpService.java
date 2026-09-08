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
     *
     * <p>这个方法只写配置，<b>不碰网络</b> —— 是否要重新拉一次工具列表由返回值告诉调用方，
     * 同步本身放在 {@link DownstreamImportOrchestrator} 里做。理由和导入那条路一样：
     * 把网络调用留在这个事务里，一个慢下游能把数据库连接占满 30 秒。
     *
     * @return 是否需要重新同步工具快照
     */
    @Transactional
    public boolean update(String gatewayId, String downstreamId, UpdateDownstreamRequest request) {
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

        String url = request.url().trim();
        Instant now = Instant.now();
        this.downstreams.updateConfig(downstreamId, name, url, encryptedHeaders, now);

        if (!existing.name().equals(name)) {
            renameExposedTools(downstreamId, name, now);
            log.warn("downstream {} renamed from {} to {}; every exposed tool name changed, "
                    + "which is a breaking change for connected agents", downstreamId, existing.name(), name);
        }

        /*
         * 换了地址或换了凭证，下游能给出的工具集就可能变了，快照必须重新拉一次 ——
         * 不拉的话页面上一切正常，工具却还是旧那套，直到 Agent 调用一个已经不存在的工具
         * 才暴露出来。只改名字不用拉：聚合工具名是本地按新名字重算的。
         */
        return !existing.url().equals(url) || request.headers() != null;
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
