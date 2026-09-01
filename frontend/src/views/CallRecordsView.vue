
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { callRecordApi, gatewayApi } from '../api/gateways'
import { ApiError } from '../api/client'
import type { CallRecordDetail, CallRecordPage, CallStatus, GatewayDetail } from '../api/types'
import { formatDateTime, formatRelative } from '../utils/datetime'
import { useAlerts } from '../composables/useAlerts'
import CallStatusBadge from '../components/CallStatusBadge.vue'
import AppIcon from '../components/AppIcon.vue'
import TableSkeleton from '../components/TableSkeleton.vue'

/*
 * 调用记录查询页（需求 FR-06.5）。
 *
 * MVP 阶段这块是空的 —— 排障只能停掉应用连 H2 查库。
 *
 * 两个设计点跟着后端接口走：
 * - 列表不含入参和返回正文，展开某一行时才按 callId 取单条。那两个字段各可能接近 1 MiB。
 * - statusCounts 不套用 status 筛选，所以可以拿它做分面切换。
 */
const route = useRoute()
const alerts = useAlerts()

const gatewayId = computed(() => String(route.params.id ?? ''))

const gateway = ref<GatewayDetail | null>(null)
const result = ref<CallRecordPage | null>(null)
const loading = ref(true)
const loadFailed = ref(false)

/** 展开的那一行，以及已经取回来的详情（按 callId 缓存，来回折叠不重复请求）。 */
const openCallId = ref<string | null>(null)
const details = reactive<Record<string, CallRecordDetail>>({})
const detailLoading = ref<string | null>(null)

const PAGE_SIZE = 20

const filters = reactive({
  toolName: '',
  downstreamMcpId: '',
  status: '' as CallStatus | '',
  traceId: '',
  from: '',
  to: '',
  page: 0
})

const STATUSES: CallStatus[] = ['SUCCESS', 'ERROR', 'TIMEOUT', 'STARTED']

const totalCount = computed(() =>
  STATUSES.reduce((sum, status) => sum + (result.value?.statusCounts[status] ?? 0), 0))

const rangeLabel = computed(() => {
  const page = result.value
  if (!page || page.total === 0) {
    return '共 0 条'
  }
  const first = page.page * page.size + 1
  const last = Math.min(first + page.items.length - 1, page.total)
  return `第 ${first}–${last} 条，共 ${page.total} 条`
})

const hasPrev = computed(() => (result.value?.page ?? 0) > 0)
const hasNext = computed(() => {
  const page = result.value
  return page ? (page.page + 1) * page.size < page.total : false
})

function describe(error: unknown): string {
  return error instanceof ApiError ? error.display : String(error)
}

/**
 * datetime-local 的值没有时区，浏览器按本地时间理解；
 * 服务端要的是 ISO-8601 instant，这里转一道。
 */
function toInstant(local: string): string | undefined {
  if (!local) {
    return undefined
  }
  const parsed = new Date(local)
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString()
}

async function load(): Promise<void> {
  loading.value = true
  try {
    result.value = await callRecordApi.search(gatewayId.value, {
      toolName: filters.toolName.trim() || undefined,
      downstreamMcpId: filters.downstreamMcpId || undefined,
      status: filters.status || undefined,
      traceId: filters.traceId.trim() || undefined,
      from: toInstant(filters.from),
      to: toInstant(filters.to),
      page: filters.page,
      size: PAGE_SIZE
    })
    loadFailed.value = false
  }
  catch (error) {
    loadFailed.value = true
    alerts.error('加载调用记录失败', describe(error))
  }
  finally {
    loading.value = false
  }
}

async function loadGateway(): Promise<void> {
  try {
    gateway.value = await gatewayApi.detail(gatewayId.value)
  }
  catch {
    // 只用于面包屑和子 MCP 下拉，取不到不影响主功能
    gateway.value = null
  }
}

watch(gatewayId, () => {
  void loadGateway()
  void load()
}, { immediate: true })

/** 改筛选条件就回到第一页 —— 停在第 5 页看一个只有 2 页的结果集会让人以为没数据。 */
function applyFilters(): void {
  filters.page = 0
  openCallId.value = null
  void load()
}

function selectStatus(status: CallStatus | ''): void {
  filters.status = filters.status === status ? '' : status
  applyFilters()
}

