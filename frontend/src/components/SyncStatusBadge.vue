<script setup lang="ts">
import { computed } from 'vue'
import type { SyncStatus } from '../api/types'

const props = defineProps<{ status: SyncStatus }>()

/*
 * 复用网关状态那几个徽章配色，不再单开一套 —— 语义是对应的：
 * 同步成功≈可用，同步失败≈不可用，尚未同步≈空。
 */
const STATUS_META: Record<SyncStatus, { className: string, hint: string }> = {
  SUCCESS: { className: 'badge-ready', hint: '最近一次同步成功，工具快照有效' },
  FAILED: { className: 'badge-unavailable', hint: '最近一次同步失败，仍在使用上一次成功的快照' },
  PENDING: { className: 'badge-empty', hint: '刚创建，尚未同步过' }
}

const meta = computed(() => STATUS_META[props.status] ?? STATUS_META.PENDING)
</script>

<template>
  <span class="badge" :class="meta.className" :title="meta.hint">{{ status }}</span>
</template>
