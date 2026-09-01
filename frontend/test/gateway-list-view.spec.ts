import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { GatewaySummary } from '../src/api/types'
import { createTestRouter } from './support/router'

/*
 * 需求 10.1 的验收断言。
 *
 * 这些断言原本在 GatewayPageControllerTest 里对着 Thymeleaf 渲染出的 HTML 做；
 * 列表页迁到 Vue 之后那段 HTML 不再由服务端产生，断言也跟着搬到这里，
 * 免得需求 10.1 在两边都没人守。
 */
const api = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  remove: vi.fn()
}))

vi.mock('../src/api/gateways', () => ({ gatewayApi: api }))

const { useAlerts } = await import('../src/composables/useAlerts')
const GatewayListView = (await import('../src/views/GatewayListView.vue')).default

function gateway(overrides: Partial<GatewaySummary> = {}): GatewaySummary {
  return {
    id: 'gw-1',
    name: '列表页网关',
    slug: 'kb-gateway',
    description: '网关用途说明',
    status: 'READY',
    downstreamCount: 2,
    toolCount: 7,
    mcpUrl: 'http://127.0.0.1:8080/mcp/kb-gateway',
    createdAt: '2026-08-30T10:00:00Z',
    updatedAt: '2026-08-31T09:30:00Z',
    ...overrides
  }
}

beforeEach(() => {
  api.list.mockReset().mockResolvedValue([])
  api.create.mockReset()
  api.remove.mockReset()
  useAlerts().clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function mountView() {
  const router = createTestRouter()
  await router.push('/gateways')
  await router.isReady()
  const view = mount(GatewayListView, { global: { plugins: [router] } })
  await flushPromises()
  return view
}

async function render(rows: GatewaySummary[]) {
  api.list.mockResolvedValue(rows)
  return mountView()
}

describe('需求 10.1：网关列表页', () => {
  it('展示名称、slug、状态、子 MCP 数量、工具数量和更新时间', async () => {
    const view = await render([gateway()])
    const text = view.text()

    expect(text).toContain('列表页网关')
    expect(text).toContain('kb-gateway')
    expect(text).toContain('READY')
    expect(text).toContain('网关用途说明')

    const cells = view.findAll('tbody td')
    expect(cells[3].text()).toBe('2')
    expect(cells[4].text()).toBe('7')
    // 更新时间以相对时间展示，绝对时间放 title 备查
    expect(cells[5].attributes('title')).toMatch(/^2026-08-31 /)
  })

  it('指向详情页的链接走前端路由，href 仍是 /ui/ 下的真实地址', async () => {
    const view = await render([gateway({ id: 'gw-abc' })])

    // RouterLink 渲染成真的 <a>，href 由 /ui/ 这个 base 拼出来 ——
    // 直接复制这个地址粘到浏览器里也要能打开
    const links = view.findAll('a[href="/ui/gateways/gw-abc"]')
    expect(links.length).toBeGreaterThan(0)
    expect(links[0].element.tagName).toBe('A')
  })

  it('没有网关时给出引导文案，而不是一张空表', async () => {
    const view = await render([])

    expect(view.text()).toContain('还没有网关')
    expect(view.find('table').exists()).toBe(false)
  })

  it('加载失败时给出重试入口，不是一直停在「加载中」', async () => {
    api.list.mockRejectedValue(new Error('boom'))
    const view = await mountView()

    expect(view.text()).toContain('重试')
    expect(view.text()).not.toContain('加载中')
  })

  it('搜索框按名称、slug 和描述过滤', async () => {
    const view = await render([
      gateway({ id: 'a', name: '知识库网关', slug: 'kb', description: null }),
      gateway({ id: 'b', name: '工单网关', slug: 'ticket', description: '客服用' })
    ])

    await view.find('input[type="search"]').setValue('ticket')

    expect(view.text()).toContain('工单网关')
    expect(view.text()).not.toContain('知识库网关')
  })
})

describe('需求 FR-05.3：令牌只完整显示一次', () => {
  it('创建成功后当场显示令牌，且不整页刷新把它冲掉', async () => {
    const view = await render([])
    api.create.mockResolvedValue({
      gateway: { id: 'gw-new', name: '新网关', slug: 'fresh' },
      accessToken: 'mcpgw_the-one-time-token'
    })

    await view.find('.page-head button').trigger('click')
    await view.find('#create-name').setValue('新网关')
    await view.find('#create-slug').setValue('fresh')
    await view.find('form').trigger('submit')
    await flushPromises()

    expect(view.text()).toContain('这是唯一一次完整显示该令牌')
    expect(view.find('.card.accent-warning input').attributes('value'))
      .toBe('mcpgw_the-one-time-token')

    // 成功提示同样还在 —— Thymeleaf 版本这里 600ms 后就被 location.reload() 冲掉了
    expect(useAlerts().items.some(item => item.title === '网关已创建')).toBe(true)
  })

  it('slug 不合法时不发请求，直接在前端拦下', async () => {
    const view = await render([])

    await view.find('.page-head button').trigger('click')
    await view.find('#create-name').setValue('网关')
    await view.find('#create-slug').setValue('bad slug!')
    await view.find('form').trigger('submit')
    await flushPromises()

    expect(api.create).not.toHaveBeenCalled()
    expect(useAlerts().items[0].title).toBe('slug 不合法')
  })
})

describe('需求 6.1.7：删除网关', () => {
  it('二次确认里说清连带影响', async () => {
    const confirm = vi.fn().mockReturnValue(true)
    vi.stubGlobal('confirm', confirm)
    const view = await render([gateway()])

    await view.find('.btn-danger').trigger('click')
    await flushPromises()

    expect(confirm.mock.calls[0][0]).toContain('调用记录')
    expect(api.remove).toHaveBeenCalledWith('gw-1')
  })

  it('取消确认就什么都不做', async () => {
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(false))
    const view = await render([gateway()])

    await view.find('.btn-danger').trigger('click')
    await flushPromises()

    expect(api.remove).not.toHaveBeenCalled()
  })
})