function filterByTrace(traceId: string): void {
  filters.traceId = traceId
  applyFilters()
}

function reset(): void {
  filters.toolName = ''
  filters.downstreamMcpId = ''
  filters.status = ''
  filters.traceId = ''
  filters.from = ''
  filters.to = ''
  applyFilters()
}

function goto(page: number): void {
  filters.page = page
  openCallId.value = null
  void load()
}

async function toggle(callId: string): Promise<void> {
  if (openCallId.value === callId) {
    openCallId.value = null
    return
  }
  openCallId.value = callId
  if (details[callId]) {
    return
  }
  detailLoading.value = callId
  try {
    details[callId] = await callRecordApi.detail(gatewayId.value, callId)
  }
  catch (error) {
    openCallId.value = null
    alerts.error('取调用详情失败', describe(error))
  }
  finally {
    detailLoading.value = null
  }
}

/**
 * 缩进展示。
 *
 * 解析失败就原样显示 —— 库里可能是打点时的占位符（序列化失败会写
 * {"_unserializable":true}），也可能是历史遗留的坏数据，不该因为格式化不了就什么都不给。
 */
function pretty(json: string | null): string {
  if (json === null || json === '') {
    return '（无）'
  }
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  }
  catch {
    return json
  }
}

function durationLabel(ms: number | null): string {
  if (ms === null) {
    return '—'
  }
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(2)} s`
}
</script>

<template>
  <nav class="breadcrumb" aria-label="面包屑">
    <RouterLink to="/gateways">网关列表</RouterLink>
    <span aria-hidden="true">/</span>
    <RouterLink :to="`/gateways/${gatewayId}`">{{ gateway?.name ?? '网关' }}</RouterLink>
    <span aria-hidden="true">/</span>
    <span>调用记录</span>
  </nav>

  <div class="page-head">
    <h1>调用记录</h1>
    <RouterLink class="btn" :to="`/gateways/${gatewayId}`">返回网关详情</RouterLink>
  </div>

  <section class="card">
    <div class="card-body stack">
      <!-- 状态分面。计数不受 status 筛选影响，所以可以直接拿来切换 -->
      <div class="facets">
        <button type="button" class="facet" :class="{ active: filters.status === '' }"
                @click="selectStatus('')">
          全部 <span class="count">{{ totalCount }}</span>
        </button>
        <button v-for="status in STATUSES" :key="status" type="button" class="facet"
                :class="{ active: filters.status === status }" @click="selectStatus(status)">
          {{ status }} <span class="count">{{ result?.statusCounts[status] ?? 0 }}</span>
        </button>
      </div>

      <form class="filters" @submit.prevent="applyFilters">
        <div class="field">
          <label for="f-tool">聚合工具名</label>
          <input id="f-tool" v-model="filters.toolName" class="control mono"
                 placeholder="包含匹配，如 kb_a__">
        </div>
        <div class="field">
          <label for="f-downstream">子 MCP</label>
          <select id="f-downstream" v-model="filters.downstreamMcpId" class="control">
            <option value="">全部</option>
            <option v-for="downstream in gateway?.downstreams ?? []" :key="downstream.id"
                    :value="downstream.id">{{ downstream.name }}</option>
          </select>
        </div>
        <div class="field">
          <label for="f-trace">trace_id</label>
          <input id="f-trace" v-model="filters.traceId" class="control mono" placeholder="精确匹配">
        </div>
        <div class="field">
          <label for="f-from">开始时间（含）</label>
          <input id="f-from" v-model="filters.from" class="control" type="datetime-local">
        </div>
        <div class="field">
          <label for="f-to">结束时间（不含）</label>
          <input id="f-to" v-model="filters.to" class="control" type="datetime-local">
        </div>
        <div class="field justify-end">
          <div class="btn-row">
            <button class="btn btn-primary" type="submit">筛选</button>
            <button class="btn btn-link" type="button" @click="reset">重置</button>
          </div>
        </div>
      </form>
    </div>
  </section>

  <section class="card">
    <TableSkeleton v-if="loading" :rows="6" />

    <div v-else-if="loadFailed" class="empty-state">
      <AppIcon name="warning" :size="28" />
      <div class="title">没能取到调用记录</div>
      <button class="btn" type="button" @click="load">重试</button>
    </div>

    <div v-else-if="!result || result.items.length === 0" class="empty-state">
      <template v-if="totalCount === 0">
        <AppIcon name="inbox" :size="30" />
        <div class="title">这个网关还没有任何调用记录</div>
        <p>Agent 调用一次工具之后就会出现。每次 <code>tools/call</code> 都会留下一条，
          未知工具和已停用工具的调用同样不会漏。</p>
      </template>
      <template v-else>
        <AppIcon name="search" :size="28" />
        <div class="title">没有符合当前筛选条件的记录</div>
        <button class="btn" type="button" @click="reset">清空筛选</button>
      </template>
    </div>

    <template v-else>
      <div class="table-wrap">
        <table class="table records">
          <thead>
            <tr>
              <th class="expander"></th>
              <th>开始时间</th>
              <th>聚合工具</th>
              <th>状态</th>
              <th class="num">耗时</th>
              <th>错误</th>
              <th>trace_id</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in result.items" :key="item.callId">
              <tr class="record" :class="{ open: openCallId === item.callId }"
                  @click="toggle(item.callId)">
                <td class="expander"><AppIcon name="chevron" :size="14" /></td>
                <td class="small" :title="formatDateTime(item.startedAt)">
                  {{ formatRelative(item.startedAt) }}
                </td>
                <td class="mono small">
                  {{ item.exposedToolName }}
                  <!-- 目标为空 = 未知工具或停用工具的调用，这类记录同样不能缺 -->
                  <span v-if="!item.downstreamMcpId" class="muted">（无路由目标）</span>
                </td>
                <td><CallStatusBadge :status="item.status" /></td>
                <td class="num small">{{ durationLabel(item.durationMs) }}</td>
                <td class="small">
                  <span v-if="item.errorCode" class="mono">{{ item.errorCode }}</span>
                  <span v-else class="muted">—</span>
                </td>
                <td class="mono small">
                  <button class="linkish" type="button" title="按这条链路筛选"
                          @click.stop="filterByTrace(item.traceId)">{{ item.traceId }}</button>
                </td>
              </tr>

              <tr v-if="openCallId === item.callId" class="detail-row">
                <td colspan="7">
                  <div v-if="detailLoading === item.callId" class="detail-panel muted">加载中…</div>

                  <div v-else-if="details[item.callId]" class="detail-panel">
                    <div class="detail-meta">
                      <div>
                        <span class="label">call_id</span>
                        <span class="mono small">{{ details[item.callId].callId }}</span>
                      </div>
                      <div>
                        <span class="label">原工具名</span>
                        <span class="mono small">{{ details[item.callId].originalToolName ?? '—' }}</span>
                      </div>
                      <div>
                        <span class="label">开始 / 结束</span>
                        <span class="small">
                          {{ formatDateTime(details[item.callId].startedAt) }}
                          →
                          {{ details[item.callId].finishedAt
                              ? formatDateTime(details[item.callId].finishedAt) : '—' }}
                        </span>
                      </div>
                      <div v-if="details[item.callId].errorMessage">
                        <span class="label">错误摘要（已脱敏）</span>
                        <span class="small">{{ details[item.callId].errorMessage }}</span>
                      </div>
                    </div>

                    <div>
                      <span class="label">入参</span>
                      <pre class="json-block">{{ pretty(details[item.callId].requestJson) }}</pre>
                    </div>
                    <div>
                      <span class="label">返回</span>
                      <pre class="json-block">{{ pretty(details[item.callId].responseJson) }}</pre>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      <div class="card-body pager">
        <span class="small muted">{{ rangeLabel }}</span>
        <div class="btn-row">
          <button class="btn btn-sm" type="button" :disabled="!hasPrev"
                  @click="goto(result.page - 1)">上一页</button>
          <button class="btn btn-sm" type="button" :disabled="!hasNext"
                  @click="goto(result.page + 1)">下一页</button>
        </div>
      </div>
    </template>
  </section>

  <p class="small muted">
    记录里不含任何 header、网关令牌或子 MCP 凭证（需求 FR-06.3）；
    但<strong>入参和返回是子 MCP 的原始内容</strong>，按部署要求保护。
  </p>
</template>
