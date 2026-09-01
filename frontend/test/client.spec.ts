import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, http } from '../src/api/client'

function respondWith(body: unknown, status = 200): void {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body
  })))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('统一响应结构的处理', () => {
  it('success=true 时取出 data', async () => {
    respondWith({ success: true, data: [{ id: 'g1' }], error: null })

    await expect(http.get('/api/gateways')).resolves.toEqual([{ id: 'g1' }])
  })

  it('success=false 时抛出带稳定错误码的异常', async () => {
    respondWith({ success: false, data: null, error: { code: 'DUPLICATE_GATEWAY_SLUG', message: 'slug already in use: kb' } }, 409)

    await expect(http.post('/api/gateways', {})).rejects.toSatisfy((error: unknown) =>
      error instanceof ApiError
      && error.code === 'DUPLICATE_GATEWAY_SLUG'
      && error.display === 'DUPLICATE_GATEWAY_SLUG：slug already in use: kb')
  })

  it('HTTP 成功但 success=false 同样算失败 —— 同步接口就是这个形态', async () => {
    respondWith({ success: false, data: null, error: { code: 'INTERNAL_ERROR', message: 'internal error' } }, 200)

    await expect(http.get('/api/gateways')).rejects.toBeInstanceOf(ApiError)
  })

  it('响应不是 JSON 时给出可读错误，而不是抛 SyntaxError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => { throw new SyntaxError('Unexpected token <') }
    })))

    await expect(http.get('/api/gateways')).rejects.toSatisfy((error: unknown) =>
      error instanceof ApiError && error.code === 'INVALID_RESPONSE')
  })
})

describe('跨站保护相关的请求头', () => {
  /*
   * 管理端没有登录也没有 CSRF 令牌，挡住跨站请求的正是两点：
   * 接口只收 application/json，且服务端不配置任何 CORS 响应头。
   * 前端这一侧的责任就是始终带上这个 Content-Type —— 一旦改成表单编码，
   * 保护就没了。服务端那一侧由 SecurityInvariantsTest.noCorsIsEnabled 把关。
   */
  it('带请求体时 Content-Type 恒为 application/json', async () => {
    respondWith({ success: true, data: null, error: null })

    await http.post('/api/gateways', { name: 'x' })

    const init = vi.mocked(fetch).mock.calls[0][1]
    expect((init?.headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })

  it('无请求体时不带 Content-Type', async () => {
    respondWith({ success: true, data: null, error: null })

    await http.delete('/api/gateways/g1')

    const init = vi.mocked(fetch).mock.calls[0][1]
    expect((init?.headers as Record<string, string>)['Content-Type']).toBeUndefined()
  })
})
