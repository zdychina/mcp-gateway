import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, http, setUnauthorizedHandler } from '../src/api/client'
import { callRecordApi } from '../src/api/gateways'

function respondWith(body: unknown, status = 200): void {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body
  })))
}

afterEach(() => {
  vi.unstubAllGlobals()
  // Cookie 是文档级的，不清掉会串到别的用例里
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
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
   * 管理端现在有登录，也就有了会话 Cookie，跨站保护相应地变成三层：
   * 服务端不配置任何 CORS 响应头、会话 Cookie 是 SameSite=Strict、写请求校验 CSRF 令牌。
   * 前端这一侧要守住两条：Content-Type 恒为 application/json（改成表单编码会打掉
   * 第一层），以及把服务端下发的 XSRF-TOKEN 回填到请求头上。
   * 服务端那一侧由 SecurityInvariantsTest 把关。
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

  it('写请求把 XSRF-TOKEN Cookie 回填到请求头上', async () => {
    document.cookie = 'XSRF-TOKEN=token-from-server'
    respondWith({ success: true, data: null, error: null })

    await http.post('/api/gateways', { name: 'x' })

    const init = vi.mocked(fetch).mock.calls[0][1]
    expect((init?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('token-from-server')
  })

  it('GET 不带 CSRF 令牌 —— 安全方法本来就不在保护范围内', async () => {
    document.cookie = 'XSRF-TOKEN=token-from-server'
    respondWith({ success: true, data: [], error: null })

    await http.get('/api/gateways')

    const init = vi.mocked(fetch).mock.calls[0][1]
    expect((init?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined()
  })
})

describe('会话失效的处理', () => {
  afterEach(() => {
    setUnauthorizedHandler(null)
  })

  it('管理接口返回 401 时通知上层 —— 会话过期要把用户送回登录页', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    respondWith({ success: false, data: null, error: { code: 'UNAUTHORIZED', message: '' } }, 401)

    await expect(http.get('/api/gateways')).rejects.toBeInstanceOf(ApiError)

    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('登录接口自己的 401 不触发跳转 —— 否则输错口令会把登录页刷掉', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    respondWith({ success: false, data: null, error: { code: 'UNAUTHORIZED', message: '' } }, 401)

    await expect(http.post('/api/auth/login', {})).rejects.toBeInstanceOf(ApiError)

    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('403 不当作会话失效 —— CSRF 令牌过期该重取令牌，不该重新登录', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    respondWith({ success: false, data: null, error: { code: 'FORBIDDEN', message: '' } }, 403)

    await expect(http.post('/api/gateways', {})).rejects.toBeInstanceOf(ApiError)

    expect(onUnauthorized).not.toHaveBeenCalled()
  })
})

describe('下载文件', () => {
  /** 下载走的是二进制分支，和 JSON 信封那条路不是同一段代码。 */
  function respondWithFile(): ReturnType<typeof vi.fn> {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      headers: new Headers({
        'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'X-Export-Rows': '42',
        'X-Export-Truncated': 'false'
      }),
      blob: async () => new Blob(['xlsx'])
    }))
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  it('把筛选条件和列一起拼进查询串', async () => {
    const fetchMock = respondWithFile()

    await callRecordApi.exportFile('gw 1', { status: 'ERROR', toolName: 'kb_a__' },
      ['startedAt', 'request:/q'], ['开始时间', '查询词'])

    const url = new URL(fetchMock.mock.calls[0][0] as string, 'http://localhost')
    expect(url.pathname).toBe('/api/gateways/gw%201/call-records/export')
    expect(url.searchParams.get('status')).toBe('ERROR')
    expect(url.searchParams.getAll('columns')).toEqual(['startedAt', 'request:/q'])
    // 中文表头由 URLSearchParams 按 UTF-8 百分号编码，服务端才不会收到乱码
    expect(url.searchParams.getAll('labels')).toEqual(['开始时间', '查询词'])
  })

  it('分页和抽取参数不发给导出接口 —— 导的是筛选结果不是某一页', async () => {
    const fetchMock = respondWithFile()

    await callRecordApi.exportFile('gw-1',
      { status: 'ERROR', page: 3, size: 100, extract: ['request:/q'] }, ['status'], ['状态'])

    const url = new URL(fetchMock.mock.calls[0][0] as string, 'http://localhost')
    expect(url.searchParams.has('page')).toBe(false)
    expect(url.searchParams.has('size')).toBe(false)
    expect(url.searchParams.has('extract')).toBe(false)
  })

  it('返回 blob 和响应头，调用方要靠头判断有没有被截断', async () => {
    respondWithFile()

    const { blob, headers } = await callRecordApi.exportFile('gw-1')

    expect(blob.size).toBeGreaterThan(0)
    expect(headers.get('X-Export-Rows')).toBe('42')
  })

  /*
   * 服务端在开始写文件之前校验参数，失败时回的仍然是 JSON 信封。
   * 不按内容类型分流的话，一个 400 会被当成一份 0 字节的 Excel 存到磁盘上。
   */
  it('服务端回 JSON 错误时抛 ApiError，而不是存下一个坏文件', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 400,
      headers: new Headers({ 'Content-Type': 'application/json' }),
      json: async () => ({
        success: false, data: null,
        error: { code: 'INVALID_REQUEST', message: 'unknown column: nope' }
      }),
      blob: async () => new Blob(['should not be used'])
    })))

    await expect(callRecordApi.exportFile('gw-1', {}, ['nope'], ['x']))
      .rejects.toSatisfy((error: unknown) =>
        error instanceof ApiError && error.code === 'INVALID_REQUEST')
  })

  it('HTTP 200 但回的是 JSON，同样按错误处理', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Type': 'application/json;charset=UTF-8' }),
      json: async () => ({ success: false, data: null, error: { code: 'INTERNAL_ERROR', message: '' } }),
      blob: async () => new Blob([''])
    })))

    await expect(callRecordApi.exportFile('gw-1')).rejects.toBeInstanceOf(ApiError)
  })
})
