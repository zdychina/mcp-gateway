<script setup lang="ts">
import { ref } from 'vue'
import { useClipboard } from '../composables/useClipboard'
import AppIcon from './AppIcon.vue'

/**
 * 图标式的"复制"按钮，用在表格单元格和详情面板里。
 *
 * 与 CopyField 的区别是这里没有可见的输入框：调用记录页要复制的东西（trace_id、
 * call_id、整段正文）都已经显示在页面上了，再套一个只读输入框只会占地方。
 *
 * 代价是剪贴板被拒时没有"内容已选中，按 Ctrl+C"的退路（内网 HTTP 下这不是小概率事件），
 * 所以失败要明说，让操作人知道得自己选中复制，而不是以为已经复制成功了。
 */
const props = withDefaults(defineProps<{
  value: string
  /** 无障碍名称，同时作为 tooltip */
  label?: string
  /** 给出文字时按钮显示成"图标 + 文字" */
  text?: string
}>(), { label: '复制', text: '' })

const { copied, copy } = useClipboard()
const failed = ref(false)

async function onCopy(): Promise<void> {
  failed.value = !(await copy(props.value))
}
</script>

<template>
  <button type="button" class="icon-btn" :class="{ ok: copied, failed }"
          :title="failed ? '浏览器不允许自动复制（多见于非 HTTPS），请手动选中' : label"
          :aria-label="label" @click.stop="onCopy">
    <AppIcon :name="copied ? 'check' : 'copy'" :size="13" />
    <span v-if="text">{{ copied ? '已复制' : text }}</span>
  </button>
</template>
