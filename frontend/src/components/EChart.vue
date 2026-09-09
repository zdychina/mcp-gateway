<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'

/**
 * ECharts 的薄封装。
 *
 * 三个刻意的选择：
 *
 * - **按需引入**，不 import 整个 echarts：这一页只用到柱状图和三个组件，
 *   全量引入会让打包产物翻好几倍，而这套界面是要塞进 jar 里随内网部署走的。
 * - **SVG 渲染器**而不是 Canvas：图表数据量很小（最多几十根柱子），SVG 在高分屏上更锐利，
 *   文字可被选中和搜索，也省掉了 jsdom 里没有 canvas 这件麻烦事。
 * - **notMerge 更新**：option 是整份重算的（主题切换、数据刷新都会重算），
 *   合并更新会把上一次残留的系列留在图上。
 */
const props = withDefaults(defineProps<{
  option: EChartsOption
  /** 图表高度，CSS 长度 */
  height?: string
  /** 读屏软件看到的说明。图表本身对它们是不可读的，所以这句话必须自己成立 */
  ariaLabel: string
}>(), { height: '18rem' })

echarts.use([BarChart, GridComponent, TooltipComponent, LegendComponent, SVGRenderer])

/** 点中某根柱子。索引是 option 里 series.data 的下标，含义由调用方解释。 */
const emit = defineEmits<{ (event: 'select', index: number): void }>()

const host = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

/**
 * 尊重"减少动态效果"。
 *
 * ECharts 默认 animation: true，柱子每次都从零长上来 —— 而这几张图不只在首次挂载时画：
 * 换时间窗、换网关、切主题、自动刷新都会重算 option 再 setOption 一次，动画跟着重播。
 * CSS 那边（骨架屏、新记录高亮）已经守了这条偏好，图表是唯一漏掉的一处。
 *
 * 每次 setOption 前重新查一次，而不是缓存：系统偏好可以在页面开着的时候被改。
 */
function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** notMerge 更新会整份换掉 option，所以 animation 也要每次带上。 */
function withMotionPreference(option: EChartsOption): EChartsOption {
  return prefersReducedMotion() ? { ...option, animation: false } : option
}

onMounted(() => {
  if (!host.value) {
    return
  }
  chart = echarts.init(host.value, undefined, { renderer: 'svg' })
  chart.setOption(withMotionPreference(props.option), true)
  chart.on('click', (params) => emit('select', (params as { dataIndex: number }).dataIndex))

  // 容器宽度会随侧边栏、窗口和列数变化，ECharts 自己不会感知
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(host.value)
  }
})

watch(() => props.option, (option) => {
  chart?.setOption(withMotionPreference(option), true)
}, { deep: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  // 不 dispose 会把 DOM 和事件留在内存里，切走再切回来就多一份
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="host" class="chart" :style="{ height }" role="img" :aria-label="ariaLabel"></div>
</template>
