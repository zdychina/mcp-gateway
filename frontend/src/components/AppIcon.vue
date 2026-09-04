<script setup lang="ts">
import { computed } from 'vue'

/**
 * 内联 SVG 图标。
 *
 * 需求 14：不引用任何外网资源。图标字体和 CDN 图标库都会让内网部署下的界面缺图，
 * 所以直接把这几条路径写进包里 —— 用到的图标很少，不值得引一个图标库。
 */
const props = withDefaults(defineProps<{ name: string, size?: number }>(), { size: 16 })

const PATHS: Record<string, string> = {
  hub: 'M12 3v6m0 6v6M5.6 7.5l5.2 3m2.4 3l5.2 3M18.4 7.5l-5.2 3m-2.4 3l-5.2 3',
  sun: 'M12 4V2m0 20v-2m8-8h2M2 12h2m13.7-5.7l1.4-1.4M4.9 19.1l1.4-1.4m0-11.4L4.9 4.9m14.2 14.2l-1.4-1.4M16 12a4 4 0 11-8 0 4 4 0 018 0z',
  moon: 'M20 14.5A8.5 8.5 0 019.5 4a8.5 8.5 0 100 17 8.5 8.5 0 0010.5-6.5z',
  chevron: 'M9 5l7 7-7 7',
  inbox: 'M4 13h4l2 3h4l2-3h4M4 13V6a2 2 0 012-2h12a2 2 0 012 2v7m-16 0v5a2 2 0 002 2h12a2 2 0 002-2v-5',
  warning: 'M12 9v4m0 4h.01M10.3 3.9L2.4 17.5A2 2 0 004.1 20.5h15.8a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z',
  search: 'M11 19a8 8 0 100-16 8 8 0 000 16zm10 2l-4.35-4.35',
  lock: 'M7 11V8a5 5 0 0110 0v3M5 11h14a1 1 0 011 1v8a1 1 0 01-1 1H5a1 1 0 01-1-1v-8a1 1 0 011-1z',
  exit: 'M15 17l5-5-5-5M20 12H9M12 20H6a2 2 0 01-2-2V6a2 2 0 012-2h6'
}

const d = computed(() => PATHS[props.name] ?? '')
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none"
       stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
       aria-hidden="true" focusable="false">
    <path :d="d" />
  </svg>
</template>
