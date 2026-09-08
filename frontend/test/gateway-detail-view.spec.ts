import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { DownstreamMcp, GatewayDetail, GatewayTool } from '../src/api/types'
import { createTestRouter } from './support/router'

/*
 * 需求 10.2 的验收断言，外加这个页面上几组最容易改坏的语义：
 *
 * - 需求 12.4 / 6.2：headers 的三态（不传=保持、{}=清空、有值=替换）
 * - 需求 6.5.5：customDescription 的三态（不传=不改、null=清除、有值=覆盖）
 * - 需求 6.4.7：同步失败保留上一次成功的快照，不能报成"工具没了"
 * - 需求 6.2.9：导入时配置整批落库，同步逐个成败
 *
 * 这些在 Thymeleaf 时代靠 app.js 里的注释和人工评审守着，现在有测试钉住。
 */
const gatewayApi = vi.hoisted(() => ({
  detail: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  agentConfig: vi.fn(),
  rotateToken: vi.fn()
}))
const downstreamApi = vi.hoisted(() => ({
  import: vi.fn(),
  sync: vi.fn(),
  update: vi.fn(),
  remove: vi.fn()
}))
const toolApi = vi.hoisted(() => ({ update: vi.fn() }))

vi.mock('../src/api/gateways', () => ({ gatewayApi, downstreamApi, toolApi }))

const { useAlerts } = await import('../src/composables/useAlerts')
const { ApiError } = await import('../src/api/client')
const GatewayDetailView = (await import('../src/views/GatewayDetailView.vue')).default

// ------------------------------------------------------------------ 夹具

function tool(overrides: Partial<GatewayTool> = {}): GatewayTool {
  return {
    id: 'tool-1',
    downstreamMcpId: 'ds-1',
    exposedName: 'kb_a__search',
    originalName: 'search',
    originalDescription: '下游的原始描述',
    customDescription: null,
    effectiveDescription: '下游的原始描述',
    enabled: true,
    lastSyncedAt: '2026-08-31T09:00:00Z',
    ...overrides
  }
}

function downstream(overrides: Partial<DownstreamMcp> = {}): DownstreamMcp {
  return {
    id: 'ds-1',
    name: 'kb_a',
    type: 'streamable-http',
    url: 'https://kb.example.com/mcp',
    headers: { Authorization: '******' },
    syncStatus: 'SUCCESS',
    lastSyncAt: '2026-08-31T09:00:00Z',
    lastSyncError: null,
    toolCount: 1,
    tools: [tool()],
    ...overrides
  }
}

function detail(overrides: Partial<GatewayDetail> = {}): GatewayDetail {
  return {
    id: 'gw-1',
    name: '详情页网关',
    slug: 'kb-gateway',
    description: '网关用途说明',
    status: 'READY',
    mcpUrl: 'http://127.0.0.1:8080/mcp/kb-gateway',
    downstreams: [downstream()],
    createdAt: '2026-08-30T10:00:00Z',
    updatedAt: '2026-08-31T09:30:00Z',
    ...overrides
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  gatewayApi.detail.mockResolvedValue(detail())
  gatewayApi.agentConfig.mockResolvedValue({
    mcpServers: {
      'kb-gateway': {
        type: 'streamable-http',
        url: 'http://127.0.0.1:8080/mcp/kb-gateway',
        headers: { Authorization: 'Bearer <gateway-access-token>' }
      }
    }
  })
  useAlerts().clear()
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
})

async function render() {
  const router = createTestRouter()
  await router.push('/gateways/gw-1')
  await router.isReady()
  const view = mount(GatewayDetailView, { global: { plugins: [router] } })
  await flushPromises()
  return view
}

/** 新增子 MCP 默认是表单模式，粘 JSON 的用例得先切过去。 */
async function toJsonMode(view: ReturnType<typeof mount>) {
  await view.findAll('.mode-switch button')
    .find(button => button.text().includes('JSON'))!
    .trigger('click')
}


type View = Awaited<ReturnType<typeof render>>

