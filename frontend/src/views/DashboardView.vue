<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { EChartsOption } from 'echarts'
import { statsApi } from '../api/stats'
import { ApiError } from '../api/client'
import type { NamedStat, Stats } from '../api/types'
import { useAlerts } from '../composables/useAlerts'
import { useTheme } from '../composables/useTheme'
import { axisStyle, chartTokens, tooltipStyle } from '../utils/chartTheme'
import { countLabel, durationLabel, percentLabel } from '../utils/format'
import { formatClock } from '../utils/datetime'
import AppIcon from '../components/AppIcon.vue'
import EChart from '../components/EChart.vue'
import StatusBadge from '../components/StatusBadge.vue'

/*
 * 总览页。
 *
 * 这一页回答"最近怎么样"：调用量的走势、哪个网关在跑、哪个子 MCP 和工具最忙、
 * 失败集中在哪几个错误码上。它不回答"某一次调用怎么了"—— 那是调用记录页的事，
 * 所以这里一条正文、一个参数都不显示，点进去才是明细。
 *
 * 图表全部来自同一个接口的同一份数据：一次请求、一个加载状态、一份时间窗，
 * 不会出现"总量对得上、分组对不上"的中间态。
 */
const alerts = useAlerts()
const { resolvedIsDark } = useTheme()

const RANGES = [
  { key: '1h', label: '1 小时', ms: 60 * 60_000 },
  { key: '24h', label: '24 小时', ms: 24 * 60 * 60_000 },
  { key: '7d', label: '7 天', ms: 7 * 24 * 60 * 60_000 },
  { key: '30d', label: '30 天', ms: 30 * 24 * 60 * 60_000 }
]

const range = ref('24h')
const gatewayId = ref('')
const data = ref<Stats | null>(null)
const loading = ref(true)
const loadFailed = ref(false)
const loadedAt = ref<number | null>(null)
/** 趋势图的表格视图：图表对读屏软件是不可读的，数字得有另一条路拿到。 */
const trendAsTable = ref(false)

/*
 * 图表颜色跟着主题走。
 *
 * theme 是个 ref，所以手动切换会自然重算；但选了"跟随系统"时，系统偏好翻转不经过任何
 * 响应式数据 —— 页面的 CSS 靠媒体查询自己变了，图表却还留在上一套颜色里。
 * 所以额外监听一次 matchMedia，把它变成一个能触发重算的信号。
 */
const systemThemeTick = ref(0)
let media: MediaQueryList | null = null

function onSystemThemeChange(): void {
  systemThemeTick.value += 1
}

onMounted(() => {
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    media = window.matchMedia('(prefers-color-scheme: dark)')
    media.addEventListener('change', onSystemThemeChange)
  }
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', onSystemThemeChange)
  media = null
})

const tokens = computed(() => {
  void systemThemeTick.value
  return chartTokens(resolvedIsDark())
})

async function load(): Promise<void> {
  loading.value = true
  const selected = RANGES.find(item => item.key === range.value) ?? RANGES[1]
  try {
    data.value = await statsApi.load({
      gatewayId: gatewayId.value || undefined,
      from: new Date(Date.now() - selected.ms).toISOString()
    })
    loadedAt.value = Date.now()
    loadFailed.value = false
  }
  catch (error) {
    loadFailed.value = true
    alerts.error('加载统计失败', error instanceof ApiError ? error.display : String(error))
  }
  finally {
    loading.value = false
  }
}

watch([range, gatewayId], () => void load(), { immediate: true })

/** 网关下拉的选项来自统计结果本身 —— 那里已经列全了所有网关，不必再拉一次列表。 */
const gatewayOptions = computed(() => data.value?.gateways ?? [])

const totals = computed(() => data.value?.totals ?? null)

const hasCalls = computed(() => (totals.value?.calls ?? 0) > 0)

const bucketLabel = computed(() => (data.value?.bucket === 'DAY' ? '天' : '小时'))

const rangeLabel = computed(() =>
  RANGES.find(item => item.key === range.value)?.label ?? '24 小时')

/** 选中网关的名字，用在标题和空状态文案里。 */
const scopedGatewayName = computed(() =>
  gatewayOptions.value.find(item => item.gatewayId === gatewayId.value)?.name ?? '')

