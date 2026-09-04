import { computed, ref, watch } from 'vue'
import type { Ref } from 'vue'

/*
 * 调用记录列表的列配置（需求 FR-06.5）。
 *
 * 两类列：
 * - 内置列，来自摘要字段，开关和排序而已；
 * - 抽取列，按 JSON Pointer 从入参/返回里抽一个值，键就是发给服务端的 extract 参数。
 *
 * 配置按网关存：抽取路径是跟着工具的参数结构走的，`/q` 在这个网关有意义，
 * 换个网关很可能什么都抽不到。内置列的开关顺带一起存，省得再造一层"全局偏好"。
 */

export interface ExtractSpec {
  source: 'request' | 'response'
  /** JSON Pointer，空串表示整个文档 */
  pointer: string
}

export interface ColumnDef {
  /** 内置列是字段名；抽取列是 `request:/q` 这种，同时也是发给服务端的 extract 值 */
  key: string
  label: string
  /** 只有抽取列有 */
  extract?: ExtractSpec
}

/** 内置列。顺序是"默认布局"之外的候选顺序，实际顺序由 layout 决定。 */
export const BUILT_IN_COLUMNS: ColumnDef[] = [
  { key: 'startedAt', label: '开始时间' },
  { key: 'exposedToolName', label: '聚合工具' },
  { key: 'downstream', label: '子 MCP' },
  { key: 'originalToolName', label: '原工具名' },
  { key: 'status', label: '状态' },
  { key: 'durationMs', label: '耗时' },
  { key: 'errorCode', label: '错误码' },
  { key: 'errorMessage', label: '错误摘要' },
  { key: 'finishedAt', label: '结束时间' },
  { key: 'traceId', label: 'trace_id' },
  { key: 'callId', label: 'call_id' }
]

/** 默认可见的列，和这个页面原来的样子一致。 */
export const DEFAULT_LAYOUT = [
  'startedAt', 'exposedToolName', 'downstream', 'status', 'durationMs',
  'errorCode', 'errorMessage', 'traceId'
]

/** 服务端的抽取列上限（CallPayloadExtractor.MAX_SPECS）。前端先拦一道，别等 400 回来才说。 */
export const MAX_EXTRACT_COLUMNS = 4

const STORAGE_PREFIX = 'mcp-gateway.call-columns.'

const STORAGE_VERSION = 'v1'

interface StoredConfig {
  layout: string[]
  custom: ColumnDef[]
}

/**
 * localStorage 在隐私模式、被策略禁用、或者 iframe 里会直接抛异常 ——
 * 列配置丢了顶多回到默认布局，不该让整个页面白屏。
 */
function readStorage(key: string): StoredConfig | null {
  try {
    const raw = window.localStorage.getItem(key)
    return raw ? JSON.parse(raw) as StoredConfig : null
  }
  catch {
    return null
  }
}

function writeStorage(key: string, value: StoredConfig): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(value))
  }
  catch {
    // 存不下就算了，本次会话内配置仍然有效
  }
}

/** 抽取列的键就是发给服务端的 extract 值，两边必须用同一套拼法。 */
export function extractKey(spec: ExtractSpec): string {
  return `${spec.source}:${spec.pointer}`
}

/**
 * 校验一个 JSON Pointer。
 *
 * 只挡住最常见的手误（把点号路径当成 pointer 写），别的交给服务端 —— 前端复刻一套
 * RFC 6901 的解析，迟早会和服务端对不上。
 */
export function validatePointer(pointer: string): string | null {
  if (pointer !== '' && !pointer.startsWith('/')) {
    return '路径要以 / 开头，比如 /q 或 /content/0/text；留空表示整个文档'
  }
  if (pointer.length > 200) {
    return '路径太长了'
  }
  return null
}

