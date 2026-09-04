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

/** 服务端下发的 CSRF 令牌 Cookie，以及回传它的请求头。 */
const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'

/**
 * 会话失效时的回调。
 *
 * client 不直接 import router：那会让"发一个请求"和"页面往哪跳"互相依赖，
 * 也让这个模块没法单独测。由 main.ts 在启动时把两者接起来。
 */
let onUnauthorized: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler
}

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'))
  return match ? decodeURIComponent(match[1]) : null
}

/**
 * 统一处理 {success, data, error} 响应结构。
 *
 * 两个请求头都不是可选项：
 *
 * - Content-Type 恒为 application/json：管理 API 只接受这一种类型，改成表单编码
 *   会打掉 SECURITY.md 里那道跨站保护。
 * - 写请求带上 X-XSRF-TOKEN：管理端有了会话 Cookie 之后就有了 CSRF 面，
 *   服务端会校验这个头。GET 不带 —— 安全方法本来就不在 CSRF 保护范围内。
 */
async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const init: RequestInit = { method, headers }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  if (method !== 'GET' && method !== 'HEAD') {
    const csrfToken = readCookie(CSRF_COOKIE)
    if (csrfToken) {
      headers[CSRF_HEADER] = csrfToken
    }
  }

  const response = await fetch(url, init)

  /*
   * 会话过期或未登录。认证接口自己会返回 401（口令错），那是登录页要显示的错误，
   * 不能触发"跳去登录页"的处理 —— 否则在登录页上输错口令会把页面自己刷掉。
   *
   * 注意这只是体验：真正的门在服务端，前端这一跳挡不住任何人。
   */
  if (response.status === 401 && !url.startsWith('/api/auth/')) {
    onUnauthorized?.()
  }

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

/**
 * 下载一个文件。
 *
 * 不能走 request()：那个函数只认 {success,data,error} 的 JSON 信封，而这里成功时拿到的是
 * 二进制流。但**失败时服务端仍然回信封** —— 参数校验发生在开始写文件之前 ——
 * 所以要按响应的内容类型分流，否则一个 400 会被当成一份 0 字节的 Excel 存到磁盘上。
 */
async function download(url: string): Promise<{ blob: Blob, headers: Headers }> {
  const response = await fetch(url, { headers: { Accept: '*/*' } })

  if (response.status === 401 && !url.startsWith('/api/auth/')) {
    onUnauthorized?.()
  }

  const contentType = response.headers.get('Content-Type') ?? ''
  if (!response.ok || contentType.includes('application/json')) {
    let payload: ApiResponse<unknown> | null = null
    try {
      payload = (await response.json()) as ApiResponse<unknown>
    }
    catch {
      throw new ApiError('INVALID_RESPONSE', `服务端返回了无法解析的响应（HTTP ${response.status}）`)
    }
    throw new ApiError(payload?.error?.code ?? `HTTP_${response.status}`,
      payload?.error?.message ?? '')
  }

  return { blob: await response.blob(), headers: response.headers }
}

export const http = {
  get: <T>(url: string) => request<T>('GET', url),
  download,
  post: <T>(url: string, body?: unknown) => request<T>('POST', url, body),
  put: <T>(url: string, body: unknown) => request<T>('PUT', url, body),
  patch: <T>(url: string, body: unknown) => request<T>('PATCH', url, body),
  delete: <T>(url: string) => request<T>('DELETE', url)
}