/** 子 MCP 的编辑表单。 */
function downstreamForm(view: View) {
  return view.find('.card.nested form')
}

/** 基本信息那一段的表单。 */
function basicsForm(view: View) {
  return view.find('#gateway-form')
}

// ------------------------------------------------------------------ 用例

describe('需求 10.2：详情页四段', () => {
  it('四段内容齐全', async () => {
    const view = await render()
    const text = view.text()

    expect(text).toContain('基本信息')
    expect(text).toContain('子 MCP')
    expect(text).toContain('聚合工具')
    expect(text).toContain('Agent 接入')

    expect(text).toContain('详情页网关')
    expect(text).toContain('kb_a')
    expect(text).toContain('kb_a__search')
    expect(text).toContain('下游的原始描述')
    // MCP 地址在只读输入框里，text() 取不到 value
  })

  it('接入 JSON 用占位符，不含真实令牌', async () => {
    const view = await render()

    const configField = view.findAll('textarea').find(
      area => area.attributes('aria-label') === '接入 JSON')
    expect(configField?.element.value).toContain('<gateway-access-token>')
    expect(view.html()).not.toContain('mcpgw_')
  })

  it('网关不存在时给出说明和返回入口，而不是空白页', async () => {
    gatewayApi.detail.mockRejectedValue(new ApiError('GATEWAY_NOT_FOUND', 'no such gateway'))
    const view = await render()

    expect(view.text()).toContain('没有找到这个网关')
    expect(view.find('a[href="/ui/gateways"]').exists()).toBe(true)
  })
})

describe('需求 12.4：headers 只显示遮罩值，且默认不回传', () => {
  it('页面上只有 header 名称和遮罩值', async () => {
    const view = await render()

    expect(view.text()).toContain('Authorization: ******')
  })

  it('不勾「替换 headers」时，请求体里根本没有 headers 字段', async () => {
    downstreamApi.update.mockResolvedValue({ gateway: detail(), syncResult: null })
    const view = await render()

    await downstreamForm(view).trigger('submit')
    await flushPromises()

    const request = downstreamApi.update.mock.calls[0][2]
    // 关键：不是 headers === undefined，而是这个键压根不存在。
    // 把页面上的遮罩值传回去会把真凭证覆盖成 ******
    expect('headers' in request).toBe(false)
    expect(request).toEqual({ name: 'kb_a', url: 'https://kb.example.com/mcp' })
  })

  it('勾选并填入内容时整体替换', async () => {
    downstreamApi.update.mockResolvedValue({ gateway: detail(), syncResult: null })
    const view = await render()

    await view.find('.check input[type="checkbox"]').setValue(true)
    await view.find('textarea[aria-label="新的 headers JSON"]')
      .setValue('{"Authorization":"Bearer 新令牌"}')
    await downstreamForm(view).trigger('submit')
    await flushPromises()

    expect(downstreamApi.update.mock.calls[0][2].headers)
      .toEqual({ Authorization: 'Bearer 新令牌' })
  })

  it('勾选后留空表示清空，且要二次确认', async () => {
    downstreamApi.update.mockResolvedValue({ gateway: detail(), syncResult: null })
    const view = await render()

    await view.find('.check input[type="checkbox"]').setValue(true)
    await downstreamForm(view).trigger('submit')
    await flushPromises()

    expect(vi.mocked(confirm).mock.calls.some(call => String(call[0]).includes('清空'))).toBe(true)
    expect(downstreamApi.update.mock.calls[0][2].headers).toEqual({})
  })

  it('headers 不是合法 JSON 时不发请求', async () => {
    const view = await render()

    await view.find('.check input[type="checkbox"]').setValue(true)
    await view.find('textarea[aria-label="新的 headers JSON"]').setValue('{ 这不是 JSON')
    await downstreamForm(view).trigger('submit')
    await flushPromises()

    expect(downstreamApi.update).not.toHaveBeenCalled()
    expect(useAlerts().items[0].title).toBe('headers 不是合法 JSON')
  })
})

