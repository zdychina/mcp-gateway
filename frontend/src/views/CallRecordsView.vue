<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQuery } from 'vue-router'
import { callRecordApi, gatewayApi } from '../api/gateways'
import { ApiError } from '../api/client'
import type { CallRecordDetail, CallRecordPage, CallStatus, GatewayDetail } from '../api/types'
import { formatClock, formatExact, formatRelative, formatStamp, toLocalInput } from '../utils/datetime'
import { downloadBlob } from '../utils/download'
import { useAlerts } from '../composables/useAlerts'
import { useCallColumns } from '../composables/useCallColumns'
import type { ColumnDef, ExtractSpec } from '../composables/useCallColumns'
import CallStatusBadge from '../components/CallStatusBadge.vue'
import AppIcon from '../components/AppIcon.vue'
import CopyButton from '../components/CopyButton.vue'
import ExtractedCell from '../components/ExtractedCell.vue'
import TableSkeleton from '../components/TableSkeleton.vue'

/*
 * 调用记录查询页（需求 FR-06.5）。
 *
 * MVP 阶段这块是空的 —— 排障只能停掉应用连 H2 查库。
 *
 * 页面按排障时真正的动作顺序排：先看有没有错（状态分面 + 成功率），再框时间窗，
 * 然后按工具或链路收敛，最后展开一条看正文。几个约束跟着后端接口走：
 * - 列表不含入参和返回正文，展开某一行时才按 callId 取单条。那两个字段各可能接近 1 MiB。
 * - statusCounts 不套用 status 筛选，所以既能拿它做分面切换，也能拿来算成功率。
 * - 后端只按 startedAt 倒序返回，没有排序参数，所以表头不做成可点的 —— 点不动的排序
 *   比没有排序更让人恼火。
 */
const route = useRoute()
const router = useRouter()
const alerts = useAlerts()

const gatewayId = computed(() => String(route.params.id ?? ''))

/*
 * 列配置（按网关存在 localStorage 里）。
 *
 * 内置列只是开关和排序；抽取列会变成请求上的 extract 参数，由服务端按 JSON Pointer
 * 从入参/返回里抽值 —— 列表接口本身仍然不返回正文，见 SECURITY.md。
 */
const {
  visibleColumns,
  menuColumns,
  extractSpecs,
  isVisible: isColumnVisible,
  toggle: toggleColumn,
  move: moveColumn,
  addExtractColumn,
  removeExtractColumn,
  reset: resetColumns
} = useCallColumns(gatewayId)

const gateway = ref<GatewayDetail | null>(null)
const result = ref<CallRecordPage | null>(null)
const loading = ref(true)
const loadFailed = ref(false)
/** 最近一次成功取到数据的时刻。开着自动刷新时，这是唯一能说明"数据有多新"的东西。 */
const loadedAt = ref<number | null>(null)

/** 展开的那一行，以及已经取回来的详情（按 callId 缓存，来回折叠不重复请求）。 */
const openCallId = ref<string | null>(null)
const details = reactive<Record<string, CallRecordDetail>>({})
const detailLoading = ref<string | null>(null)
/**
 * 正文默认格式化，入参和返回各自一份开关。
 *
 * 合成一个开关看着更省事，但排障时的动作往往是"把返回按原样贴出去、入参还留着缩进看"，
 * 一个开关会把另一边一起翻掉。
 */
const showRaw = reactive({ request: false, response: false })

const DEFAULT_PAGE_SIZE = 20
/** 后端上限就是 100（CallRecordService.MAX_PAGE_SIZE），别给出取不到的选项。 */
const PAGE_SIZES = [20, 50, 100]

const STATUSES: CallStatus[] = ['SUCCESS', 'ERROR', 'TIMEOUT', 'STARTED']

/** 时间窗快捷键。排障问的绝大多数是"刚才"，不是某个具体时刻。 */
const RANGES = [
  { key: '15m', label: '15 分钟', ms: 15 * 60_000 },
  { key: '1h', label: '1 小时', ms: 60 * 60_000 },
  { key: '24h', label: '24 小时', ms: 24 * 60 * 60_000 },
  { key: '7d', label: '7 天', ms: 7 * 24 * 60 * 60_000 }
]

const AUTO_REFRESH_OPTIONS = [
  { value: 0, label: '不自动刷新' },
  { value: 10, label: '每 10 秒' },
  { value: 30, label: '每 30 秒' },
  { value: 60, label: '每 60 秒' }
]

const filters = reactive({
  toolName: '',
  downstreamMcpId: '',
  status: '' as CallStatus | '',
  traceId: '',
  /** ISO instant，含。快捷键和自定义输入最终都落到这两个字段上 */
  from: '',
  /** ISO instant，不含 */
  to: '',
  /** 选中的时间窗快捷键，自定义时间时为空。决定高亮，以及自动刷新时窗口跟不跟着滑 */
  range: '',
  page: 0,
  size: DEFAULT_PAGE_SIZE
})

/** 自定义时间输入默认收起 —— 十次里有九次点快捷键就够了。 */
const customRangeOpen = ref(false)

// -------------------------------------------------------------- URL 同步

