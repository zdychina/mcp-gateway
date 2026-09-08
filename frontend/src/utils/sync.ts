import type { SyncResult } from '../api/types'

/**
 * 一次同步结果的可读摘要。
 *
 * 成功和失败都要说清楚：失败时必须点明"上一次成功的快照还在"，
 * 否则操作人会以为工具已经没了（需求 6.4.7）。
 */
export function describeSyncResult(result: SyncResult): string {
  if (result.succeeded) {
    return `${result.downstreamName}：新增 ${result.added}，更新 ${result.updated}，`
      + `未变 ${result.unchanged}，移除 ${result.removed}`
  }
  return `${result.downstreamName}：${result.errorCode ?? '同步失败'}`
    + `${result.errorMessage ? '　' + result.errorMessage : ''}`
}

/**
 * 工具集变了之后统一的一句提示。
 *
 * 网关这边是立即生效的（tools/list 每次现读数据库），但它不发
 * notifications/tools/list_changed —— 无状态的 streamable-http 下没有可推的长连接。
 * 而多数客户端只在建立连接时拉一次工具列表，所以对使用者来说"生效"意味着重连。
 * 不说这一句，操作人会以为改完就完了。
 */
export const AGENT_RELIST_HINT
  = '已连接的 Agent 需要重新拉取工具列表（多数客户端要重连）才能看到变化。'

/** 批量导入时的多行摘要。 */
export function describeSyncResults(results: SyncResult[]): string {
  return results.map(describeSyncResult).join('\n')
}
