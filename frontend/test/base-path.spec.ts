import { afterEach, describe, expect, it, vi } from 'vitest'
import { normalizeBasePath } from '../src/basePath'

/*
 * 子路径部署的前缀（见 src/basePath.ts）。
 *
 * 前缀是构建期注入的，所以每一条都得先把 import.meta.env 摆成"构建产物"的样子
 * （DEV=false）再重新 import —— 模块顶层那次求值只发生一次。
 *
 * 服务端那一半在 SubPathDeploymentTest 里：两边用的是同一个前缀，但是两套机制，
 * 各自都能单独坏掉。
 */

// 路由要真的装出来才能读到 history.base，但目标页面与前缀无关，换成空壳。
const blank = { template: '<div />' }
vi.mock('../src/views/GatewayListView.vue', () => ({ default: blank }))
vi.mock('../src/views/GatewayDetailView.vue', () => ({ default: blank }))
vi.mock('../src/views/CallRecordsView.vue', () => ({ default: blank }))
vi.mock('../src/views/LoginView.vue', () => ({ default: blank }))

/** 把环境摆成"带前缀的构建产物"，然后拿一份全新的模块。 */
async function freshModules(basePath: string | undefined) {
  vi.stubEnv('DEV', false)
  if (basePath !== undefined) {
    vi.stubEnv('VITE_BASE_PATH', basePath)
  }
  vi.resetModules()
  return {
    basePath: (await import('../src/basePath')).BASE_PATH,
    client: await import('../src/api/client'),
    router: (await import('../src/router')).router
  }
}

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('前缀的归一化', () => {
  it.each([
    ['', ''],
    ['/', ''],
    ['   ', ''],
    ['/kbmcp', '/kbmcp'],
    // 补上开头的斜杠：写配置的人很容易漏掉它
    ['kbmcp', '/kbmcp'],
    // 去掉末尾的斜杠：留着会在拼接处拼出 //ui/，那是协议相对 URL，会跳出本站
    ['/kbmcp/', '/kbmcp'],
    ['/kbmcp//', '/kbmcp'],
    ['/a/b/', '/a/b']
  ])('%o 归一成 %o', (raw, expected) => {
    expect(normalizeBasePath(raw)).toBe(expected)
  })

  it('没设就是空串 —— 默认部署占据整个根路径', () => {
    expect(normalizeBasePath(undefined)).toBe('')
  })
})

describe('前缀的取值时机', () => {
  it('构建产物读 VITE_BASE_PATH', async () => {
    expect((await freshModules('/kbmcp')).basePath).toBe('/kbmcp')
  })

  it('开发期恒为空，哪怕环境里设了 —— dev server 的 base 固定是 /', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_BASE_PATH', '/kbmcp')
    vi.resetModules()

    expect((await import('../src/basePath')).BASE_PATH).toBe('')
  })
})

describe('前缀要作用到的地方', () => {
  it('路由的 history base 带上前缀，深链和刷新才落得回来', async () => {
    const { router } = await freshModules('/kbmcp')

    // vue-router 自己会去掉末尾斜杠，这里按它归一化之后的形态断言
    expect(router.options.history.base).toBe('/kbmcp/ui')
  })

  it('默认部署时 history base 仍是 /ui/ —— 老书签不会失效', async () => {
    const { router } = await freshModules('')

    expect(router.options.history.base).toBe('/ui')
  })

  it('接口请求带上前缀，不会打到同域的其他应用上', async () => {
    // 声明形参，否则 mock.calls 的元组类型是空的，取 [0][0] 通不过 vue-tsc
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => ({
      ok: true,
      status: 200,
      json: async () => ({ success: true, data: null, error: null })
    }))
    vi.stubGlobal('fetch', fetchMock)
    const { client } = await freshModules('/kbmcp')

    await client.http.get('/api/gateways')

    expect(fetchMock.mock.calls[0][0]).toBe('/kbmcp/api/gateways')
  })

  it('默认部署时接口地址原样发出', async () => {
    // 声明形参，否则 mock.calls 的元组类型是空的，取 [0][0] 通不过 vue-tsc
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => ({
      ok: true,
      status: 200,
      json: async () => ({ success: true, data: null, error: null })
    }))
    vi.stubGlobal('fetch', fetchMock)
    const { client } = await freshModules('')

    await client.http.get('/api/gateways')

    expect(fetchMock.mock.calls[0][0]).toBe('/api/gateways')
  })
})
