import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import type { Router } from 'vue-router'

/*
 * 登录页（需求 12.8）。
 *
 * 这里验的是页面行为：提交表单、显示失败原因、登录后跳到哪。
 * 真正的认证在服务端，由 AuthApiTest 覆盖 —— 这个文件里的任何断言都不构成访问控制。
 */
const auth = vi.hoisted(() => ({
  current: vi.fn(),
  signIn: vi.fn(),
  signOut: vi.fn()
}))

vi.mock('../src/api/auth', () => ({ authApi: auth }))

const LoginView = (await import('../src/views/LoginView.vue')).default
const { ApiError } = await import('../src/api/client')

const blank = { template: '<div />' }

function routerFor(initial: string): Router {
  const router = createRouter({
    history: createWebHistory('/ui/'),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/gateways', name: 'gateway-list', component: blank },
      { path: '/gateways/:id', name: 'gateway-detail', component: blank }
    ]
  })
  router.push(initial)
  return router
}

async function mountLogin(initial = '/login') {
  const router = routerFor(initial)
  await router.isReady()
  const wrapper = mount(LoginView, { global: { plugins: [router] } })
  return { wrapper, router }
}

async function submit(wrapper: ReturnType<typeof mount>, username: string, password: string) {
  await wrapper.get('#login-username').setValue(username)
  await wrapper.get('#login-password').setValue(password)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  auth.current.mockReset().mockResolvedValue({ authenticated: false, username: null })
  auth.signIn.mockReset()
  auth.signOut.mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('登录页', () => {
  it('提交表单时把用户名和口令交给登录接口', async () => {
    auth.signIn.mockResolvedValue({ authenticated: true, username: 'admin' })
    const { wrapper } = await mountLogin()

    await submit(wrapper, 'admin', 'a-long-enough-passphrase')

    expect(auth.signIn).toHaveBeenCalledWith('admin', 'a-long-enough-passphrase')
  })

  it('登录成功后回到原本要去的页面', async () => {
    auth.signIn.mockResolvedValue({ authenticated: true, username: 'admin' })
    const { wrapper, router } = await mountLogin('/login?redirect=/gateways/gw-1')

    await submit(wrapper, 'admin', 'a-long-enough-passphrase')

    expect(router.currentRoute.value.fullPath).toBe('/gateways/gw-1')
  })

  it('redirect 指向站外时被忽略 —— 否则登录页就成了开放重定向', async () => {
    auth.signIn.mockResolvedValue({ authenticated: true, username: 'admin' })
    const { wrapper, router } = await mountLogin(
      '/login?redirect=' + encodeURIComponent('https://evil.example/phish'))

    await submit(wrapper, 'admin', 'a-long-enough-passphrase')

    expect(router.currentRoute.value.fullPath).toBe('/gateways')
  })

  it('协议相对地址同样是站外，一并挡掉', async () => {
    auth.signIn.mockResolvedValue({ authenticated: true, username: 'admin' })
    const { wrapper, router } = await mountLogin(
      '/login?redirect=' + encodeURIComponent('//evil.example/phish'))

    await submit(wrapper, 'admin', 'a-long-enough-passphrase')

    expect(router.currentRoute.value.fullPath).toBe('/gateways')
  })

  it('凭证不对时给一句不区分字段的提示，并清掉口令输入框', async () => {
    auth.signIn.mockRejectedValue(new ApiError('UNAUTHORIZED', 'invalid username or password'))
    const { wrapper } = await mountLogin()

    await submit(wrapper, 'admin', 'wrong-password')

    expect(wrapper.text()).toContain('用户名或口令不正确')
    // 服务端不区分哪一半错了，前端也不能替它猜
    expect(wrapper.text()).not.toContain('用户名不存在')
    expect((wrapper.get('#login-password').element as HTMLInputElement).value).toBe('')
  })

  it('被限速时说明是限速而不是口令错 —— 否则运维会一直怀疑自己记错了口令', async () => {
    auth.signIn.mockRejectedValue(new ApiError('TOO_MANY_ATTEMPTS', 'too many failed login attempts'))
    const { wrapper } = await mountLogin()

    await submit(wrapper, 'admin', 'a-long-enough-passphrase')

    expect(wrapper.text()).toContain('临时锁定')
  })

  it('提交期间禁用按钮，避免连点制造多次失败把自己锁掉', async () => {
    let release: (value: unknown) => void = () => {}
    auth.signIn.mockReturnValue(new Promise(resolve => { release = resolve }))
    const { wrapper } = await mountLogin()

    await wrapper.get('#login-username').setValue('admin')
    await wrapper.get('#login-password').setValue('a-long-enough-passphrase')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()

    release({ authenticated: true, username: 'admin' })
    await flushPromises()
  })

  it('口令输入框是 password 类型，且按 current-password 提示浏览器', async () => {
    const { wrapper } = await mountLogin()

    expect(wrapper.get('#login-password').attributes('type')).toBe('password')
    expect(wrapper.get('#login-password').attributes('autocomplete')).toBe('current-password')
  })
})
