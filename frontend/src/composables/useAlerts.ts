import { reactive } from 'vue'

export type AlertKind = 'success' | 'danger' | 'warning'

export interface AlertItem {
  id: number
  kind: AlertKind
  title: string
  detail?: string
}

/*
 * 全局提示栈。
 *
 * Thymeleaf 版本这里有个实打实的缺陷：显示提示后 600ms 就 location.reload()，
 * 绿色提示框和同步结果摘要一起被整页刷新冲掉，用户根本来不及读。
 * 改成 Vue 之后不再整页刷新，提示自然留得住 —— 成功提示 6 秒后自动消失，
 * 警告和错误留到用户自己关掉。
 */
const items = reactive<AlertItem[]>([])
let sequence = 0

const AUTO_DISMISS_MS = 6000

export function useAlerts() {
  function dismiss(id: number): void {
    const index = items.findIndex(item => item.id === id)
    if (index >= 0) {
      items.splice(index, 1)
    }
  }

  function push(kind: AlertKind, title: string, detail?: string): number {
    const id = ++sequence
    items.unshift({ id, kind, title, detail })
    if (kind === 'success') {
      window.setTimeout(() => dismiss(id), AUTO_DISMISS_MS)
    }
    return id
  }

  return {
    items,
    dismiss,
    push,
    success: (title: string, detail?: string) => push('success', title, detail),
    warning: (title: string, detail?: string) => push('warning', title, detail),
    error: (title: string, detail?: string) => push('danger', title, detail),
    clear: () => items.splice(0, items.length)
  }
}