describe('需求 6.5.5：自定义描述的三态', () => {
  it('填了内容就覆盖', async () => {
    toolApi.update.mockResolvedValue(tool({ customDescription: '运营写的提示词' }))
    const view = await render()

    // 描述编辑器默认收起，先点开那一行的入口
    await view.findAll('tr').find(row => row.text().includes('kb_a__search'))!
      .find('.desc-edit').trigger('click')
    await view.find('textarea[aria-label="kb_a__search 的自定义描述"]').setValue('运营写的提示词')
    // 编辑态里有"取消"和"保存"两个按钮，要点准那个主按钮
    await view.find('.table.tools .desc-actions .btn-primary').trigger('click')
    await flushPromises()

    expect(toolApi.update).toHaveBeenCalledWith('gw-1', 'tool-1',
      { customDescription: '运营写的提示词' })
  })

  it('清空时显式传 null，表示回退到原始描述', async () => {
    gatewayApi.detail.mockResolvedValue(
      detail({ downstreams: [downstream({ tools: [tool({ customDescription: '旧的' })] })] }))
    toolApi.update.mockResolvedValue(tool({ customDescription: null }))
    const view = await render()

    // 描述编辑器默认收起，先点开那一行的入口
    await view.findAll('tr').find(row => row.text().includes('kb_a__search'))!
      .find('.desc-edit').trigger('click')
    await view.find('textarea[aria-label="kb_a__search 的自定义描述"]').setValue('   ')
    // 编辑态里有"取消"和"保存"两个按钮，要点准那个主按钮
    await view.find('.table.tools .desc-actions .btn-primary').trigger('click')
    await flushPromises()

    // 必须是 null 而不是 undefined —— 后者会被 JSON.stringify 丢掉，
    // 服务端就会当成"没传这个字段"，也就是"不改动"
    const request = toolApi.update.mock.calls[0][2]
    expect(request.customDescription).toBeNull()
    expect(JSON.stringify(request)).toContain('null')
  })
})

describe('需求 6.5.2：工具启停', () => {
  it('停用后立即反映在界面上', async () => {
    toolApi.update.mockResolvedValue(tool({ enabled: false }))
    const view = await render()

    await view.find('.switch input').setValue(false)
    await flushPromises()

    expect(toolApi.update).toHaveBeenCalledWith('gw-1', 'tool-1', { enabled: false })
    expect(useAlerts().items[0].title).toBe('工具已停用')
  })

  it('服务端没改成时把开关拨回去 —— 界面不能显示一个假状态', async () => {
    toolApi.update.mockRejectedValue(new ApiError('INTERNAL_ERROR', 'internal error'))
    const view = await render()

    const input = view.find('.switch input')
    await input.setValue(false)
    await flushPromises()

    expect((input.element as HTMLInputElement).checked).toBe(true)
    expect(useAlerts().items[0].title).toBe('修改启用状态失败')
  })
})

describe('需求 6.4.7：同步失败保留上一次成功的快照', () => {
  it('同步失败报成警告，并说明快照还在', async () => {
    downstreamApi.sync.mockResolvedValue({
      downstreamId: 'ds-1',
      downstreamName: 'kb_a',
      succeeded: false,
      added: 0, updated: 0, unchanged: 0, removed: 0,
      errorCode: 'DOWNSTREAM_TIMEOUT',
      errorMessage: 'kb_a: downstream call timed out'
    })
    const view = await render()

    await view.find('.card.nested .card-header button').trigger('click')
    await flushPromises()

    const alert = useAlerts().items[0]
    // 同步失败不是请求失败：HTTP 是 200，succeeded 才是判据
    expect(alert.kind).toBe('warning')
    expect(alert.title).toContain('保留上一次成功的工具快照')
    expect(alert.detail).toContain('DOWNSTREAM_TIMEOUT')
  })

  it('同步成功报出增删改统计，并重新取详情', async () => {
    downstreamApi.sync.mockResolvedValue({
      downstreamId: 'ds-1', downstreamName: 'kb_a', succeeded: true,
      added: 3, updated: 1, unchanged: 2, removed: 1, errorCode: null, errorMessage: null
    })
    const view = await render()

    await view.find('.card.nested .card-header button').trigger('click')
    await flushPromises()

    expect(useAlerts().items[0].detail).toContain('kb_a：新增 3，更新 1，未变 2，移除 1')
    // 网关这边立即生效，但 Agent 那边要重新拉一次才看得到 —— 不说这句会以为改完就完了
    expect(useAlerts().items[0].detail).toContain('重新拉取工具列表')
    // 同步接口只返回统计，工具快照变了必须重新取
    expect(gatewayApi.detail).toHaveBeenCalledTimes(2)
  })
})

