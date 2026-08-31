package com.mcpgateway.service;

import com.mcpgateway.api.dto.AgentConfigResponse;
import com.mcpgateway.api.dto.CreateGatewayRequest;
import com.mcpgateway.api.dto.CreatedGatewayResponse;
import com.mcpgateway.api.dto.DownstreamMcpResponse;
import com.mcpgateway.api.dto.GatewayDetailResponse;
import com.mcpgateway.api.dto.GatewaySummaryResponse;
import com.mcpgateway.api.dto.GatewayToolResponse;
import com.mcpgateway.api.dto.RotatedTokenResponse;
import com.mcpgateway.api.dto.UpdateGatewayRequest;
import com.mcpgateway.config.GatewayProperties;
import com.mcpgateway.mcpserver.GatewayMcpRegistry;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.domain.GatewayStatus;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.GatewayRepository;
import com.mcpgateway.repository.GatewayToolRepository;
import com.mcpgateway.security.AccessTokenService;
import com.mcpgateway.security.DownstreamHeaderCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 网关的创建、查看、编辑、删除与令牌轮换（需求 FR-01 / FR-05）。 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    /** 需求 FR-05.3：令牌只显示一次，之后接入 JSON 里给这个占位符。 */
    private static final String TOKEN_PLACEHOLDER = "<gateway-access-token>";

    private final GatewayRepository gateways;

    private final DownstreamMcpRepository downstreams;

    private final GatewayToolRepository tools;

    private final AccessTokenService accessTokens;

    private final DownstreamHeaderCodec headerCodec;

    private final GatewayProperties properties;

    /**
     * 需求 6.1.6：配置变更保存后立即生效。
     *
     * 只有会改变 slug 或 instructions 的操作需要失效对外的 MCP 上下文；
     * 工具启停和重新同步不需要，因为工具目录是每次请求现读数据库快照的。
     */
    private final GatewayMcpRegistry mcpRegistry;

    public GatewayService(GatewayRepository gateways, DownstreamMcpRepository downstreams,
            GatewayToolRepository tools, AccessTokenService accessTokens, DownstreamHeaderCodec headerCodec,
            GatewayProperties properties, GatewayMcpRegistry mcpRegistry) {
        this.gateways = gateways;
        this.downstreams = downstreams;
        this.tools = tools;
        this.accessTokens = accessTokens;
        this.headerCodec = headerCodec;
        this.properties = properties;
        this.mcpRegistry = mcpRegistry;
    }

    // ---------------------------------------------------------------- 查询

    @Transactional(readOnly = true)
    public List<GatewaySummaryResponse> list() {
        // 三次查询算完整个列表页，避免按网关逐个查。
        Map<String, List<DownstreamMcp>> byGateway = this.downstreams.findAll().stream()
                .collect(Collectors.groupingBy(DownstreamMcp::gatewayId));
        Map<String, Integer> toolCounts = this.tools.countByGatewayGrouped();

        return this.gateways.findAll().stream()
                .map(gateway -> {
                    List<DownstreamMcp> owned = byGateway.getOrDefault(gateway.id(), List.of());
                    return new GatewaySummaryResponse(
                            gateway.id(), gateway.name(), gateway.slug(), gateway.description(),
                            GatewayStatusCalculator.calculate(owned),
                            owned.size(),
                            toolCounts.getOrDefault(gateway.id(), 0),
                            mcpUrl(gateway.slug()),
                            gateway.createdAt(), gateway.updatedAt());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public GatewayDetailResponse detail(String gatewayId) {
        return toDetail(requireGateway(gatewayId));
    }

    // ---------------------------------------------------------------- 变更

    @Transactional
    public CreatedGatewayResponse create(CreateGatewayRequest request) {
        String slug = request.slug().trim();
        if (this.gateways.existsBySlug(slug)) {
            throw GatewayException.of(ErrorCode.DUPLICATE_GATEWAY_SLUG, "slug already in use: " + slug);
        }

        AccessTokenService.GeneratedToken token = this.accessTokens.generate();
        Instant now = Instant.now();
        Gateway gateway = new Gateway(UUID.randomUUID().toString(), request.name().trim(), slug,
                normalizeDescription(request.description()), token.hash(), now, now);

        this.gateways.insert(gateway);
        log.info("created gateway {} (slug={})", gateway.id(), gateway.slug());

        // 明文令牌只在这一次返回里出现，服务端不保留。
        return new CreatedGatewayResponse(toDetail(gateway), token.token());
    }

    @Transactional
    public GatewayDetailResponse update(String gatewayId, UpdateGatewayRequest request) {
        Gateway existing = requireGateway(gatewayId);
        String slug = request.slug().trim();
        if (this.gateways.existsBySlugAndIdNot(slug, gatewayId)) {
            throw GatewayException.of(ErrorCode.DUPLICATE_GATEWAY_SLUG, "slug already in use: " + slug);
        }

        this.gateways.update(gatewayId, request.name().trim(), slug,
                normalizeDescription(request.description()), Instant.now());

        if (!existing.slug().equals(slug)) {
            // slug 变了意味着 Agent 侧的 MCP URL 变了，是对 Agent 的破坏性变更。
            log.warn("gateway {} slug changed from {} to {}; agent MCP URL changed",
                    gatewayId, existing.slug(), slug);
        }
        // 名称、描述、slug 任一变化都会影响对外的 MCP 端点或 instructions。
        this.mcpRegistry.evict(gatewayId);
        return toDetail(requireGateway(gatewayId));
    }

    /** 需求 6.1.7：级联删除子 MCP、工具快照和调用记录，由外键完成。二次确认是前端的职责。 */
    @Transactional
    public void delete(String gatewayId) {
        requireGateway(gatewayId);
        this.gateways.deleteById(gatewayId);
        this.mcpRegistry.evict(gatewayId);
        log.info("deleted gateway {} and all of its downstreams, tools and call records", gatewayId);
    }

    /** 需求 FR-05.3：轮换令牌。旧令牌立即失效，MVP 不提供过渡期。 */
    @Transactional
    public RotatedTokenResponse rotateAccessToken(String gatewayId) {
        Gateway gateway = requireGateway(gatewayId);
        AccessTokenService.GeneratedToken token = this.accessTokens.generate();

        this.gateways.updateAccessTokenHash(gatewayId, token.hash(), Instant.now());
        // 令牌校验每次请求现查数据库，这里不必失效运行时，但旧令牌必须立刻失效。
        log.info("rotated access token for gateway {}; previously issued token is now invalid", gatewayId);

        return new RotatedTokenResponse(token.token(), mcpUrl(gateway.slug()));
    }

    // ------------------------------------------------------------ 接入 JSON

    /**
     * 需求 FR-05：Agent 接入 JSON。
     *
     * 令牌位置放占位符 —— 服务端只有哈希，拿不回明文。想要真令牌只能轮换一次。
     */
    @Transactional(readOnly = true)
    public AgentConfigResponse agentConfig(String gatewayId) {
        Gateway gateway = requireGateway(gatewayId);
        var entry = new AgentConfigResponse.ServerEntry(
                DownstreamMcp.TYPE_STREAMABLE_HTTP,
                mcpUrl(gateway.slug()),
                Map.of("Authorization", "Bearer " + TOKEN_PLACEHOLDER));
        return new AgentConfigResponse(Map.of(gateway.slug(), entry));
    }

    // ---------------------------------------------------------------- 内部

    public Gateway requireGateway(String gatewayId) {
        return this.gateways.findById(gatewayId)
                .orElseThrow(() -> GatewayException.of(ErrorCode.GATEWAY_NOT_FOUND, "no such gateway"));
    }

    /** 需求 FR-05.1：baseUrl 只能来自部署配置，不从 Host 等请求头拼接。 */
    private String mcpUrl(String slug) {
        String baseUrl = this.properties.getBaseUrl();
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed + "/mcp/" + slug;
    }

    private GatewayDetailResponse toDetail(Gateway gateway) {
        List<DownstreamMcp> owned = this.downstreams.findByGatewayId(gateway.id());

        // 需求 FR-03：详情页按子 MCP 分组展示全部已同步工具。
        Map<String, List<GatewayToolResponse>> toolsByDownstream = this.tools.findByGatewayId(gateway.id())
                .stream()
                .collect(Collectors.groupingBy(GatewayTool::downstreamMcpId, LinkedHashMap::new,
                        Collectors.mapping(GatewayToolResponse::from, Collectors.toList())));

        List<DownstreamMcpResponse> downstreamViews = owned.stream()
                .sorted(Comparator.comparing(DownstreamMcp::name))
                .map(downstream -> {
                    List<GatewayToolResponse> downstreamTools =
                            toolsByDownstream.getOrDefault(downstream.id(), List.of());
                    return new DownstreamMcpResponse(
                            downstream.id(), downstream.name(), downstream.type(), downstream.url(),
                            this.headerCodec.maskedView(downstream.encryptedHeadersJson()),
                            downstream.syncStatus(), downstream.lastSyncAt(), downstream.lastSyncError(),
                            downstreamTools.size(), downstreamTools);
                })
                .toList();

        GatewayStatus status = GatewayStatusCalculator.calculate(owned);
        return new GatewayDetailResponse(gateway.id(), gateway.name(), gateway.slug(), gateway.description(),
                status, mcpUrl(gateway.slug()), downstreamViews, gateway.createdAt(), gateway.updatedAt());
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
