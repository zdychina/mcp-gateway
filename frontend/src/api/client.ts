import type { ApiResponse } from './types'

/**
 * 带稳定错误码的请求失败。
 *
 * code 取自服务端的 §11 错误码枚举，调用方可以据此分支；无法解析响应时用
 * 本地生成的占位码，同样保证 code 一定有值。
 */
export class ApiError extends Error {
  constructor(readonly code: string, message: string) {
    super(message)
    this.name = 'ApiError'
  }

  /** 给用户看的一行文案：错误码 + 服务端已脱敏的说明。 */
  get display(): string {
    return this.message ? `${this.code}：${this.message}` : this.code
  }
}

/**
 * 统一处理 {success, data, error} 响应结构。
 *
 * 注意 Content-Type 恒为 application/json：管理 API 只接受这一种类型，
 * 而这正是当前防跨站请求的两道保护之一（另一道是完全不配置 CORS）。
 * 改成表单编码会打掉它 —— 见 SECURITY.md。
 */
async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const init: RequestInit = { method, headers }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }

  const response = await fetch(url, init)

  let payload: ApiResponse<T> | null = null
  try {
    payload = (await response.json()) as ApiResponse<T>
  }
  catch {
    throw new ApiError('INVALID_RESPONSE', `服务端返回了无法解析的响应（HTTP ${response.status}）`)
  }

  if (!response.ok || !payload || payload.success !== true) {
    const error = payload?.error
    throw new ApiError(error?.code ?? `HTTP_${response.status}`, error?.message ?? '')
  }
  return payload.data as T
}

export const http = {
  get: <T>(url: string) => request<T>('GET', url),
  post: <T>(url: string, body?: unknown) => request<T>('POST', url, body),
  put: <T>(url: string, body: unknown) => request<T>('PUT', url, body),
  patch: <T>(url: string, body: unknown) => request<T>('PATCH', url, body),
  delete: <T>(url: string) => request<T>('DELETE', url)
}
