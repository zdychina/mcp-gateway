<script setup lang="ts">
import { ref } from 'vue'
import { useClipboard } from '../composables/useClipboard'

/** 只读 + 一键复制的字段。MCP 地址、接入 JSON、访问令牌都用它。 */
const props = withDefaults(defineProps<{
  value: string
  label?: string
  multiline?: boolean
  rows?: number
  /** 接入 JSON 这类要整段读的内容不该自动换行，那会打乱缩进 */
  preserveWhitespace?: boolean
}>(), { multiline: false, rows: 12, preserveWhitespace: false })

const field = ref<HTMLInputElement | HTMLTextAreaElement | null>(null)
const { copied, copy } = useClipboard()
const failed = ref(false)

async function onCopy(): Promise<void> {
  failed.value = !(await copy(props.value, field.value))
}
</script>

<template>
  <div class="field">
    <label v-if="label">{{ label }}</label>
    <div :class="multiline ? 'stack-sm' : 'input-group'">
      <textarea v-if="multiline" ref="field" class="control mono" :class="{ nowrap: preserveWhitespace }"
                :rows="rows" :value="value" readonly :aria-label="label"></textarea>
      <input v-else ref="field" class="control mono" :value="value" readonly :aria-label="label">
      <button class="btn" :class="{ 'btn-sm': multiline }" type="button" @click="onCopy">
        {{ copied ? '已复制' : '复制' }}
      </button>
    </div>
    <span v-if="failed" class="hint">浏览器不允许自动复制（多见于非 HTTPS）。内容已选中，请按 Ctrl+C。</span>
  </div>
</template>