/*
 * 筛选条件全部落在 URL 上。
 *
 * 这不是锦上添花：排障的产物往往就是"这个链接里能看到那次失败"，能贴进工单和聊天窗
 * 才算查完。顺带解决刷新页面丢筛选、后退键一路退回列表页这两件烦人的事。
 */
function queryFromFilters(): Record<string, string> {
  const query: Record<string, string> = {}
  const put = (key: string, value: string) => {
    if (value) {
      query[key] = value
    }
  }
  put('tool', filters.toolName.trim())
  put('ds', filters.downstreamMcpId)
  put('status', filters.status)
  put('trace', filters.traceId.trim())
  put('from', filters.from)
  put('to', filters.to)
  put('range', filters.range)
  if (filters.page > 0) {
    query.page = String(filters.page)
  }
  if (filters.size !== DEFAULT_PAGE_SIZE) {
    query.size = String(filters.size)
  }
  return query
}

function readQuery(query: LocationQuery): void {
  const get = (key: string) => {
    const value = query[key]
    return typeof value === 'string' ? value : ''
  }
  filters.toolName = get('tool')
  filters.downstreamMcpId = get('ds')
  filters.status = (STATUSES as string[]).includes(get('status')) ? get('status') as CallStatus : ''
  filters.traceId = get('trace')
  filters.from = get('from')
  filters.to = get('to')
  filters.range = RANGES.some(range => range.key === get('range')) ? get('range') : ''
  filters.page = Math.max(0, Number(get('page')) || 0)
  const size = Number(get('size'))
  filters.size = PAGE_SIZES.includes(size) ? size : DEFAULT_PAGE_SIZE
  customRangeOpen.value = !filters.range && Boolean(filters.from || filters.to)
}

/** 顺序无关的比较用串，用来判断一次 query 变化是不是页面自己写进去的。 */
function serialize(query: Record<string, unknown>): string {
  return Object.keys(query).sort()
    .map(key => `${key}=${String(query[key] ?? '')}`)
    .join('&')
}

function syncUrl(): void {
  const next = queryFromFilters()
  if (serialize(next) !== serialize(route.query)) {
    // replace 而不是 push：调一次筛选条件不该在浏览器历史里堆出十几步
    void router.replace({ query: next })
  }
}

// ------------------------------------------------------------------ 取数

const totalCount = computed(() =>
  STATUSES.reduce((sum, status) => sum + (result.value?.statusCounts[status] ?? 0), 0))

/** 成功率用 statusCounts 算：它不套用 status 筛选，所以点进 ERROR 分面后这个数字依然成立。 */
const successRate = computed(() => {
  const total = totalCount.value
  return total === 0 ? null : (result.value?.statusCounts.SUCCESS ?? 0) / total
})

const failureCount = computed(() =>
  (result.value?.statusCounts.ERROR ?? 0) + (result.value?.statusCounts.TIMEOUT ?? 0))

const rangeLabel = computed(() => {
  const page = result.value
  if (!page || page.total === 0) {
    return '共 0 条'
  }
  const first = page.page * page.size + 1
  const last = Math.min(first + page.items.length - 1, page.total)
  return `第 ${first}–${last} 条，共 ${page.total} 条`
})

const totalPages = computed(() => {
  const page = result.value
  return !page || page.total === 0 ? 0 : Math.ceil(page.total / page.size)
})

/**
 * 页码按钮：首页、末页、当前页左右各一个，中间的空档用省略号顶上。
 *
 * 当前页贴边时往另一侧多补一个，让按钮个数保持恒定 —— 否则翻到第 2 页时
 * 整排按钮会左右跳一下，鼠标就落空了。
 */
const pageItems = computed<(number | 'gap')[]>(() => {
  const total = totalPages.value
  const current = result.value?.page ?? 0
  if (total <= 1) {
    return []
  }

  const wanted = new Set([0, total - 1, current - 1, current, current + 1])
  if (current <= 1) {
    wanted.add(2)
  }
  if (current >= total - 2) {
    wanted.add(total - 3)
  }

  const pages = [...wanted].filter(page => page >= 0 && page < total).sort((a, b) => a - b)
  const items: (number | 'gap')[] = []
  let previous = -1
  for (const page of pages) {
    if (previous >= 0 && page - previous > 1) {
      items.push('gap')
    }
    items.push(page)
    previous = page
  }
  return items
})

/**
 * 页码按钮省略之后，跳转框才有意义。
 *
 * 页数少的时候它纯属噪声 —— 想去第 3 页，点一下"3"就够了。
 */
const showJump = computed(() => pageItems.value.includes('gap'))

/** type=number 的输入框上 Vue 会自动套 .number，所以这里可能是数字也可能是空串。 */
const jumpTo = ref<string | number>('')

/**
 * 跳转到指定页。
 *
 * 超出范围**收敛到边界**，不报错也不照发 —— 页码超范围时服务端会老老实实返回空列表，
 * 而空列表会把分页栏本身一起换成"没有符合条件的记录"，人就困在那儿了，
 * 只能去清筛选。这是这个页面上唯一一处"越界即收敛"比"越界即报错"更好的地方。
 */
