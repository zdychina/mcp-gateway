package com.mcpgateway.downstream;

import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.SyncStatus;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.repository.DownstreamMcpRepository;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 连接测试与工具同步（需求 6.2.7 / 6.2.8 / 6.4）。
 *
 * 这个类本身**不是**事务性的，是有意的：一次同步要发起真实 HTTP 调用，最长可能占用 30 秒。
 * 如果整个流程包在一个事务里，一个慢下游会把数据库连接一直攥在手上。所以流程拆成三段：
 * 事务外读配置 -> 事务外走网络 -> 事务内落快照（{@link ToolSnapshotWriter}）。
 */
@Service
public class ToolSyncService {

    private static final Logger log = LoggerFactory.getLogger(ToolSyncService.class);

    private final DownstreamMcpRepository downstreams;

    private final DownstreamClientFactory clientFactory;

    private final ToolDefinitionMapper definitionMapper;

    private final ToolSnapshotWriter snapshotWriter;

    public ToolSyncService(DownstreamMcpRepository downstreams, DownstreamClientFactory clientFactory,
            ToolDefinitionMapper definitionMapper, ToolSnapshotWriter snapshotWriter) {
        this.downstreams = downstreams;
        this.clientFactory = clientFactory;
        this.definitionMapper = definitionMapper;
        this.snapshotWriter = snapshotWriter;
    }

    /**
     * 测试连接并同步工具。
     *
     * 失败时不抛异常，而是把结果放进 {@link SyncReport} 并把子 MCP 标记为 FAILED。
     * 需求 6.2.9 要求单个子 MCP 不可用不影响其他子 MCP，所以批量导入时逐个调用它必须能继续走下去。
     */
    public SyncReport sync(String downstreamId) {
        DownstreamMcp downstream = this.downstreams.findById(downstreamId)
                .orElseThrow(() -> GatewayException.of(ErrorCode.DOWNSTREAM_NOT_FOUND, "no such downstream MCP"));
        return sync(downstream);
    }

    public SyncReport sync(DownstreamMcp downstream) {
        if (!DownstreamMcp.TYPE_STREAMABLE_HTTP.equals(downstream.type())) {
            return markFailed(downstream, DownstreamClientFactory.unsupported(downstream.name()));
        }

        List<FetchedTool> fetched;
        try {
            // 网络往返在事务之外。
            fetched = fetchTools(downstream);
        }
        catch (GatewayException ex) {
            return markFailed(downstream, ex);
        }
        catch (RuntimeException ex) {
            return markFailed(downstream,
                    DownstreamErrorMapper.map(ex, ErrorCode.DOWNSTREAM_SYNC_FAILED, downstream.name()));
        }

        Instant now = Instant.now();
        try {
            ToolSnapshotWriter.SyncOutcome outcome = this.snapshotWriter.merge(downstream, fetched, now);
            this.downstreams.updateSyncResult(downstream.id(), SyncStatus.SUCCESS, now, null, now);
            return SyncReport.success(downstream.id(), downstream.name(), outcome);
        }
        catch (GatewayException ex) {
            // 需求 6.4.7：同步失败保留上一次成功快照。合并是单个事务，抛异常即整体回滚，
            // 上一次的快照原封不动。
            return markFailed(downstream, ex);
        }
    }

    private List<FetchedTool> fetchTools(DownstreamMcp downstream) {
        return this.clientFactory.withClient(downstream, client -> {
            McpSchema.ListToolsResult result;
            try {
                result = client.listTools();
            }
            catch (RuntimeException ex) {
                throw DownstreamErrorMapper.map(ex, ErrorCode.DOWNSTREAM_SYNC_FAILED, downstream.name());
            }
            return this.definitionMapper.toFetchedTools(result.tools(), downstream.name());
        });
    }

    /**
     * 需求 6.4.7：把子 MCP 标记为异常，但 last_sync_at 停在上一次成功的时刻 ——
     * 那个字段的含义是"快照有多新"，不是"上次尝试是什么时候"。
     */
    private SyncReport markFailed(DownstreamMcp downstream, GatewayException failure) {
        String errorSummary = failure.errorCode().name() + ": " + failure.getMessage();
        this.downstreams.updateSyncResult(downstream.id(), SyncStatus.FAILED,
                downstream.lastSyncAt(), truncate(errorSummary), Instant.now());
        log.warn("sync failed for downstream [{}]: {}", downstream.name(), errorSummary, failure);
        return SyncReport.failure(downstream.id(), downstream.name(), failure.errorCode(), errorSummary);
    }

    /** 与 downstream_mcp.last_sync_error 的列宽一致。 */
    private static String truncate(String message) {
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    /** 一次同步的结果。失败不抛异常，由调用方决定是整体报错还是继续处理下一个。 */
    public record SyncReport(
            String downstreamId,
            String downstreamName,
            boolean succeeded,
            ToolSnapshotWriter.SyncOutcome outcome,
            ErrorCode errorCode,
            String errorMessage) {

        static SyncReport success(String id, String name, ToolSnapshotWriter.SyncOutcome outcome) {
            return new SyncReport(id, name, true, outcome, null, null);
        }

        static SyncReport failure(String id, String name, ErrorCode errorCode, String errorMessage) {
            return new SyncReport(id, name, false, null, errorCode, errorMessage);
        }
    }
}
