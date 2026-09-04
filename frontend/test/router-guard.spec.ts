import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * 路由守卫（需求 12.8）。
 *
 * 用的是 src/router 里那个真的守卫，而不是在测试里重写一份同构的 —— 重写的那份
 * 永远是绿的，改坏了真守卫也照样通过。
 *
 * 再说一次：这是体验不是访问控制。绕过守卫只能看到一个拉不到任何数据的空壳，
 * 服务端那道门由 ApiAuthorizationInvariantsTest 守着。
 */
const auth = vi.hoisted(() => ({
  current: vi.fn(),
  signIn: vi.fn(),
  signOut: vi.fn()
}))

vi.mock('../src/api/auth', () => ({ authApi: auth }))

// 目标页面本身与守卫无关，换成空壳，免得把整棵组件树和它们的接口调用拖进来。
const blank = { template: '<div />' }
vi.mock('../src/views/GatewayListView.vue', () => ({ default: blank }))
vi.mock('../src/views/GatewayDetailView.vue', () => ({ default: blank }))
vi.mock('../src/views/CallRecordsView.vue', () => ({ default: blank }))
vi.mock('../src/views/LoginView.vue', () => ({ default: blank }))

async function freshRouter() {
  vi.resetModules()
  const { router } = await import('../src/router')
  return router
}

beforeEach(() => {
  auth.current.mockReset()
  auth.signIn.mockReset()
  auth.signOut.mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('未登录', () => {
  beforeEach(() => {
    auth.current.mockResolvedValue({ authenticated: false, username: null })
  })

  it('访问管理页面时被送去登录页，并记下原本要去的地址', async () => {
    const router = await freshRouter()

    await router.push('/gateways/gw-1')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/gateways/gw-1')
  })

  it('登录页本身可以直接访问', async () => {
    const router = await freshRouter()

    await router.push('/login')

    expect(router.currentRoute.value.name).toBe('login')
  })

  it('会话只查一次，翻页不会每次都去问服务端', async () => {
    const router = await freshRouter()

    await router.push('/gateways')
    await router.push('/login')
    await router.push('/gateways/gw-1')

    expect(auth.current).toHaveBeenCalledTimes(1)
  })
})

describe('已登录', () => {
  beforeEach(() => {
    auth.current.mockResolvedValue({ authenticated: true, username: 'admin' })
  })

  it('管理页面正常放行', async () => {
    const router = await freshRouter()

    await router.push('/gateways/gw-1')

    expect(router.currentRoute.value.name).toBe('gateway-detail')
  })

  it('不会停在登录页上 —— 已经登录了那个页面没有意义', async () => {
    const router = await freshRouter()

    await router.push('/login')

    expect(router.currentRoute.value.path).toBe('/gateways')
  })
})
