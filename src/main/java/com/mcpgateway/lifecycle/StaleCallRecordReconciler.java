package com.mcpgateway.lifecycle;

import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.repository.ToolCallRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 需求 13.2：服务异常退出遗留的 STARTED 记录，可在下次启动时标记为 ERROR。
 *
 * 保证"调用失败必须产生最终调用状态"这条不因进程被 kill 而出现空洞。
 * 单实例部署的前提下，启动时任何 STARTED 都必然是上一次进程留下的残留。
 */
@Component
public class StaleCallRecordReconciler {

    private static final Logger log = LoggerFactory.getLogger(StaleCallRecordReconciler.class);

    private static final String RECONCILE_MESSAGE = "call did not finish before gateway shutdown";

    private final ToolCallRecordRepository repository;

    public StaleCallRecordReconciler(ToolCallRecordRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        int fixed = this.repository.markStaleStartedAsError(
                ErrorCode.INTERNAL_ERROR.name(), RECONCILE_MESSAGE, Instant.now());
        if (fixed > 0) {
            log.warn("marked {} stale STARTED call record(s) as ERROR on startup", fixed);
        }
    }
}
