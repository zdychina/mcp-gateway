package com.mcpgateway.downstream;

import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.GatewayTool;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.repository.GatewayToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 工具快照合并（需求 6.4）。这是整个同步流程里最容易写错的一块，规则逐条对应需求：
 *
 * <ul>
 *   <li>6.4.3 新发现的工具默认启用</li>
 *   <li>6.4.4 同名工具再次同步时保留启用状态和自定义描述</li>
 *   <li>6.4.5 原始描述、Schema、annotations 以最新同步结果覆盖</li>
 *   <li>6.4.6 下游已删除的工具从快照移除</li>
 *   <li>6.3.5 聚合名不合法或超长时整次同步失败，不静默截断或重命名</li>
 * </ul>
 *
 * 整个方法是一个事务：要么快照完整更新，要么一行都不动。半更新的快照会让 tools/list
 * 和实际路由对不上。
 */
@Component
public class ToolSnapshotWriter {

    private static final Logger log = LoggerFactory.getLogger(ToolSnapshotWriter.class);

    private static final Pattern EXPOSED_NAME_PATTERN = Pattern.compile(GatewayTool.EXPOSED_NAME_PATTERN);

    private final GatewayToolRepository tools;

    public ToolSnapshotWriter(GatewayToolRepository tools) {
        this.tools = tools;
    }

    @Transactional
    public SyncOutcome merge(DownstreamMcp downstream, List<FetchedTool> fetched, Instant now) {
        // 先把全部聚合名算出来并校验，任何一条不合法就整批失败，一行都不写。
        Map<String, String> exposedNames = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (FetchedTool tool : fetched) {
            String exposedName = GatewayTool.buildExposedName(downstream.name(), tool.originalName());
            validateExposedName(downstream.name(), tool.originalName(), exposedName);
            if (!seen.add(tool.originalName())) {
                throw GatewayException.of(ErrorCode.DOWNSTREAM_SYNC_FAILED,
                        downstream.name() + ": downstream returned duplicate tool name " + tool.originalName());
            }
            exposedNames.put(tool.originalName(), exposedName);
        }

        Map<String, GatewayTool> existing = new LinkedHashMap<>();
        for (GatewayTool tool : this.tools.findByDownstreamMcpId(downstream.id())) {
            existing.put(tool.originalName(), tool);
        }

        int added = 0;
        int updated = 0;
        int unchanged = 0;

        for (FetchedTool tool : fetched) {
            GatewayTool current = existing.remove(tool.originalName());
            String exposedName = exposedNames.get(tool.originalName());

            if (current == null) {
                // 需求 6.4.3：新工具默认启用。
                this.tools.insert(new GatewayTool(UUID.randomUUID().toString(), downstream.gatewayId(),
                        downstream.id(), tool.originalName(), exposedName,
                        tool.description(), null,
                        tool.inputSchemaJson(), tool.outputSchemaJson(), tool.annotationsJson(),
                        true, tool.definitionHash(), now, now, now));
                added++;
                continue;
            }

            // 聚合名可能因为子 MCP 改过名而与当前不一致，顺手纠正。
            if (!exposedName.equals(current.exposedName())) {
                this.tools.updateExposedName(current.id(), exposedName, now);
            }

            if (tool.definitionHash() != null && tool.definitionHash().equals(current.definitionHash())) {
                // 定义没变，不重写协议字段。但 last_synced_at 仍要推进，
                // 否则前端会把一个刚同步过的工具显示成很久没同步。
                this.tools.touchLastSyncedAt(current.id(), now);
                unchanged++;
                continue;
            }

            // 需求 6.4.5：协议字段整体覆盖。注意这个方法的 SQL 里根本没有 enabled 和
            // custom_description 两列，所以需求 6.4.4 的"保留操作人配置"由结构本身保证。
            this.tools.updateProtocolFields(current.id(), tool.description(),
                    tool.inputSchemaJson(), tool.outputSchemaJson(), tool.annotationsJson(),
                    tool.definitionHash(), now, now);
            updated++;
        }

        // 需求 6.4.6：existing 里剩下的就是下游已经删掉的工具。
        List<String> removedIds = new ArrayList<>(existing.values().stream().map(GatewayTool::id).toList());
        this.tools.deleteByIds(removedIds);

        SyncOutcome outcome = new SyncOutcome(added, updated, unchanged, removedIds.size());
        log.info("synced downstream [{}]: {}", downstream.name(), outcome);
        return outcome;
    }

    /** 需求 6.3.4 / 6.3.5。 */
    private void validateExposedName(String downstreamName, String originalName, String exposedName) {
        if (exposedName.length() > GatewayTool.MAX_EXPOSED_NAME_LENGTH) {
            throw GatewayException.of(ErrorCode.INVALID_TOOL_NAME,
                    downstreamName + ": aggregated name for tool '" + originalName + "' exceeds "
                            + GatewayTool.MAX_EXPOSED_NAME_LENGTH + " characters");
        }
        if (!EXPOSED_NAME_PATTERN.matcher(exposedName).matches()) {
            throw GatewayException.of(ErrorCode.INVALID_TOOL_NAME,
                    downstreamName + ": aggregated name for tool '" + originalName
                            + "' is not a valid MCP tool name");
        }
    }

    /** 一次同步的变更统计，用于日志和 API 返回。 */
    public record SyncOutcome(int added, int updated, int unchanged, int removed) {

        @Override
        public String toString() {
            return "added=" + this.added + " updated=" + this.updated
                    + " unchanged=" + this.unchanged + " removed=" + this.removed;
        }
    }
}