describe('编辑子 MCP 后自动重新同步', () => {
  /*
   * 从前编辑完不同步：把 URL 指到另一个下游之后，快照还是旧那套工具，
   * 界面上却看不出任何异样。
   */
  it('同步成功时把增删改统计一起报出来', async () => {
    downstreamApi.update.mockResolvedValue({
      gateway: detail(),
      syncResult: {
        downstreamId: 'ds-1', downstreamName: 'kb_a', succeeded: true,
        added: 2, updated: 0, unchanged: 1, removed: 3, errorCode: null, errorMessage: null
      }
    })
    const view = await render()

    await view.find('.card.nested .card-body form').trigger('submit')
    await flushPromises()

    const alert = useAlerts().items[0]
    expect(alert.kind).toBe('success')
    expect(alert.title).toContain('重新同步')
    expect(alert.detail).toContain('移除 3')
  })

  /** 同步失败不是保存失败：配置已经落库，旧快照还在（需求 6.4.7）。 */
  it('同步失败时说清楚配置已保存、快照还是旧的', async () => {
    downstreamApi.update.mockResolvedValue({
      gateway: detail(),
      syncResult: {
        downstreamId: 'ds-1', downstreamName: 'kb_a', succeeded: false,
        added: 0, updated: 0, unchanged: 0, removed: 0,
        errorCode: 'DOWNSTREAM_UNREACHABLE', errorMessage: 'connection refused'
      }
    })
    const view = await render()

    await view.find('.card.nested .card-body form').trigger('submit')
    await flushPromises()

    const alert = useAlerts().items[0]
    expect(alert.kind).toBe('warning')
    expect(alert.title).toContain('已保存')
    expect(alert.detail).toContain('上一次成功同步')
  })
})

