<script setup lang="ts">
import { computed } from 'vue'
import type { ExtractedValue } from '../api/types'

/**
 * 一个抽取列的单元格（列表可配置列）。
 *
 * 抽不到值的三种原因要分开说：路径不对该去改路径，正文过大该去展开这一条，
 * 不是 JSON 则说明这条记录本身在打点时就出过问题。全画成"—"会让人以为是同一回事。
 */
const props = defineProps<{ value?: ExtractedValue }>()

const FALLBACKS = {
  MISSING: { text: '—', title: '这条记录的正文里没有这个路径' },
  NOT_JSON: { text: '非 JSON', title: '正文不是合法 JSON —— 可能是打点时序列化失败写下的占位符，或历史遗留的坏数据' },
  TOO_LARGE: { text: '正文过大', title: '正文超过解析上限，服务端没有读它。展开这一条可以看全文' }
} as const

const fallback = computed(() => {
  const state = props.value?.state
  return state && state !== 'OK' ? FALLBACKS[state] : FALLBACKS.MISSING
})
</script>

<template>
  <span v-if="value && value.state === 'OK'" class="extract-value mono" :title="value.value ?? ''">
    {{ value.value
    }}<span v-if="value.truncated" class="muted" title="值已截断到 200 字符，展开这一条看全文">…</span>
  </span>
  <span v-else class="muted extract-empty" :title="fallback.title">{{ fallback.text }}</span>
</template>
