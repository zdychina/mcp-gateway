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

onMounted(() => {
  if (!host.value) {
    return
  }
  chart = echarts.init(host.value, undefined, { renderer: 'svg' })
  chart.setOption(props.option, true)
  chart.on('click', (params) => emit('select', (params as { dataIndex: number }).dataIndex))

  // 容器宽度会随侧边栏、窗口和列数变化，ECharts 自己不会感知
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(host.value)
  }
})

watch(() => props.option, (option) => {
  chart?.setOption(option, true)
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
