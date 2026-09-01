<script setup lang="ts">
import { computed } from 'vue'
import type { GatewayStatus } from '../api/types'

const props = defineProps<{ status: GatewayStatus }>()

/*
 * Thymeleaf 版本把这段写成三重嵌套三元表达式，而且列表页和详情页各抄了一遍。
 * 收敛到一个组件里，顺便把每个状态的含义写清楚 —— 光看 DEGRADED 这个词
 * 猜不出"仍有可用工具"。
 */
const STATUS_META: Record<GatewayStatus, { className: string, hint: string }> = {
  READY: { className: 'badge-ready', hint: '所有子 MCP 最近一次同步成功' },
  DEGRADED: { className: 'badge-degraded', hint: '至少一个子 MCP 异常，但仍有可用工具' },
  UNAVAILABLE: { className: 'badge-unavailable', hint: '配置了子 MCP 但全部异常，没有可用工具' },
  EMPTY: { className: 'badge-empty', hint: '尚无成功同步的子 MCP' }
}

const meta = computed(() => STATUS_META[props.status] ?? STATUS_META.EMPTY)
</script>

<template>
  <span class="badge" :class="meta.className" :title="meta.hint">{{ status }}</span>
</template>