describe('新增子 MCP：表单和 JSON 两种输入方式', () => {
  /** 切到表单模式（默认就是，但用例里显式一下更清楚）。 */
  async function toFormMode(view: ReturnType<typeof mount>) {
    await view.findAll('.mode-switch button')
      .find(button => button.text().includes('表单'))!
      .trigger('click')
  }

  it('填表单也能新增，最终走的是同一个导入接口', async () => {
    const view = await render()

    await view.find('#new-ds-name').setValue('wiki')
    await view.find('#new-ds-url').setValue('https://wiki.example.com/mcp')
    await view.findAll('.header-row input')[0].setValue('Authorization')
    await view.findAll('.header-row input')[1].setValue('Bearer t0ken')
    await view.find('#import-form').trigger('submit')
    await flushPromises()

    expect(downstreamApi.import).toHaveBeenCalledWith('gw-1', {
      mcpServers: {
        wiki: {
          type: 'streamable-http',
          url: 'https://wiki.example.com/mcp',
          headers: { Authorization: 'Bearer t0ken' }
        }
      }
    })
  })

  /** 留着的空行是给下次输入用的，不该变成一个空 header 发出去。 */
  it('没填名字的 header 行会被丢掉', async () => {
    const view = await render()

    await view.find('#new-ds-name').setValue('wiki')
    await view.find('#new-ds-url').setValue('https://wiki.example.com/mcp')
    await view.find('#import-form').trigger('submit')
    await flushPromises()

    const body = downstreamApi.import.mock.calls[0][1] as
      { mcpServers: Record<string, Record<string, unknown>> }
    expect(body.mcpServers.wiki).not.toHaveProperty('headers')
  })

  it('名称或 URL 没填就不发请求', async () => {
    const view = await render()

    await view.find('#new-ds-name').setValue('wiki')
    await view.find('#import-form').trigger('submit')
    await flushPromises()

    expect(downstreamApi.import).not.toHaveBeenCalled()
    expect(useAlerts().items[0].title).toContain('都要填')
  })

  /*
   * 两种方式是同一份数据的两种视图 —— 切过去要看得到刚才填的东西，
   * 而不是一个空框。
   */
  it('表单切到 JSON 会带着刚填的内容过去', async () => {
    const view = await render()

    await view.find('#new-ds-name').setValue('wiki')
    await view.find('#new-ds-url').setValue('https://wiki.example.com/mcp')
    await toJsonMode(view)

    const json = (view.find('#import-json').element as HTMLTextAreaElement).value
    expect(JSON.parse(json)).toEqual({
      mcpServers: { wiki: { type: 'streamable-http', url: 'https://wiki.example.com/mcp' } }
    })
  })

  it('JSON 切回表单会回填', async () => {
    const view = await render()

    await toJsonMode(view)
    await view.find('#import-json').setValue(JSON.stringify({
      mcpServers: {
        wiki: {
          type: 'streamable-http', url: 'https://wiki.example.com/mcp',
          headers: { Authorization: 'Bearer t0ken' }
        }
      }
    }))
    await toFormMode(view)

    expect((view.find('#new-ds-name').element as HTMLInputElement).value).toBe('wiki')
    expect((view.find('#new-ds-url').element as HTMLInputElement).value)
      .toBe('https://wiki.example.com/mcp')
    expect((view.findAll('.header-row input')[0].element as HTMLInputElement).value)
      .toBe('Authorization')
  })

  /** 表单一次只描述一个，硬塞进去必然丢东西，不如说清楚、留在 JSON 模式。 */
  it('JSON 里有多个子 MCP 时不切过去，并说明原因', async () => {
    const view = await render()

    await toJsonMode(view)
    await view.find('#import-json').setValue(JSON.stringify({
      mcpServers: {
        a: { type: 'streamable-http', url: 'https://a.example.com/mcp' },
        b: { type: 'streamable-http', url: 'https://b.example.com/mcp' }
      }
    }))
    await toFormMode(view)

    // 还在 JSON 模式
    expect(view.find('#import-json').exists()).toBe(true)
    expect(view.find('#new-ds-name').exists()).toBe(false)
    expect(useAlerts().items[0].detail).toContain('2 个')
  })

  it('JSON 解析不了时不切过去', async () => {
    const view = await render()

    await toJsonMode(view)
    await view.find('#import-json').setValue('{ 坏掉的')
    await toFormMode(view)

    expect(view.find('#import-json').exists()).toBe(true)
    expect(useAlerts().items[0].detail).toContain('解析不了')
  })
})