function onJump(): void {
  // 空串必须先挡掉：Number('') 是 0，会被下面收敛成第 1 页，等于按个回车就跳走了
  const raw = String(jumpTo.value ?? '').trim()
  const requested = Number(raw)
  if (raw === '' || !Number.isFinite(requested) || totalPages.value === 0) {
    return
  }
  const clamped = Math.min(Math.max(Math.trunc(requested), 1), totalPages.value)
  jumpTo.value = ''
  goto(clamped - 1)
}

const hasPrev = computed(() => (result.value?.page ?? 0) > 0)
const hasNext = computed(() => {
  const page = result.value
  return page ? (page.page + 1) * page.size < page.total : false
})

/** 耗时条按本页最大值归一 —— 固定刻度在毫秒级和秒级的两页之间没有可比性。 */
const maxDuration = computed(() =>
  Math.max(1, ...(result.value?.items ?? []).map(item => item.durationMs ?? 0)))

function describe(error: unknown): string {
  return error instanceof ApiError ? error.display : String(error)
}

/**
 * datetime-local 的值没有时区，浏览器按本地时间理解；
 * 服务端和 URL 上要的都是 ISO-8601 instant，这里转一道。
 */
function toInstant(local: string): string | undefined {
  if (!local) {
    return undefined
  }
  const parsed = new Date(local)
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString()
}

/*
 * 同一份数据请求两遍，是自动刷新最容易踩的坑：定时器和翻页撞在一起，慢的那个后回来，
 * 页面就退回上一页的内容。两道防线 ——
 * loadedKey 挡掉重复请求，requestSeq 挡掉已经过期的响应。
 */
let requestSeq = 0
let loadedKey = ''

async function load(options: { silent?: boolean, force?: boolean, markNew?: boolean } = {}): Promise<void> {
  // 抽取列也算进去：加一列正文字段同样要重新查一次
  const key = `${gatewayId.value}|${serialize(queryFromFilters())}|${extractSpecs.value.join(',')}`
  if (!options.force && key === loadedKey) {
    return
  }
  loadedKey = key

  const seq = ++requestSeq
  if (!options.silent) {
    loading.value = true
  }
  try {
    const page = await callRecordApi.search(gatewayId.value, {
      toolName: filters.toolName.trim() || undefined,
      downstreamMcpId: filters.downstreamMcpId || undefined,
      status: filters.status || undefined,
      traceId: filters.traceId.trim() || undefined,
      from: filters.from || undefined,
      to: filters.to || undefined,
      page: filters.page,
      size: filters.size,
      extract: extractSpecs.value.length > 0 ? extractSpecs.value : undefined
    })
    if (seq !== requestSeq) {
      return
    }
    markFresh(page, options.markNew === true)
    result.value = page
    loadedAt.value = Date.now()
    loadFailed.value = false
  }
  catch (error) {
    if (seq !== requestSeq) {
      return
    }
    loadedKey = ''
    if (options.silent) {
      // 自动刷新失败先停下来，否则错误提示会按秒往外冒
      autoRefresh.value = 0
      alerts.warning('自动刷新已停止', describe(error))
    }
    else {
      loadFailed.value = true
      alerts.error('加载调用记录失败', describe(error))
    }
  }
  finally {
    if (seq === requestSeq) {
      loading.value = false
    }
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
  readQuery(route.query)
  void loadGateway()
  void load()
}, { immediate: true })

// 加、删或重排抽取列都要重新取数：值是服务端算的，前端手里没有正文
watch(() => extractSpecs.value.join('|'), () => {
  void load({ force: true })
})

// 后退/前进键，以及别人发过来的带筛选条件的链接
watch(() => route.query, (query) => {
  if (serialize(query as Record<string, unknown>) === serialize(queryFromFilters())) {
    return
  }
  readQuery(query)
  openCallId.value = null
  void load()
})

// -------------------------------------------------------------- 自动刷新

/**
 * 自动刷新间隔，秒；0 是关。
 *
 * 默认关着：这个页面的每次刷新都是一次带筛选条件的库查询，不该因为有人开着标签页就一直跑。
 */
const autoRefresh = ref(0)
let timer: number | undefined

