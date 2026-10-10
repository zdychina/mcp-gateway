package com.mcpgateway.mcpserver;

import com.mcpgateway.config.GatewayProperties;
import com.mcpgateway.config.GatewayVersion;
import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.recording.ToolCallRecorder;
import com.mcpgateway.repository.DownstreamMcpRepository;
import com.mcpgateway.repository.GatewayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动网关的 MCP 服务上下文注册表。
 *
 * 按需构建、按 slug 缓存。需求 6.1.6 要求配置变更保存后立即生效，所以任何会改变
 * slug 或 instructions 的操作都必须调 {@link #evict}；工具启停和重新同步**不需要**，
 * 因为工具目录本来就是每次请求现读数据库的。instructions 由网关描述和有描述的
 * 子 MCP 组合而成（{@link AgentInstructions}），所以子 MCP 的名称、描述或同步捕获
 * 的原始描述变化同样要走 {@link #evict}，失效入口在 DownstreamMcpService 和 ToolSyncService。
 */
@Component
public class GatewayMcpRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GatewayMcpRegistry.class);

    private final GatewayRepository gateways;

    private final DownstreamMcpRepository downstreams;

    private final GatewayProperties properties;

    private final GatewayVersion version;

    private final GatewayToolRouter router;

    private final ToolCallRecorder recorder;

    private final Map<String, Entry> bySlug = new ConcurrentHashMap<>();

    public GatewayMcpRegistry(GatewayRepository gateways, DownstreamMcpRepository downstreams,
            GatewayProperties properties, GatewayVersion version,
            GatewayToolRouter router, ToolCallRecorder recorder) {
        // 与 AdminAccount / AesGcmCipher 同款的启动期 fail-fast：坏模板让应用起不来，
        // 而不是把字面 {{...}} 静默发给 Agent、等有人从行为倒查回来。
        AgentInstructions.validate(properties.getServer().getAgentInstructionsTemplate());
        this.gateways = gateways;
        this.downstreams = downstreams;
        this.properties = properties;
        this.version = version;
        this.router = router;
        this.recorder = recorder;
    }

    /**
     * 按 slug 取运行时。网关不存在时返回空。
     */
    public Optional<Resolved> resolve(String slug) {
        Optional<Gateway> found = this.gateways.findBySlug(slug);
        if (found.isEmpty()) {
            // 网关刚被删掉的话，顺手把缓存清掉。
            evictBySlug(slug);
            return Optional.empty();
        }
        Gateway gateway = found.get();
        Entry entry = this.bySlug.compute(slug, (key, existing) -> {
            if (existing != null && existing.gatewayId.equals(gateway.id())) {
                return existing;
            }
            if (existing != null) {
                existing.runtime.close();
            }
            // instructions 在构建时组合一次，随上下文缓存；子 MCP 列表只在这里查，
            // resolve 每个请求都会走，不能把这条查询放到 compute 之外。
            List<DownstreamMcp> owned = this.downstreams.findByGatewayId(gateway.id());
            return new Entry(gateway.id(), GatewayMcpRuntime.create(gateway,
                    AgentInstructions.compose(
                            this.properties.getServer().getAgentInstructionsTemplate(), gateway, owned),
                    this.properties, this.version, this.router, this.recorder));
        });
        return Optional.of(new Resolved(gateway, entry.runtime));
    }

    /** 网关的 slug 或描述变化、以及网关被删除时调用。 */
    public void evict(String gatewayId) {
        this.bySlug.entrySet().removeIf(entry -> {
            if (entry.getValue().gatewayId.equals(gatewayId)) {
                entry.getValue().runtime.close();
                log.info("evicted MCP runtime for gateway {}", gatewayId);
                return true;
            }
            return false;
        });
    }

    private void evictBySlug(String slug) {
        Entry removed = this.bySlug.remove(slug);
        if (removed != null) {
            removed.runtime.close();
        }
    }

    @Override
    public void close() {
        this.bySlug.values().forEach(entry -> entry.runtime.close());
        this.bySlug.clear();
    }

    /** 解析结果：网关本身（用于令牌校验）和它的 MCP 运行时。 */
    public record Resolved(Gateway gateway, GatewayMcpRuntime runtime) {
    }

    private record Entry(String gatewayId, GatewayMcpRuntime runtime) {
    }
}
