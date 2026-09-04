import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * 登录态（需求 12.8）。
 *
 * useSession 的状态是模块级的单例 —— 这正是它的用途，但也意味着用例之间会串味。
 * 每个用例前 resetModules，再动态 import 一份干净的模块。
 */

interface FetchCall { url: string, init?: RequestInit }

const calls: FetchCall[] = []

function respondWith(body: unknown, status = 200): void {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init })
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => body
    }
  }))
}

function session(authenticated: boolean, username: string | null) {
  return { success: true, data: { authenticated, username }, error: null }
}

beforeEach(() => {
  calls.length = 0
  vi.resetModules()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('会话加载', () => {
  it('启动时拉一次会话，并把身份记下来', async () => {
    respondWith(session(true, 'admin'))
    const { useSession } = await import('../src/composables/useSession')

    const store = useSession()
    await store.load()

    expect(store.state.ready).toBe(true)
    expect(store.state.authenticated).toBe(true)
    expect(store.state.username).toBe('admin')
    expect(calls[0].url).toBe('/api/auth/session')
  })

  it('只发一次请求 —— 每次路由跳转都去问服务端会让翻页变慢', async () => {
    respondWith(session(true, 'admin'))
    const { useSession } = await import('../src/composables/useSession')

    const store = useSession()
    await Promise.all([store.load(), store.load()])
    await store.load()

    expect(calls).toHaveLength(1)
  })

  it('会话接口拿不到结果时按未登录处理，而不是卡在空白页上', async () => {
    respondWith({ success: false, data: null, error: { code: 'INTERNAL_ERROR', message: '' } }, 500)
    const { useSession } = await import('../src/composables/useSession')

    const store = useSession()
    await store.load()

    expect(store.state.ready).toBe(true)
    expect(store.state.authenticated).toBe(false)
  })
})

describe('登录与退出', () => {
  it('登录成功后状态变为已登录', async () => {
    respondWith(session(true, 'operator'))
    const { useSession } = await import('../src/composables/useSession')

    const store = useSession()
    await store.signIn('operator', 'a-long-enough-passphrase')

    expect(store.state.authenticated).toBe(true)
    expect(store.state.username).toBe('operator')
    expect(calls[0].url).toBe('/api/auth/login')
  })

  it('登录失败时状态保持未登录，异常向上抛给登录页显示', async () => {
    respondWith({ success: false, data: null, error: { code: 'UNAUTHORIZED', message: '' } }, 401)
    const { useSession } = await import('../src/composables/useSession')

    const store = useSession()
    await expect(store.signIn('operator', 'wrong')).rejects.toBeTruthy()

    expect(store.state.authenticated).toBe(false)
    expect(store.state.username).toBeNull()
  })

  it('退出登录即使服务端报错也要清掉本地状态 —— 会话已经不可信了', async () => {
    respondWith(session(true, 'admin'))
    const { useSession } = await import('../src/composables/useSession')
    const store = useSession()
    await store.load()

    respondWith({ success: false, data: null, error: { code: 'INTERNAL_ERROR', message: '' } }, 500)
    await expect(store.signOut()).rejects.toBeTruthy()

    expect(store.state.authenticated).toBe(false)
    expect(store.state.username).toBeNull()
  })
})
