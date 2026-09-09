<script setup lang="ts">
import { ref } from 'vue'
import { useClipboard } from '../composables/useClipboard'
import { useAlerts } from '../composables/useAlerts'
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
const alerts = useAlerts()
const failed = ref(false)

/*
 * 失败要说出来，而不是把按钮变个色就算交代了。
 *
 * 变色是**只有颜色**在承载状态：图标不变、文字不变，色觉障碍或没盯着这颗按钮的人
 * 完全看不出刚才那下没成。补救办法（自己选中按 Ctrl+C）原先只写在 title 里，
 * 而 title 要悬停才出得来 —— 触屏和键盘根本够不着。
 *
 * 所以失败时做三件事：图标换成警告号（形状上就不同）、走全局提示栈说清楚怎么办
 * （AlertStack 带 aria-live，读屏软件也会播报）、title 保留给悬停的人。
 */
async function onCopy(): Promise<void> {
  const ok = await copy(props.value)
  failed.value = !ok
  if (!ok) {
    alerts.warning('没能自动复制',
      '浏览器不允许这个页面写剪贴板（多见于非 HTTPS 部署）。请选中内容后按 Ctrl+C。')
  }
}
</script>

<template>
  <button type="button" class="icon-btn" :class="{ ok: copied, failed }"
          :title="failed ? '浏览器不允许自动复制（多见于非 HTTPS），请手动选中' : label"
          :aria-label="label" @click.stop="onCopy">
    <!-- 三种状态三个形状，不靠颜色区分 -->
    <AppIcon :name="failed ? 'warning' : (copied ? 'check' : 'copy')" :size="13" />
    <span v-if="text">{{ failed ? '复制失败' : (copied ? '已复制' : text) }}</span>
  </button>
</template>
