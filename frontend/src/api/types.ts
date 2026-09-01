/*
 * 管理 API 的响应模型，与服务端的 Java record 一一对应。
 *
 * 这些类型只是服务端 DTO 的镜像，不在前端另造一套视图模型 ——
 * effectiveDescription、status、遮罩后的 headers 都由服务端算好，前端直接渲染。
 */

/** 需求 §8 的统一响应结构。三个字段恒定存在。 */
export interface ApiResponse<T> {
  success: boolean
  data: T | null
  error: ApiErrorBody | null
}

export interface ApiErrorBody {
  /** 需求 §11 的稳定错误码。只增不改，可以安全地拿来做分支判断。 */
  code: string
  /** 已脱敏的可读消息，不含堆栈、header 或凭证。 */
  message: string
}

/** 网关派生状态（需求 FR-01）。不落库，服务端每次实时计算。 */
export type GatewayStatus = 'EMPTY' | 'READY' | 'DEGRADED' | 'UNAVAILABLE'

/** 子 MCP 最近一次同步的结果。 */
export type SyncStatus = 'PENDING' | 'SUCCESS' | 'FAILED'

/** 需求 10.1 列表页所需字段。时间戳是 ISO-8601 字符串。 */
export interface GatewaySummary {
  id: string
  name: string
  slug: string
  description: string | null
  status: GatewayStatus
  downstreamCount: number
  toolCount: number
  mcpUrl: string
  createdAt: string
  updatedAt: string
}

/** 需求 FR-03：工具快照对外的视图。 */
export interface GatewayTool {
  id: string
  downstreamMcpId: string
  exposedName: string
  originalName: string
  originalDescription: string | null
  /** 操作人写的覆盖描述；为空表示没设置 */
  customDescription: string | null
  /** 需求 6.5.5：自定义描述非空时用它，否则回退原始描述。由服务端算好 */
  effectiveDescription: string | null
  enabled: boolean
  lastSyncedAt: string
}

/**
 * 子 MCP 的对外视图。
 *
 * headers **只含名称和遮罩值**（需求 12.4），真实凭证永远不出现在任何响应里。
 * 因此这个字段只能用于展示，绝不能原样提交回服务端 —— 那会把真凭证覆盖成 `******`。
 */
export interface DownstreamMcp {
  id: string
  name: string
  type: string
  url: string
  headers: Record<string, string>
  syncStatus: SyncStatus
  /** 需求 6.4.7：含义是"快照有多新"，不是"上次尝试是什么时候"。同步失败时停在上一次成功的时刻 */
  lastSyncAt: string | null
  lastSyncError: string | null
  toolCount: number
  tools: GatewayTool[]
}

/** 需求 10.2 网关详情页所需字段。 */
export interface GatewayDetail {
  id: string
  name: string
  slug: string
  description: string | null
  status: GatewayStatus
  mcpUrl: string
  downstreams: DownstreamMcp[]
  createdAt: string
  updatedAt: string
}

/** 创建网关的响应。accessToken 是明文，且只在这一次响应里出现（需求 FR-05.3）。 */
export interface CreatedGateway {
  gateway: GatewayDetail
  accessToken: string
}

export interface CreateGatewayRequest {
  name: string
  slug: string
  description: string | null
}

export type UpdateGatewayRequest = CreateGatewayRequest

/**
 * 一次"测试并同步"的结果。
 *
 * 即使同步失败 HTTP 也是 200 —— 请求本身处理成功了，失败的是与下游的交互。
 * 所以调用方必须看 succeeded，不能只看 HTTP 状态。
 */
export interface SyncResult {
  downstreamId: string
  downstreamName: string
  succeeded: boolean
  added: number
  updated: number
  unchanged: number
  removed: number
  errorCode: string | null
  errorMessage: string | null
}

/**
 * 导入结果。
 *
 * 需求 6.4.2 要求新增后立即同步，需求 6.2.9 又要求单个子 MCP 不可用不影响其他 ——
 * 所以配置一定全部落库（gateway 反映的就是落库后的状态），同步则逐个成败。
 */
export interface ImportResult {
  gateway: GatewayDetail
  syncResults: SyncResult[]
}

/**
 * 编辑子 MCP。
 *
 * headers 的三态是这个接口最容易用错的地方：
 * - 字段**不出现** → 保持原有凭证不变
 * - 空对象 `{}`   → 清空凭证
 * - 非空对象       → 整体替换
 *
 * 页面上显示的是遮罩值，所以默认必须走第一种。
 */
export interface UpdateDownstreamRequest {
  name: string
  url: string
  headers?: Record<string, string>
}

/**
 * 修改工具（PATCH 语义，两个字段都可省略表示不改）。
 *
 * customDescription 同样是三态：
 * - 字段**不出现** → 不改动
 * - `null` 或空白  → 清除，回退到下游的原始描述
 * - 非空字符串     → 覆盖
 */
export interface UpdateToolRequest {
  enabled?: boolean
  customDescription?: string | null
}

/** 需求 FR-05：可复制的 Agent 接入 JSON。令牌位置是占位符，服务端拿不回明文。 */
export interface AgentConfig {
  mcpServers: Record<string, { type: string, url: string, headers: Record<string, string> }>
}

/** 令牌轮换。明文只在这里出现一次，旧令牌立即失效。 */
export interface RotatedToken {
  accessToken: string
  mcpUrl: string
}

// ---------------------------------------------------------------- 调用记录

/** 调用记录的终态。STARTED 是进行中，或进程异常退出遗留的残留。 */
export type CallStatus = 'STARTED' | 'SUCCESS' | 'ERROR' | 'TIMEOUT'

/**
 * 列表里的一行（需求 FR-06.5）。
 *
 * 刻意不含入参和返回正文 —— 那两个字段各可能接近 1 MiB，装的是知识库内容。
 * 要看正文得按 callId 取单条。
 */
export interface CallRecordSummary {
  callId: string
  traceId: string
  /** 未知工具或停用工具的调用无法确定目标，为空 */
  downstreamMcpId: string | null
  exposedToolName: string
  originalToolName: string | null
  status: CallStatus
  errorCode: string | null
  /** 已脱敏的错误摘要，与返回给 Agent 的是同一份文案 */
  errorMessage: string | null
  startedAt: string
  finishedAt: string | null
  durationMs: number | null
}

/** 单条记录的完整内容。requestJson / responseJson 是原始 JSON 文本，可能很大。 */
export interface CallRecordDetail extends CallRecordSummary {
  gatewayId: string
  requestJson: string | null
  responseJson: string | null
}

export interface CallRecordPage {
  items: CallRecordSummary[]
  page: number
  size: number
  total: number
  /** 各终态的条数。**不套用 status 筛选**，所以可以拿它做分面切换 */
  statusCounts: Partial<Record<CallStatus, number>>
}

/** 列表筛选条件。全部可省略。 */
export interface CallRecordFilters {
  downstreamMcpId?: string
  toolName?: string
  status?: CallStatus | ''
  traceId?: string
  /** ISO-8601 instant，含 */
  from?: string
  /** ISO-8601 instant，不含 */
  to?: string
  page?: number
  size?: number
}