export function useCallColumns(gatewayId: Ref<string>) {
  const layout = ref<string[]>([...DEFAULT_LAYOUT])
  const custom = ref<ColumnDef[]>([])

  const storageKey = computed(() => `${STORAGE_PREFIX}${gatewayId.value}.${STORAGE_VERSION}`)

  /** 存进去过的列可能在新版本里没了（改名、删列），读回来时一律按现有定义过滤。 */
  function load(): void {
    const stored = readStorage(storageKey.value)
    if (!stored) {
      layout.value = [...DEFAULT_LAYOUT]
      custom.value = []
      return
    }
    custom.value = (stored.custom ?? []).filter(column => column.key && column.extract)
    const known = new Set([
      ...BUILT_IN_COLUMNS.map(column => column.key),
      ...custom.value.map(column => column.key)
    ])
    const restored = (stored.layout ?? []).filter(key => known.has(key))
    layout.value = restored.length > 0 ? restored : [...DEFAULT_LAYOUT]
  }

  watch(gatewayId, load, { immediate: true })

  function persist(): void {
    writeStorage(storageKey.value, { layout: layout.value, custom: custom.value })
  }

  const allColumns = computed(() => [...BUILT_IN_COLUMNS, ...custom.value])

  /** 可见列，按 layout 的顺序。 */
  const visibleColumns = computed(() =>
    layout.value
      .map(key => allColumns.value.find(column => column.key === key))
      .filter((column): column is ColumnDef => column !== undefined))

  /** 菜单里的顺序：先是可见的（可上下移动），再是关掉的。 */
  const menuColumns = computed(() => [
    ...visibleColumns.value,
    ...allColumns.value.filter(column => !layout.value.includes(column.key))
  ])

  function isVisible(key: string): boolean {
    return layout.value.includes(key)
  }

  /** 发给服务端的 extract 参数：只包含**可见**的抽取列，关掉的列不该继续查库。 */
  const extractSpecs = computed(() =>
    visibleColumns.value.filter(column => column.extract).map(column => column.key))

  const visibleExtractCount = computed(() => extractSpecs.value.length)

  function toggle(key: string): void {
    if (isVisible(key)) {
      // 至少留一列，否则表格会变成一堆看不出是什么的空行
      if (layout.value.length > 1) {
        layout.value = layout.value.filter(item => item !== key)
      }
    }
    else {
      layout.value = [...layout.value, key]
    }
    persist()
  }

  function move(key: string, delta: number): void {
    const index = layout.value.indexOf(key)
    const target = index + delta
    if (index < 0 || target < 0 || target >= layout.value.length) {
      return
    }
    const next = [...layout.value]
    next.splice(index, 1)
    next.splice(target, 0, key)
    layout.value = next
    persist()
  }

  /**
   * 加一个抽取列。
   *
   * @returns 出错原因，成功返回 null
   */
  function addExtractColumn(spec: ExtractSpec, label: string): string | null {
    const pointerError = validatePointer(spec.pointer)
    if (pointerError) {
      return pointerError
    }
    const key = extractKey(spec)
    if (allColumns.value.some(column => column.key === key)) {
      // 已经有了就直接打开它，比报一句"重复了"有用
      if (!isVisible(key)) {
        toggle(key)
      }
      return null
    }
    if (visibleExtractCount.value >= MAX_EXTRACT_COLUMNS) {
      return `最多同时显示 ${MAX_EXTRACT_COLUMNS} 个正文列，先关掉一个再加`
    }
    custom.value = [...custom.value, { key, label: label.trim() || key, extract: spec }]
    layout.value = [...layout.value, key]
    persist()
    return null
  }

  function removeExtractColumn(key: string): void {
    custom.value = custom.value.filter(column => column.key !== key)
    layout.value = layout.value.filter(item => item !== key)
    persist()
  }

  function reset(): void {
    layout.value = [...DEFAULT_LAYOUT]
    custom.value = []
    persist()
  }

  return {
    layout,
    custom,
    allColumns,
    visibleColumns,
    menuColumns,
    extractSpecs,
    visibleExtractCount,
    isVisible,
    toggle,
    move,
    addExtractColumn,
    removeExtractColumn,
    reset
  }
}
