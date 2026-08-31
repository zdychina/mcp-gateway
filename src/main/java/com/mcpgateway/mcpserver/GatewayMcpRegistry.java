package com.mcpgateway.mcpserver;

import com.mcpgateway.config.GatewayProperties;
import com.mcpgateway.domain.Gateway;
import com.mcpgateway.recording.ToolCallRecorder;
import com.mcpgateway.repository.GatewayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动网关的 MCP 服务上下文注册表。
 *
 * 按需构建、按 slug 缓存。需求 6.1.6 要求配置变更保存后立即生效，所以任何会改变
 * slug 或 instructions 的操作都必须调 {@link #evict}；工具启停和重新同步**不需要**，
 * 因为工具目录本来就是每次请求现读数据库的。
 */
@Component
public class GatewayMcpRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GatewayMcpRegistry.class);

    private final GatewayRepository gateways;

    private final GatewayProperties properties;

    private final GatewayToolRouter router;

    private final ToolCallRecorder recorder;

    private final Map<String, Entry> bySlug = new ConcurrentHashMap<>();

    public GatewayMcpRegistry(GatewayRepository gateways, GatewayProperties properties, GatewayToolRouter router,
            ToolCallRecorder recorder) {
        this.gateways = gateways;
        this.properties = properties;
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
            return new Entry(gateway.id(),
                    GatewayMcpRuntime.create(gateway, this.properties, this.router, this.recorder));
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
