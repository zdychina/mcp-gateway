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

/** 批量导入时的多行摘要。 */
export function describeSyncResults(results: SyncResult[]): string {
  return results.map(describeSyncResult).join('\n')
}