// ------------------------------------------------------------------ 时间轴

function bucketTick(iso: string, bucket: 'HOUR' | 'DAY'): string {
  const date = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return bucket === 'DAY'
    ? `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    : `${pad(date.getHours())}:00`
}

// -------------------------------------------------------------- 图表 option

/**
 * 调用量趋势。
 *
 * 时间桶是离散的，所以是柱不是线；按状态堆叠，一眼看出"量涨了"还是"错涨了"。
 * 状态色跑过色觉障碍校验，堆叠段之间留一道表面色的缝，加上图例和表格视图 ——
 * 暗色下"超时/错误"那一对的可分辨度落在需要第二重编码的区间里。
 */
const trendOption = computed<EChartsOption>(() => {
  const t = tokens.value
  const points = data.value?.series ?? []
  const bucket = data.value?.bucket ?? 'HOUR'
  const labels = points.map(point => bucketTick(point.at, bucket))

  const series = [
    { name: '成功', color: t.success, values: points.map(point => point.success) },
    { name: '错误', color: t.error, values: points.map(point => point.error) },
    { name: '超时', color: t.timeout, values: points.map(point => point.timeout) }
  ]

  return {
    grid: { left: 4, right: 8, top: 34, bottom: 0, containLabel: true },
    legend: {
      top: 0, right: 0, itemWidth: 10, itemHeight: 10, itemGap: 14, icon: 'roundRect',
      textStyle: { color: t.textMuted, fontSize: 11, fontFamily: t.fontFamily }
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      ...tooltipStyle(t),
      formatter: (params: unknown) => {
        const rows = params as { name: string, seriesName: string, value: number }[]
        const index = (params as { dataIndex: number }[])[0]?.dataIndex ?? 0
        const point = points[index]
        const lines = rows
          .filter(row => row.value > 0)
          .map(row => `${row.seriesName} ${row.value}`)
        // 总计单独给：进行中的调用不在这三条堆叠里，但它算在总量上
        lines.push(`总计 ${point?.total ?? 0}`)
        return `<strong>${rows[0]?.name ?? ''}</strong><br>${lines.join('<br>')}`
      }
    },
    xAxis: {
      type: 'category',
      data: labels,
      ...axisStyle(t),
      splitLine: { show: false },
      axisLabel: {
        color: t.textMuted, fontSize: 11, fontFamily: t.fontFamily,
        // 桶多的时候自动隔几个显示一个，挤成一团就没法读了
        hideOverlap: true
      }
    },
    yAxis: { type: 'value', minInterval: 1, ...axisStyle(t) },
    series: series.map(item => ({
      name: item.name,
      type: 'bar' as const,
      stack: 'calls',
      barMaxWidth: 26,
      data: item.values,
      itemStyle: {
        color: item.color,
        borderRadius: [3, 3, 0, 0] as [number, number, number, number],
        // 段与段之间的缝用表面色画出来，颜色之外再多一重区分
        borderColor: t.surface,
        borderWidth: 1.5
      }
    }))
  }
})

/**
 * 横向条形图：一组带名字的量。
 *
 * 单序列、单一颜色 —— 身份由 y 轴的名字承担，颜色只表示"这是量"。
 * 名字（网关名、工具名、错误码）常常很长，横向排比竖着转 45 度好读得多。
 */
function barOption(items: { name: string, value: number, hint?: string }[],
  color: string, labelWidth = 120): EChartsOption {
  const t = tokens.value
  // ECharts 的类目轴从下往上排，倒过来才能让最大的在顶上
  const ordered = [...items].reverse()

  return {
    grid: { left: 4, right: 56, top: 6, bottom: 0, containLabel: true },
    tooltip: {
      trigger: 'item',
      ...tooltipStyle(t),
      formatter: (params: unknown) => {
        const row = params as { name: string, dataIndex: number, value: number }
        const item = ordered[row.dataIndex]
        return `<strong>${row.name}</strong><br>${countLabel(row.value)} 次`
          + (item?.hint ? `<br>${item.hint}` : '')
      }
    },
    xAxis: { type: 'value', minInterval: 1, ...axisStyle(t) },
    yAxis: {
      type: 'category',
      data: ordered.map(item => item.name),
      ...axisStyle(t),
      splitLine: { show: false },
      axisLabel: {
        color: t.textMuted, fontSize: 11, fontFamily: t.fontFamily,
        width: labelWidth, overflow: 'truncate'
      }
    },
    series: [{
      type: 'bar',
      barMaxWidth: 18,
      data: ordered.map(item => item.value),
      itemStyle: { color, borderRadius: [0, 4, 4, 0] as [number, number, number, number] },
      // 直接标数值：读数不用去比对刻度，也让颜色不再是唯一的信息载体
      label: {
        show: true, position: 'right', color: t.textMuted, fontSize: 11,
        fontFamily: t.fontFamily,
        formatter: (params: unknown) => countLabel((params as { value: number }).value)
      }
    }]
  }
}

/** 网关榜取前 8 个：再多就该去网关列表页看了。 */
const gatewayBars = computed(() =>
  gatewayOptions.value.filter(item => item.calls > 0).slice(0, 8))

const gatewayOption = computed(() => barOption(
  gatewayBars.value.map(item => ({
    name: item.name,
    value: item.calls,
    hint: `成功率 ${percentLabel(item.successRate)} · P95 ${durationLabel(item.p95DurationMs)}`
  })),
  tokens.value.accent))

const downstreamBars = computed<NamedStat[]>(() => data.value?.downstreams ?? [])

const downstreamOption = computed(() => barOption(
  downstreamBars.value.map(item => ({
    name: item.name,
    value: item.calls,
    hint: `失败 ${item.failures} · P95 ${durationLabel(item.p95DurationMs)}`
  })),
  tokens.value.accent))

const errorBars = computed(() => data.value?.errorCodes ?? [])

const errorOption = computed(() => barOption(
  errorBars.value.map(item => ({ name: item.code, value: item.count })),
  // 错误码是一长串大写字母，给足宽度 —— 截成 DOWNSTREAM_TIME… 等于把身份截掉了
  tokens.value.error, 190))

const tools = computed(() => data.value?.tools ?? [])

/**
 * 条形图的高度跟着柱子数量走。
 *
 * 固定高度在柱子少的时候最难看：两根柱被摊开在一大片空白里，中间的缝比柱子还宽，
 * 看着像图没画完。上下都设界，免得一根柱子矮到看不见、十根柱子把页面撑爆。
 */
function barHeight(count: number): string {
  return `${Math.min(15, Math.max(6, count * 1.9 + 3)).toFixed(1)}rem`
}

/** 工具表里那条底纹：按本页最大调用量归一，扫一眼就知道谁最忙。 */
const maxToolCalls = computed(() =>
  Math.max(1, ...tools.value.map(item => item.calls)))

// ------------------------------------------------------------------ 交互

/** 点网关的柱子就钻进那个网关 —— 这是这一页上最自然的下一步。 */
function selectGatewayBar(index: number): void {
  // barOption 里把顺序倒过来了，这里要倒回去
  const item = [...gatewayBars.value].reverse()[index]
  if (item) {
    gatewayId.value = item.gatewayId
  }
}

function callRecordsLink(id: string): string {
  return `/gateways/${id}/calls`
}
</script>

<template>
  <div class="page-head">
    <div>
      <h1>总览</h1>
      <p class="small muted">
        最近 {{ rangeLabel }}{{ scopedGatewayName ? ` · ${scopedGatewayName}` : ' · 全部网关' }}
      </p>
    </div>
    <div class="btn-row">
      <span v-if="loadedAt" class="small muted refresh-stamp">
        <AppIcon name="clock" :size="13" />
        更新于 {{ formatClock(loadedAt) }}
      </span>
      <button class="btn" type="button" @click="load">
        <AppIcon name="refresh" :size="14" /> 刷新
      </button>
    </div>
  </div>

  <!-- 筛选放在所有图表上方一行：改的是整页的口径，不是某一张图的 -->
  <section class="card">
    <div class="card-body filter-row">
      <div class="range-row">
        <span class="range-label">时间范围</span>
        <button v-for="item in RANGES" :key="item.key" type="button" class="chip-btn"
                :class="{ active: range === item.key }" @click="range = item.key">
          {{ item.label }}
        </button>
      </div>
      <label class="small muted scope-picker">
        网关
        <select v-model="gatewayId" class="control control-inline" ariaLabel="按网关筛选">
          <option value="">全部网关</option>
          <option v-for="item in gatewayOptions" :key="item.gatewayId" :value="item.gatewayId">
            {{ item.name }}
          </option>
        </select>
      </label>
    </div>
  </section>

  <div v-if="loadFailed" class="card">
    <div class="empty-state">
      <AppIcon name="warning" :size="28" />
      <div class="title">没能取到统计数据</div>
      <button class="btn" type="button" @click="load">重试</button>
    </div>
  </div>

  <template v-else>
    <!-- 四个数字回答"要不要紧"，剩下的图回答"问题在哪" -->
    <div class="stat-grid">
      <div class="stat-tile">
        <span class="label">调用量</span>
        <strong :class="{ skeleton: loading }">{{ loading ? '' : countLabel(totals?.calls ?? 0) }}</strong>
        <span class="sub">
          <template v-if="(totals?.started ?? 0) > 0">进行中 {{ totals?.started }}</template>
          <template v-else>最近 {{ rangeLabel }}</template>
        </span>
      </div>
      <div class="stat-tile">
        <span class="label">成功率</span>
        <strong :class="{ skeleton: loading, ok: (totals?.successRate ?? 1) >= 0.99,
                          bad: totals?.successRate !== null && (totals?.successRate ?? 1) < 0.95 }">
          {{ loading ? '' : percentLabel(totals?.successRate ?? null) }}
        </strong>
        <span class="sub">成功 {{ countLabel(totals?.success ?? 0) }} 次</span>
      </div>
      <div class="stat-tile">
        <span class="label">失败</span>
        <strong :class="{ skeleton: loading, bad: ((totals?.error ?? 0) + (totals?.timeout ?? 0)) > 0 }">
          {{ loading ? '' : countLabel((totals?.error ?? 0) + (totals?.timeout ?? 0)) }}
        </strong>
        <span class="sub">错误 {{ totals?.error ?? 0 }} · 超时 {{ totals?.timeout ?? 0 }}</span>
      </div>
      <div class="stat-tile">
        <span class="label">P95 耗时</span>
        <strong :class="{ skeleton: loading }">
          {{ loading ? '' : durationLabel(totals?.p95DurationMs ?? null) }}
        </strong>
        <span class="sub">平均 {{ durationLabel(totals?.avgDurationMs ?? null) }}</span>
      </div>
    </div>

    <section class="card">
      <div class="chart-head">
        <div>
          <h2>调用量趋势</h2>
          <p class="small muted">按{{ bucketLabel }}分桶，没有调用的桶补零</p>
        </div>
        <button type="button" class="chip-btn" @click="trendAsTable = !trendAsTable">
          {{ trendAsTable ? '看图表' : '看表格' }}
        </button>
      </div>

      <div v-if="loading" class="chart-placeholder skeleton"></div>

      <div v-else-if="!hasCalls" class="empty-state">
        <AppIcon name="inbox" :size="28" />
        <div class="title">这段时间没有调用</div>
        <p>换一个更长的时间范围，或者确认 Agent 是否在用这个网关。</p>
      </div>

      <div v-else-if="trendAsTable" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>时间</th>
              <th class="num">成功</th>
              <th class="num">错误</th>
              <th class="num">超时</th>
              <th class="num">总计</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="point in data?.series ?? []" :key="point.at">
              <td class="mono small">{{ bucketTick(point.at, data?.bucket ?? 'HOUR') }}</td>
              <td class="num small">{{ point.success }}</td>
              <td class="num small">{{ point.error }}</td>
              <td class="num small">{{ point.timeout }}</td>
              <td class="num small">{{ point.total }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <EChart v-else class="chart-body" :option="trendOption" height="17rem"
              ariaLabel="调用量趋势柱状图，可切换为表格查看具体数字" />
    </section>

    <div class="chart-grid">
      <section class="card">
        <div class="chart-head">
          <div>
            <h2>各网关调用量</h2>
            <p class="small muted">点柱子进入该网关</p>
          </div>
        </div>
        <div v-if="loading" class="chart-placeholder skeleton"></div>
        <div v-else-if="gatewayBars.length === 0" class="empty-state small">
          <div class="title">这段时间没有网关被调用</div>
        </div>
        <EChart v-else :option="gatewayOption" :height="barHeight(gatewayBars.length)"
                ariaLabel="各网关调用量条形图" @select="selectGatewayBar" />
      </section>

      <section class="card">
        <div class="chart-head">
          <div>
            <h2>子 MCP 调用量</h2>
            <p class="small muted">
              {{ gatewayId ? '按路由目标拆分' : '选一个网关后按子 MCP 拆分' }}
            </p>
          </div>
        </div>
        <div v-if="loading" class="chart-placeholder skeleton"></div>
        <div v-else-if="!gatewayId" class="empty-state small">
          <AppIcon name="hub" :size="24" />
          <div class="title">先在上面选一个网关</div>
          <p>子 MCP 属于具体网关，跨网关放在一起比没有意义。</p>
        </div>
        <div v-else-if="downstreamBars.length === 0" class="empty-state small">
          <div class="title">这段时间没有走到任何子 MCP</div>
          <p>未知工具和停用工具的调用没有路由目标，不计入这里。</p>
        </div>
        <EChart v-else :option="downstreamOption" :height="barHeight(downstreamBars.length)"
                ariaLabel="子 MCP 调用量条形图" />
      </section>

      <section class="card">
        <div class="chart-head">
          <div>
            <h2>调用最多的工具</h2>
            <p class="small muted">前 {{ tools.length }} 个</p>
          </div>
        </div>
        <div v-if="loading" class="chart-placeholder skeleton"></div>
        <div v-else-if="tools.length === 0" class="empty-state small">
          <div class="title">这段时间没有工具被调用</div>
        </div>
        <div v-else class="table-wrap">
          <table class="table tools-rank">
            <thead>
              <tr>
                <th>聚合工具</th>
                <th class="num">调用量</th>
                <th class="num">失败</th>
                <th class="num">P95</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="tool in tools" :key="tool.name">
                <td class="mono small">{{ tool.name }}</td>
                <td class="num small">
                  <span class="rank-value">{{ countLabel(tool.calls) }}</span>
                  <span class="rank-bar" aria-hidden="true">
                    <span class="rank-fill"
                          :style="{ width: `${(tool.calls / maxToolCalls) * 100}%` }"></span>
                  </span>
                </td>
                <td class="num small" :class="{ 'has-failures': tool.failures > 0 }">
                  {{ tool.failures }}
                </td>
                <td class="num small">{{ durationLabel(tool.p95DurationMs) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="card">
        <div class="chart-head">
          <div>
            <h2>错误码分布</h2>
            <p class="small muted">失败的调用按错误码归类</p>
          </div>
        </div>
        <div v-if="loading" class="chart-placeholder skeleton"></div>
        <div v-else-if="errorBars.length === 0" class="empty-state small">
          <div class="title">这段时间没有失败</div>
        </div>
        <EChart v-else :option="errorOption" :height="barHeight(errorBars.length)"
                ariaLabel="错误码分布条形图" />
      </section>
    </div>

    <section class="card">
      <div class="card-header">全部网关</div>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>网关</th>
              <th>状态</th>
              <th class="num">调用量</th>
              <th class="num">失败</th>
              <th class="num">成功率</th>
              <th class="num">P95</th>
              <th class="actions"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in gatewayOptions" :key="item.gatewayId">
              <td>
                <RouterLink :to="`/gateways/${item.gatewayId}`">{{ item.name }}</RouterLink>
                <code class="slug">{{ item.slug }}</code>
              </td>
              <td><StatusBadge :status="item.status" /></td>
              <td class="num small">{{ countLabel(item.calls) }}</td>
              <td class="num small" :class="{ 'has-failures': item.failures > 0 }">
                {{ item.failures }}
              </td>
              <td class="num small">{{ percentLabel(item.successRate) }}</td>
              <td class="num small">{{ durationLabel(item.p95DurationMs) }}</td>
              <td class="actions">
                <RouterLink class="btn btn-sm" :to="callRecordsLink(item.gatewayId)">调用记录</RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </template>
</template>