describe('需求 6.2.9 / 6.4.2：导入后立即同步，单个失败不影响其他', () => {
  it('部分同步失败时报警告，但说明配置已经导入', async () => {
    downstreamApi.import.mockResolvedValue({
      gateway: detail(),
      syncResults: [
        {
          downstreamId: 'a', downstreamName: 'kb_a', succeeded: true,
          added: 2, updated: 0, unchanged: 0, removed: 0, errorCode: null, errorMessage: null
        },
        {
          downstreamId: 'b', downstreamName: 'kb_b', succeeded: false,
          added: 0, updated: 0, unchanged: 0, removed: 0,
          errorCode: 'DOWNSTREAM_INIT_FAILED', errorMessage: 'kb_b: downstream is unreachable'
        }
      ]
    })
    const view = await render()

    await toJsonMode(view)
    await view.find('#import-json').setValue('{"mcpServers":{}}')
    await view.find('#import-form').trigger('submit')
    await flushPromises()

    const alert = useAlerts().items[0]
    expect(alert.kind).toBe('warning')
    expect(alert.title).toContain('已导入')
    expect(alert.detail).toContain('kb_a：新增 2')
    expect(alert.detail).toContain('DOWNSTREAM_INIT_FAILED')
  })

  it('粘贴的 JSON 原样转发，不先过一道前端模型', async () => {
    downstreamApi.import.mockResolvedValue({ gateway: detail(), syncResults: [] })
    const view = await render()

    // 带 command 的 stdio 配置必须原样送到服务端，由它报 UNSUPPORTED_TRANSPORT；
    // 前端要是先绑一层模型，这个字段会被悄悄吃掉，用户就会看到"导入成功"
    await toJsonMode(view)
    await view.find('#import-json').setValue('{"mcpServers":{"x":{"command":"node"}}}')
    await view.find('#import-form').trigger('submit')
    await flushPromises()

    expect(downstreamApi.import).toHaveBeenCalledWith('gw-1',
      { mcpServers: { x: { command: 'node' } } })
  })

  it('JSON 格式错误时不发请求', async () => {
    const view = await render()

    await toJsonMode(view)
    await view.find('#import-json').setValue('{ 坏掉的')
    await view.find('#import-form').trigger('submit')
    await flushPromises()

    expect(downstreamApi.import).not.toHaveBeenCalled()
    expect(useAlerts().items[0].title).toBe('配置 JSON 格式错误')
  })
})

describe('破坏性变更要二次确认', () => {
  it('改 slug 会说明 Agent 的 MCP 地址会变', async () => {
    gatewayApi.update.mockResolvedValue(detail({ slug: 'renamed' }))
    const view = await render()

    await view.find('#gw-slug').setValue('renamed')
    await basicsForm(view).trigger('submit')
    await flushPromises()

    expect(vi.mocked(confirm).mock.calls.some(call => String(call[0]).includes('MCP 地址'))).toBe(true)
  })

  it('改子 MCP 名会说明所有聚合工具名都会变', async () => {
    downstreamApi.update.mockResolvedValue({ gateway: detail(), syncResult: null })
    const view = await render()

    await view.find('.card.nested input.mono').setValue('kb_renamed')
    await downstreamForm(view).trigger('submit')
    await flushPromises()

    expect(vi.mocked(confirm).mock.calls.some(call => String(call[0]).includes('聚合名'))).toBe(true)
  })
})

describe('需求 FR-05.3：轮换令牌', () => {
  it('新令牌当场显示一次，且页面不刷新', async () => {
    gatewayApi.rotateToken.mockResolvedValue({
      accessToken: 'mcpgw_rotated-token',
      mcpUrl: 'http://127.0.0.1:8080/mcp/kb-gateway'
    })
    const view = await render()

    const rotate = view.findAll('button').find(button => button.text().includes('轮换访问令牌'))
    await rotate!.trigger('click')
    await flushPromises()

    expect(view.text()).toContain('这是唯一一次完整显示该令牌')
    expect(view.find('.card.accent-warning input').attributes('value')).toBe('mcpgw_rotated-token')
    expect(useAlerts().items.some(item => item.title === '令牌已轮换')).toBe(true)
  })
})

describe('变更后不整页刷新', () => {
  it('保存基本信息后提示留得住，且用返回值直接更新状态', async () => {
    gatewayApi.update.mockResolvedValue(detail({ name: '改过的名字' }))
    const view = await render()

    await view.find('#gw-name').setValue('改过的名字')
    await basicsForm(view).trigger('submit')
    await flushPromises()

    expect(useAlerts().items.some(item => item.title === '网关已保存')).toBe(true)
    expect(view.text()).toContain('改过的名字')
    // 写接口已经返回了完整详情，不该再多取一次
    expect(gatewayApi.detail).toHaveBeenCalledTimes(1)
  })
})

describe('需求 FR-06.5：入口', () => {
  it('详情页有通往调用记录页的链接', async () => {
    const view = await render()

    const link = view.find('a[href="/ui/gateways/gw-1/calls"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toContain('调用记录')
  })
})