function stopTimer(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

watch(autoRefresh, (seconds) => {
  stopTimer()
  if (seconds > 0) {
    timer = window.setInterval(tick, seconds * 1000)
  }
})

onBeforeUnmount(stopTimer)

function tick(): void {
  // 标签页在后台就不刷 —— 没人在看，白给数据库加压
  if (typeof document !== 'undefined' && document.hidden) {
    return
  }
  if (filters.range) {
    // 快捷时间窗跟着"现在"滑动，否则挂一晚上会停在昨天的窗口里
    slideRange()
    syncUrl()
  }
  void load({ silent: true, force: true, markNew: true })
}

function manualRefresh(): void {
  if (filters.range) {
    slideRange()
    syncUrl()
  }
  void load({ force: true })
}

function slideRange(): void {
  const range = RANGES.find(item => item.key === filters.range)
  if (range) {
    filters.from = new Date(Date.now() - range.ms).toISOString()
    filters.to = ''
  }
}

/*
 * 自动刷新带进来的新记录闪一下。
 *
 * 盯着一个二十行的列表看，"最上面多了两行"是很容易漏掉的变化。
 * 首次加载和翻页不算 —— 那时整页都是新的，全闪等于没闪。
 */
const freshIds = ref(new Set<string>())
const seenIds = new Set<string>()

function markFresh(page: CallRecordPage, enabled: boolean): void {
  const fresh = new Set<string>()
  if (enabled && seenIds.size > 0) {
    for (const item of page.items) {
      if (!seenIds.has(item.callId)) {
        fresh.add(item.callId)
      }
    }
  }
  freshIds.value = fresh
  for (const item of page.items) {
    seenIds.add(item.callId)
  }
}

// ------------------------------------------------------------------ 筛选

/** 改筛选条件就回到第一页 —— 停在第 5 页看一个只有 2 页的结果集会让人以为没数据。 */
function applyFilters(): void {
  filters.page = 0
  openCallId.value = null
  syncUrl()
  void load()
}

function selectStatus(status: CallStatus | ''): void {
  filters.status = filters.status === status ? '' : status
  applyFilters()
}

function selectRange(key: string): void {
  if (filters.range === key) {
    filters.range = ''
    filters.from = ''
    filters.to = ''
  }
  else {
    filters.range = key
    slideRange()
    customRangeOpen.value = false
  }
  applyFilters()
}

/*
 * 自定义时间输入。
 *
 * 手改就不再算"最近 N"（range 清空），否则下一次自动刷新会把手填的窗口冲掉。
 */
const fromInput = computed({
  get: () => toLocalInput(filters.from),
  set: (value: string) => {
    filters.from = toInstant(value) ?? ''
    filters.range = ''
  }
})

const toInput = computed({
  get: () => toLocalInput(filters.to),
  set: (value: string) => {
    filters.to = toInstant(value) ?? ''
    filters.range = ''
  }
})

function filterByTrace(traceId: string): void {
  filters.traceId = traceId
  applyFilters()
}

function filterByTool(toolName: string): void {
  filters.toolName = toolName
  applyFilters()
}

function reset(): void {
  filters.toolName = ''
  filters.downstreamMcpId = ''
  filters.status = ''
  filters.traceId = ''
  filters.from = ''
  filters.to = ''
  filters.range = ''
  customRangeOpen.value = false
  applyFilters()
}

/**
 * 当前生效的条件，逐条可撤销。
 *
 * 时间窗和工具名都在上面的表单里，翻到第 3 页时早滚出视野了 ——
 * "怎么就这几条"十有八九是忘了自己还开着一个筛选。
 */
const activeChips = computed(() => {
  const chips: { key: string, label: string, value: string, clear: () => void }[] = []
  if (filters.range) {
    const range = RANGES.find(item => item.key === filters.range)
    chips.push({
      key: 'range',
      label: '时间',
      value: `最近 ${range?.label ?? filters.range}`,
      clear: () => selectRange(filters.range)
    })
  }
  else if (filters.from || filters.to) {
    chips.push({
      key: 'range',
      label: '时间',
      value: `${filters.from ? formatExact(filters.from) : '不限'} → ${filters.to ? formatExact(filters.to) : '现在'}`,
      clear: () => {
        filters.from = ''
        filters.to = ''
        applyFilters()
      }
    })
  }
  if (filters.toolName) {
    chips.push({
      key: 'tool',
      label: '工具名包含',
      value: filters.toolName,
      clear: () => {
        filters.toolName = ''
        applyFilters()
      }
    })
  }
  if (filters.downstreamMcpId) {
    chips.push({
      key: 'ds',
      label: '子 MCP',
      value: downstreamName(filters.downstreamMcpId),
      clear: () => {
        filters.downstreamMcpId = ''
        applyFilters()
      }
    })
  }
  if (filters.traceId) {
    chips.push({
      key: 'trace',
      label: 'trace_id',
      value: filters.traceId,
      clear: () => {
        filters.traceId = ''
        applyFilters()
      }
    })
  }
  if (filters.status) {
    chips.push({
      key: 'status',
      label: '状态',
      value: filters.status,
      clear: () => selectStatus(filters.status)
    })
  }
  return chips
})

// ------------------------------------------------------------------ 导出

/** 服务端的导出行数上限（CallRecordService.MAX_EXPORT_ROWS）。 */
const MAX_EXPORT_ROWS = 5000

const exporting = ref(false)

/**
 * 导出当前筛选结果。
 *
 * 导的是**筛选后的全部**，不是当前这一页 —— 翻 62 页导 62 次没有意义。
 * 列和表头跟着页面上的列配置走：导出来的表和屏幕上看到的是同一张。
 */
async function exportExcel(): Promise<void> {
  if (exporting.value) {
    return
  }

  const total = result.value?.total ?? 0
  if (total === 0) {
    alerts.warning('没有可导出的记录', '当前筛选条件下一条都没有。')
    return
  }
  if (total > MAX_EXPORT_ROWS
    && !window.confirm(`当前筛选结果有 ${total} 条，一次最多导出 ${MAX_EXPORT_ROWS} 条。\n\n`
      + '文件里只会有最近的那部分，其余的请缩小时间范围后分批导。\n\n继续吗？')) {
    return
  }

  exporting.value = true
  try {
    const { blob, headers } = await callRecordApi.exportFile(
      gatewayId.value,
      {
        toolName: filters.toolName.trim() || undefined,
        downstreamMcpId: filters.downstreamMcpId || undefined,
        status: filters.status || undefined,
        traceId: filters.traceId.trim() || undefined,
        from: filters.from || undefined,
        to: filters.to || undefined
      },
      visibleColumns.value.map(column => column.key),
      visibleColumns.value.map(column => column.label))

    const stamp = formatClock(Date.now()).replace(/:/g, '')
    downloadBlob(blob, `调用记录-${gateway.value?.slug ?? gatewayId.value}-${stamp}.xlsx`)

    const rows = headers.get('X-Export-Rows') ?? '?'
    if (headers.get('X-Export-Truncated') === 'true') {
      alerts.warning(`已导出 ${rows} 条`,
        `筛选结果共 ${headers.get('X-Export-Total')} 条，超过一次导出的上限，`
        + '文件里只有最近的部分。缩小时间范围可以分批导全。')
    }
    else {
      alerts.success(`已导出 ${rows} 条`, '文件已开始下载。')
    }
  }
  catch (error) {
    alerts.error('导出失败', describe(error))
  }
  finally {
    exporting.value = false
  }
}

// ---------------------------------------------------------------- 列菜单

/** "添加正文列"表单的草稿。 */
const draft = reactive<{ source: ExtractSpec['source'], pointer: string, label: string }>({
  source: 'request',
  pointer: '',
  label: ''
})

const addColumnError = ref('')

function onAddColumn(): void {
  const error = addExtractColumn({ source: draft.source, pointer: draft.pointer.trim() },
    draft.label)
  addColumnError.value = error ?? ''
  if (!error) {
    draft.pointer = ''
    draft.label = ''
  }
}

/**
 * 单元格的类名。
 *
 * 抽取列的键里有 `/` 和 `:`，不能直接当类名用，统一走 col-extract。
 */
function cellClass(column: ColumnDef): string {
  return column.extract ? 'col-extract' : `col-${column.key}`
}

// ------------------------------------------------------------------ 分页

function goto(page: number): void {
  filters.page = page
  openCallId.value = null
  syncUrl()
  void load()
}

function changeSize(event: Event): void {
  filters.size = Number((event.target as HTMLSelectElement).value)
  applyFilters()
}

// ------------------------------------------------------------------ 详情

async function toggle(callId: string): Promise<void> {
  if (openCallId.value === callId) {
    openCallId.value = null
    return
  }
  openCallId.value = callId
  showRaw.request = false
  showRaw.response = false
  if (details[callId]) {
    return
  }
  detailLoading.value = callId
  try {
    const record = await callRecordApi.detail(gatewayId.value, callId)
    details[callId] = record
    payloadSizes[callId] = {
      request: sizeLabel(record.requestJson),
      response: sizeLabel(record.responseJson)
    }
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

function payload(json: string | null, raw: boolean): string {
  if (json === null || json === '') {
    return '（无）'
  }
  return raw ? json : pretty(json)
}

/**
 * 正文大小，取回详情时算一次。
 *
 * 先让人知道自己在打开多大的东西；也别每次重渲染都把接近 1 MiB 的字符串重新编码一遍 ——
 * 开着自动刷新时这一页每十秒就要重画一次。
 */
const payloadSizes = reactive<Record<string, { request: string, response: string }>>({})

function sizeLabel(json: string | null): string {
  if (json === null || json === '') {
    return '空'
  }
  const bytes = new TextEncoder().encode(json).length
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

// ------------------------------------------------------------------ 展示

function durationLabel(ms: number | null): string {
  if (ms === null) {
    return '—'
  }
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(2)} s`
}

function durationWidth(ms: number | null): string {
  return ms === null ? '0%' : `${Math.max(2, (ms / maxDuration.value) * 100)}%`
}

function downstreamName(id: string | null): string {
  if (!id) {
    return '—'
  }
  return gateway.value?.downstreams.find(item => item.id === id)?.name ?? id
}

/** trace_id 是一长串十六进制，整串铺开会把表格挤变形；全文在 title 和复制按钮里。 */
function shortTrace(traceId: string): string {
  return traceId.length > 12 ? `${traceId.slice(0, 8)}…` : traceId
}

function percent(value: number): string {
  return `${(value * 100).toFixed(1)}%`
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
    <div>
      <h1>调用记录</h1>
      <p class="small muted">每次 <code>tools/call</code> 留一条，最近的在前</p>
    </div>
    <div class="btn-row">
      <span v-if="loadedAt" class="small muted refresh-stamp">
        <AppIcon name="clock" :size="13" />
        更新于 {{ formatClock(loadedAt) }}
      </span>
      <select v-model.number="autoRefresh" class="control control-inline" aria-label="自动刷新间隔">
        <option v-for="option in AUTO_REFRESH_OPTIONS" :key="option.value" :value="option.value">
          {{ option.label }}
        </option>
      </select>
      <button class="btn" type="button" @click="manualRefresh">
        <AppIcon name="refresh" :size="14" /> 刷新
      </button>

      <button class="btn" type="button" :disabled="exporting" @click="exportExcel">
        <AppIcon name="download" :size="14" />
        {{ exporting ? '导出中…' : '导出 Excel' }}
      </button>

      <!-- 列配置。用原生 details 而不是自己写弹层：点外面收起、Esc 收起、键盘可达，全是白送的 -->
      <details class="col-menu">
        <summary class="btn">
          <AppIcon name="columns" :size="14" /> 列
          <span class="count">{{ visibleColumns.length }}</span>
        </summary>

        <div class="col-panel">
          <div class="col-panel-head">
            <span>显示哪些列</span>
            <button type="button" class="chip-btn ghost" @click="resetColumns">恢复默认</button>
          </div>

          <ul class="col-list">
            <li v-for="column in menuColumns" :key="column.key"
                :class="{ off: !isColumnVisible(column.key) }">
              <label class="check">
                <input type="checkbox" :checked="isColumnVisible(column.key)"
                       @change="toggleColumn(column.key)">
                <span class="col-label">{{ column.label }}</span>
              </label>
              <code v-if="column.extract" class="col-path">{{ column.key }}</code>
              <span class="col-actions">
                <button v-if="isColumnVisible(column.key)" type="button" class="icon-btn"
                        title="上移" aria-label="上移" @click="moveColumn(column.key, -1)">↑</button>
                <button v-if="isColumnVisible(column.key)" type="button" class="icon-btn"
                        title="下移" aria-label="下移" @click="moveColumn(column.key, 1)">↓</button>
                <button v-if="column.extract" type="button" class="icon-btn danger"
                        title="删除这一列" :aria-label="`删除 ${column.label}`"
                        @click="removeExtractColumn(column.key)">
                  <AppIcon name="close" :size="12" />
                </button>
              </span>
            </li>
          </ul>

          <form class="col-add" @submit.prevent="onAddColumn">
            <div class="col-panel-head">
              <span>加一列正文字段</span>
            </div>
            <div class="col-add-row">
              <select v-model="draft.source" class="control control-inline" aria-label="来源">
                <option value="request">入参</option>
                <option value="response">返回</option>
              </select>
              <input v-model="draft.pointer" class="control mono" aria-label="JSON Pointer 路径"
                     placeholder="/content/0/text">
              <input v-model="draft.label" class="control" aria-label="列名" placeholder="列名">
              <button class="btn btn-sm btn-primary" type="submit">添加</button>
            </div>
            <p class="hint">
              路径是 JSON Pointer：<code>/q</code>、<code>/content/0/text</code>；
              留空表示整个文档。最多 4 列，每格最长 200 字符 ——
              服务端只回抽到的值，正文本身不会进列表。
            </p>
            <p v-if="addColumnError" class="hint warn">{{ addColumnError }}</p>
          </form>
        </div>
      </details>
      <RouterLink class="btn" :to="`/gateways/${gatewayId}`">返回网关详情</RouterLink>
    </div>
  </div>

  <section class="card">
    <!-- 状态分面。计数不受 status 筛选影响，所以既能拿来切换，也能拿来算成功率 -->
    <div class="facet-bar">
      <div class="facets">
        <button type="button" class="facet" :class="{ active: filters.status === '' }"
                @click="selectStatus('')">
          全部 <span class="count">{{ totalCount }}</span>
        </button>
        <button v-for="status in STATUSES" :key="status" type="button"
                class="facet" :class="[`facet-${status}`, { active: filters.status === status }]"
                @click="selectStatus(status)">
          {{ status }} <span class="count">{{ result?.statusCounts[status] ?? 0 }}</span>
        </button>
      </div>

      <div v-if="successRate !== null" class="health" :class="{ bad: failureCount > 0 }">
        <span class="label">成功率</span>
        <strong>{{ percent(successRate) }}</strong>
        <span v-if="failureCount > 0" class="small">{{ failureCount }} 条未成功</span>
      </div>
    </div>

    <form class="card-body stack filters-form" @submit.prevent="applyFilters">
      <div class="range-row">
        <span class="range-label">时间范围</span>
        <button v-for="range in RANGES" :key="range.key" type="button" class="chip-btn"
                :class="{ active: filters.range === range.key }" @click="selectRange(range.key)">
          最近 {{ range.label }}
        </button>
        <button type="button" class="chip-btn" :class="{ active: customRangeOpen }"
                :aria-expanded="customRangeOpen" @click="customRangeOpen = !customRangeOpen">
          自定义…
        </button>
      </div>

      <div v-show="customRangeOpen" class="filters custom-range">
        <div class="field">
          <label for="f-from">开始时间（含）</label>
          <input id="f-from" v-model="fromInput" class="control" type="datetime-local">
        </div>
        <div class="field">
          <label for="f-to">结束时间（不含）</label>
          <input id="f-to" v-model="toInput" class="control" type="datetime-local">
        </div>
      </div>

      <div class="filters">
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
        <div class="field justify-end">
          <div class="btn-row">
            <button class="btn btn-primary" type="submit">筛选</button>
            <button class="btn btn-link" type="button" @click="reset">重置</button>
          </div>
        </div>
      </div>
    </form>
  </section>

  <section class="card">
    <div v-if="activeChips.length > 0" class="chip-bar">
      <span class="small muted">已筛选</span>
      <span v-for="chip in activeChips" :key="chip.key" class="chip">
        <span class="chip-key">{{ chip.label }}</span>
        <span class="chip-value mono">{{ chip.value }}</span>
        <button type="button" class="chip-x" :aria-label="`取消${chip.label}筛选`" @click="chip.clear()">
          <AppIcon name="close" :size="11" />
        </button>
      </span>
      <button type="button" class="chip-btn ghost" @click="reset">全部清除</button>
    </div>

    <TableSkeleton v-if="loading" :rows="6" />

    <div v-else-if="loadFailed" class="empty-state">
      <AppIcon name="warning" :size="28" />
      <div class="title">没能取到调用记录</div>
      <button class="btn" type="button" @click="load({ force: true })">重试</button>
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
        <p>这个网关有 {{ totalCount }} 条记录，只是都不在当前条件里。</p>
        <button class="btn" type="button" @click="reset">清空筛选</button>
      </template>
    </div>

    <template v-else>
      <div class="table-wrap">
        <table class="table records">
          <thead>
            <tr>
              <th class="expander"><span class="sr-only">展开</span></th>
              <th v-for="column in visibleColumns" :key="column.key"
                  :class="[cellClass(column), { num: column.key === 'durationMs' }]"
                  :title="column.extract ? `抽自${column.extract.source === 'request' ? '入参' : '返回'} ${column.extract.pointer || '整个文档'}` : undefined">
                {{ column.label }}
              </th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in result.items" :key="item.callId">
              <tr class="record"
                  :class="[`st-${item.status}`, {
                    open: openCallId === item.callId,
                    fresh: freshIds.has(item.callId)
                  }]"
                  @click="toggle(item.callId)">
                <td class="expander">
                  <button type="button" class="expander-btn"
                          :aria-expanded="openCallId === item.callId"
                          :aria-label="`展开 ${item.exposedToolName} 的调用详情`"
                          @click.stop="toggle(item.callId)">
                    <AppIcon name="chevron" :size="14" />
                  </button>
                </td>
                <!-- 抽取列的值是服务端按 JSON Pointer 抽的；其余列都来自摘要字段 -->
                <td v-for="column in visibleColumns" :key="column.key"
                    class="small" :class="[cellClass(column), { num: column.key === 'durationMs' }]">
                  <ExtractedCell v-if="column.extract" :value="item.extracted?.[column.key]" />

                  <template v-else-if="column.key === 'startedAt'">
                    <span class="mono"
                          :title="`${formatExact(item.startedAt)}（${formatRelative(item.startedAt)}）`">
                      {{ formatStamp(item.startedAt) }}
                    </span>
                  </template>

                  <template v-else-if="column.key === 'exposedToolName'">
                    <button class="linkish mono tool-name" type="button" title="只看这个工具的调用"
                            @click.stop="filterByTool(item.exposedToolName)">
                      {{ item.exposedToolName }}
                    </button>
                  </template>

                  <template v-else-if="column.key === 'downstream'">
                    <!-- 目标为空 = 未知工具或停用工具的调用，这类记录同样不能缺 -->
                    <span v-if="!item.downstreamMcpId" class="warn">（无路由目标）</span>
                    <span v-else>{{ downstreamName(item.downstreamMcpId) }}</span>
                  </template>

                  <template v-else-if="column.key === 'status'">
                    <CallStatusBadge :status="item.status" />
                  </template>

                  <template v-else-if="column.key === 'durationMs'">
                    <span class="dur-value">{{ durationLabel(item.durationMs) }}</span>
                    <span v-if="item.durationMs !== null" class="dur-bar" aria-hidden="true">
                      <span class="dur-fill" :class="{ slow: item.durationMs >= 3000 }"
                            :style="{ width: durationWidth(item.durationMs) }"></span>
                    </span>
                  </template>

                  <template v-else-if="column.key === 'errorCode'">
                    <span v-if="item.errorCode" class="mono err-code">{{ item.errorCode }}</span>
                    <span v-else class="muted">—</span>
                  </template>

                  <template v-else-if="column.key === 'errorMessage'">
                    <span v-if="item.errorMessage" class="clamp" :title="item.errorMessage">
                      {{ item.errorMessage }}
                    </span>
                    <span v-else class="muted">—</span>
                  </template>

                  <template v-else-if="column.key === 'traceId'">
                    <button class="linkish mono" type="button" title="按这条链路筛选"
                            @click.stop="filterByTrace(item.traceId)">{{ shortTrace(item.traceId) }}</button>
                    <CopyButton :value="item.traceId" label="复制 trace_id" />
                  </template>

                  <template v-else-if="column.key === 'callId'">
                    <span class="mono" :title="item.callId">{{ shortTrace(item.callId) }}</span>
                    <CopyButton :value="item.callId" label="复制 call_id" />
                  </template>

                  <template v-else-if="column.key === 'finishedAt'">
                    <span class="mono muted">{{ item.finishedAt ? formatStamp(item.finishedAt) : '—' }}</span>
                  </template>

                  <template v-else-if="column.key === 'originalToolName'">
                    <span class="mono">{{ item.originalToolName ?? '—' }}</span>
                  </template>
                </td>
              </tr>

              <tr v-if="openCallId === item.callId" class="detail-row">
                <td :colspan="visibleColumns.length + 1">
                  <div v-if="detailLoading === item.callId" class="detail-panel muted">加载中…</div>

                  <div v-else-if="details[item.callId]" class="detail-panel">
                    <div class="detail-meta">
                      <div>
                        <span class="label">call_id</span>
                        <span class="value mono small">
                          {{ details[item.callId].callId }}
                          <CopyButton :value="details[item.callId].callId" label="复制 call_id" />
                        </span>
                      </div>
                      <div>
                        <span class="label">子 MCP / 原工具名</span>
                        <span class="value mono small">
                          {{ downstreamName(details[item.callId].downstreamMcpId) }}
                          / {{ details[item.callId].originalToolName ?? '—' }}
                        </span>
                      </div>
                      <div>
                        <span class="label">开始 → 结束</span>
                        <span class="value small">
                          {{ formatExact(details[item.callId].startedAt) }}
                          →
                          {{ details[item.callId].finishedAt
                              ? formatExact(details[item.callId].finishedAt) : '—' }}
                        </span>
                      </div>
                      <div>
                        <span class="label">耗时</span>
                        <span class="value small">{{ durationLabel(details[item.callId].durationMs) }}</span>
                      </div>
                      <div v-if="details[item.callId].errorMessage" class="span-all">
                        <span class="label">错误摘要（已脱敏）</span>
                        <span class="value small">{{ details[item.callId].errorMessage }}</span>
                      </div>
                    </div>

                    <div class="payloads">
                      <section class="payload">
                        <header>
                          <span class="label">入参</span>
                          <span class="size">{{ payloadSizes[item.callId]?.request }}</span>
                          <span class="payload-actions">
                            <button type="button" class="chip-btn tiny"
                                    @click="showRaw.request = !showRaw.request">
                              {{ showRaw.request ? '格式化' : '原文' }}
                            </button>
                            <CopyButton :value="details[item.callId].requestJson ?? ''" label="复制入参" />
                          </span>
                        </header>
                        <pre class="json-block">{{ payload(details[item.callId].requestJson, showRaw.request) }}</pre>
                      </section>

                      <section class="payload">
                        <header>
                          <span class="label">返回</span>
                          <span class="size">{{ payloadSizes[item.callId]?.response }}</span>
                          <span class="payload-actions">
                            <button type="button" class="chip-btn tiny"
                                    @click="showRaw.response = !showRaw.response">
                              {{ showRaw.response ? '格式化' : '原文' }}
                            </button>
                            <CopyButton :value="details[item.callId].responseJson ?? ''" label="复制返回" />
                          </span>
                        </header>
                        <pre class="json-block">{{ payload(details[item.callId].responseJson, showRaw.response) }}</pre>
                      </section>
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

        <div class="pager-controls">
          <label class="small muted page-size">
            每页
            <select class="control control-inline" :value="filters.size" @change="changeSize">
              <option v-for="size in PAGE_SIZES" :key="size" :value="size">{{ size }}</option>
            </select>
          </label>

          <nav class="pager-nav" aria-label="分页">
            <button class="btn btn-sm pager-prev" type="button" :disabled="!hasPrev"
                    @click="goto(result.page - 1)">上一页</button>

            <template v-for="(item, index) in pageItems" :key="index">
              <span v-if="item === 'gap'" class="page-gap" aria-hidden="true">…</span>
              <button v-else type="button" class="page-btn"
                      :class="{ current: item === result.page }"
                      :aria-current="item === result.page ? 'page' : undefined"
                      :aria-label="`第 ${item + 1} 页`"
                      @click="goto(item)">{{ item + 1 }}</button>
            </template>

            <button class="btn btn-sm pager-next" type="button" :disabled="!hasNext"
                    @click="goto(result.page + 1)">下一页</button>
          </nav>

          <!-- 页码被省略号截断之后才给跳转框：页数少的时候点数字就够了 -->
          <form v-if="showJump" class="pager-jump" @submit.prevent="onJump">
            <label class="small muted">
              跳至
              <input v-model="jumpTo" class="control control-inline" type="number"
                     min="1" :max="totalPages" inputmode="numeric" aria-label="跳至第几页">
              页 / 共 {{ totalPages }} 页
            </label>
            <button class="btn btn-sm" type="submit">跳转</button>
          </form>
        </div>
      </div>
    </template>
  </section>

  <p class="small muted">
    记录里不含任何 header、网关令牌或子 MCP 凭证（需求 FR-06.3）；
    但<strong>入参和返回是子 MCP 的原始内容</strong>，按部署要求保护。
  </p>
</template>
