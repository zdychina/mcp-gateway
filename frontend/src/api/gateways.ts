import { http } from './client'
import type {
  AgentConfig,
  CallRecordDetail,
  CallRecordFilters,
  CallRecordPage,
  CreateGatewayRequest,
  CreatedGateway,
  GatewayDetail,
  GatewaySummary,
  GatewayTool,
  ImportResult,
  RotatedToken,
  SyncResult,
  UpdateDownstreamRequest,
  UpdateGatewayRequest,
  UpdateToolRequest
} from './types'

const base = (gatewayId: string) => `/api/gateways/${encodeURIComponent(gatewayId)}`

/** 网关管理 API（需求 §8）。路径与 Thymeleaf 时代完全一致，服务端未做任何改动。 */
export const gatewayApi = {
  list: () => http.get<GatewaySummary[]>('/api/gateways'),

  detail: (id: string) => http.get<GatewayDetail>(base(id)),

  /** 响应里含明文访问令牌，且仅此一次（需求 FR-05.3）。 */
  create: (request: CreateGatewayRequest) =>
    http.post<CreatedGateway>('/api/gateways', request),

  update: (id: string, request: UpdateGatewayRequest) =>
    http.put<GatewayDetail>(base(id), request),

  /** 需求 6.1.7：级联删除子 MCP、工具快照和全部调用记录。 */
  remove: (id: string) => http.delete<void>(base(id)),

  agentConfig: (id: string) => http.get<AgentConfig>(`${base(id)}/agent-config`),

  /** 需求 FR-05.3：旧令牌立即失效，没有过渡期。新令牌只返回一次。 */
  rotateToken: (id: string) => http.post<RotatedToken>(`${base(id)}/access-token/rotate`)
}

/** 子 MCP 配置与同步 API（需求 FR-02）。 */
export const downstreamApi = {
  /**
   * 粘贴 mcpServers JSON 导入，服务端按需求 6.4.2 立即同步一次。
   *
   * body 是操作人粘贴的原始 JSON 对象，原样转发 —— 服务端要靠里面有没有
   * command/args/env 来判定 stdio 配置并报 UNSUPPORTED_TRANSPORT，
   * 前端不能先过一道自己的模型把未知字段吃掉。
   */
  import: (gatewayId: string, body: unknown) =>
    http.post<ImportResult>(`${base(gatewayId)}/mcp-servers/import`, body),

  /** 需求 6.2.8：人工触发的"测试并同步"。失败也返回 200，细节在 body 里。 */
  sync: (gatewayId: string, serverId: string) =>
    http.post<SyncResult>(`${base(gatewayId)}/mcp-servers/${encodeURIComponent(serverId)}/sync`),

  /** headers 字段省略即保持原有凭证，见 UpdateDownstreamRequest 的说明。 */
  update: (gatewayId: string, serverId: string, request: UpdateDownstreamRequest) =>
    http.put<GatewayDetail>(`${base(gatewayId)}/mcp-servers/${encodeURIComponent(serverId)}`, request),

  /** 需求 6.2.10：工具快照由外键级联移除，立即从 tools/list 消失。 */
  remove: (gatewayId: string, serverId: string) =>
    http.delete<GatewayDetail>(`${base(gatewayId)}/mcp-servers/${encodeURIComponent(serverId)}`)
}

/** 工具启停与描述覆盖（需求 6.5）。只有 PATCH —— 工具行由同步流程增删，操作人不能手工改。 */
export const toolApi = {
  update: (gatewayId: string, toolId: string, request: UpdateToolRequest) =>
    http.patch<GatewayTool>(`${base(gatewayId)}/tools/${encodeURIComponent(toolId)}`, request)
}

/**
 * 调用记录查询（需求 FR-06.5）。
 *
 * 只有读接口：记录是打点产生的事实，不接受人工增删改。
 */
export const callRecordApi = {
  search: (gatewayId: string, filters: CallRecordFilters = {}) => {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(filters)) {
      // 数组（extract）重复传，不拼成一个逗号串 —— 值里本来就可能有逗号
      if (Array.isArray(value)) {
        for (const item of value) {
          params.append(key, item)
        }
        continue
      }
      // 空串和 undefined 都不发 —— 服务端把空串当成"没筛"，但少发一个参数更干净
      if (value !== undefined && value !== null && value !== '') {
        params.set(key, String(value))
      }
    }
    const query = params.toString()
    return http.get<CallRecordPage>(`${base(gatewayId)}/call-records${query ? '?' + query : ''}`)
  },

  /**
   * 导出当前筛选结果为 .xlsx。
   *
   * 列由调用方给：columns 是有序的列名（含 `request:/q` 这种抽取列），labels 是对应的表头。
   * 服务端最多导 5000 行，是否截断看 X-Export-Truncated 响应头。
   */
  exportFile: (gatewayId: string, filters: CallRecordFilters = {},
    columns: string[] = [], labels: string[] = []) => {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(filters)) {
      // 导出接口没有 extract / page / size —— 列本身就带着抽取路径，行数由服务端上限决定
      if (key === 'extract' || key === 'page' || key === 'size') {
        continue
      }
      if (value !== undefined && value !== null && value !== '') {
        params.set(key, String(value))
      }
    }
    for (const column of columns) {
      params.append('columns', column)
    }
    for (const label of labels) {
      params.append('labels', label)
    }
    return http.download(`${base(gatewayId)}/call-records/export?${params.toString()}`)
  },

  /** 取单条的完整内容，含入参和返回正文。 */
  detail: (gatewayId: string, callId: string) =>
    http.get<CallRecordDetail>(`${base(gatewayId)}/call-records/${encodeURIComponent(callId)}`)
}
