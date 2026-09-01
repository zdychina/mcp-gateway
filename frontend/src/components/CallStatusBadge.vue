<script setup lang="ts">
import { computed } from 'vue'
import type { CallStatus } from '../api/types'

const props = defineProps<{ status: CallStatus }>()

/*
 * STARTED 单独一个配色：它既可能是"正在进行"，也可能是进程异常退出遗留的残留
 * （需求 13.2 会在下次启动时把它们改成 ERROR）。两种都值得注意，所以不按"正常"处理。
 */
const STATUS_META: Record<CallStatus, { className: string, hint: string }> = {
  SUCCESS: { className: 'badge-ready', hint: '下游返回了结果（工具自己标记的 isError 也算成功返回）' },
  ERROR: { className: 'badge-unavailable', hint: '网关没能拿到结果：未知工具、参数非法、网络失败或下游报错' },
  TIMEOUT: { className: 'badge-degraded', hint: '下游调用超时' },
  STARTED: { className: 'badge-started', hint: '进行中，或上次进程异常退出遗留的记录' }
}

const meta = computed(() => STATUS_META[props.status] ?? STATUS_META.STARTED)
</script>

<template>
  <span class="badge" :class="meta.className" :title="meta.hint">{{ status }}</span>
</template>
