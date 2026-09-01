import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { CallRecordDetail, CallRecordPage, CallRecordSummary } from '../src/api/types'
import { createTestRouter } from './support/router'

/*
 * 需求 FR-06.5：调用记录查询页。
 *
 * 重点盯两条跟着后端接口来的设计约束：
 * - 列表不含入参和返回正文，只有展开某一行时才按 callId 取单条
 *   （那两个字段各可能接近 1 MiB，装的是知识库正文）
 * - statusCounts 不套用 status 筛选，所以能拿它做分面切换
 */
const callRecordApi = vi.hoisted(() => ({
  search: vi.fn(),
  detail: vi.fn()
}))
const gatewayApi = vi.hoisted(() => ({ detail: vi.fn() }))

vi.mock('../src/api/gateways', () => ({ callRecordApi, gatewayApi }))

const { useAlerts } = await import('../src/composables/useAlerts')
const { ApiError } = await import('../src/api/client')
const CallRecordsView = (await import('../src/views/CallRecordsView.vue')).default

// ------------------------------------------------------------------ 夹具

function summary(overrides: Partial<CallRecordSummary> = {}): CallRecordSummary {
  return {
    callId: 'call-1',
    traceId: 'trace-1',
    downstreamMcpId: 'ds-1',
    exposedToolName: 'kb_a__search',
    originalToolName: 'search',
    status: 'SUCCESS',
    errorCode: null,
    errorMessage: null,
    startedAt: '2026-08-31T09:00:00Z',
    finishedAt: '2026-08-31T09:00:00.120Z',
    durationMs: 120,
    ...overrides
  }
}

function page(overrides: Partial<CallRecordPage> = {}): CallRecordPage {
  return {
    items: [summary()],
    page: 0,
    size: 20,
    total: 1,
    statusCounts: { SUCCESS: 1 },
    ...overrides
  }
}

function detail(overrides: Partial<CallRecordDetail> = {}): CallRecordDetail {
  return {
    ...summary(),
    gatewayId: 'gw-1',
    requestJson: '{"q":"制度条款在哪"}',
    responseJson: '{"content":[{"text":"第 3 章"}]}',
    ...overrides
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  callRecordApi.search.mockResolvedValue(page())
  callRecordApi.detail.mockResolvedValue(detail())
  gatewayApi.detail.mockResolvedValue({
    id: 'gw-1',
    name: '知识库网关',
    slug: 'kb',
    downstreams: [{ id: 'ds-1', name: 'kb_a' }]
  })
  useAlerts().clear()
})

async function render() {
  const router = createTestRouter()
  await router.push('/gateways/gw-1/calls')
  await router.isReady()
  const view = mount(CallRecordsView, { global: { plugins: [router] } })
  await flushPromises()
  return view
}

/** 最近一次 search 调用带的筛选条件。 */
function lastFilters() {
  const calls = callRecordApi.search.mock.calls
  return calls[calls.length - 1][1]
}

// ------------------------------------------------------------------ 用例

describe('需求 FR-06.5：调用记录列表', () => {
  it('展示时间、工具、状态、耗时和 trace_id', async () => {
    const view = await render()
    const text = view.text()

    expect(text).toContain('kb_a__search')
    expect(text).toContain('SUCCESS')
    expect(text).toContain('120 ms')
    expect(text).toContain('trace-1')
  })

  it('耗时过秒后换成秒显示', async () => {
    callRecordApi.search.mockResolvedValue(page({ items: [summary({ durationMs: 2500 })] }))
    const view = await render()

    expect(view.text()).toContain('2.50 s')
  })

  it('没有路由目标的记录标出来 —— 未知工具和停用工具的调用同样要留痕', async () => {
    callRecordApi.search.mockResolvedValue(page({
      items: [summary({
        downstreamMcpId: null, originalToolName: null,
        exposedToolName: 'no_such_tool', status: 'ERROR', errorCode: 'TOOL_NOT_FOUND'
      })]
    }))
    const view = await render()

    expect(view.text()).toContain('无路由目标')
    expect(view.text()).toContain('TOOL_NOT_FOUND')
  })

  it('区分「一条记录都没有」和「筛不出来」', async () => {
    callRecordApi.search.mockResolvedValue(page({ items: [], total: 0, statusCounts: {} }))
    let view = await render()
    expect(view.text()).toContain('还没有任何调用记录')

    // 有记录但当前筛选条件筛不出来，是另一回事
    callRecordApi.search.mockResolvedValue(
      page({ items: [], total: 0, statusCounts: { SUCCESS: 7 } }))
    view = await render()
    expect(view.text()).toContain('没有符合当前筛选条件的记录')
  })

  it('加载失败时给出重试入口', async () => {
    callRecordApi.search.mockRejectedValue(new ApiError('INTERNAL_ERROR', 'internal error'))
    const view = await render()

    expect(view.text()).toContain('重试')
    expect(useAlerts().items[0].title).toBe('加载调用记录失败')
  })
})

describe('列表不带正文，展开才取单条', () => {
  it('渲染列表时不会去取任何一条详情', async () => {
    callRecordApi.search.mockResolvedValue(page({
      items: [summary({ callId: 'a' }), summary({ callId: 'b' }), summary({ callId: 'c' })],
      total: 3
    }))
    await render()

    // 一次都不该调 —— 否则等于把整页的知识库正文都拉下来了
    expect(callRecordApi.detail).not.toHaveBeenCalled()
  })

  it('点开某一行才取它的入参和返回，并缩进展示', async () => {
    const view = await render()

    await view.find('tr.record').trigger('click')
    await flushPromises()

    expect(callRecordApi.detail).toHaveBeenCalledWith('gw-1', 'call-1')
    const shown = view.find('.detail-panel').text()
    expect(shown).toContain('制度条款在哪')
    expect(shown).toContain('第 3 章')
    // JSON 缩进过
    expect(view.findAll('.json-block')[0].text()).toContain('\n  ')
  })

  it('来回折叠不重复请求', async () => {
    const view = await render()
    const row = view.find('tr.record')

    await row.trigger('click')
    await flushPromises()
    await row.trigger('click')
    await flushPromises()
    await row.trigger('click')
    await flushPromises()

    expect(callRecordApi.detail).toHaveBeenCalledTimes(1)
  })

  it('正文不是合法 JSON 时原样显示，而不是什么都不给', async () => {
    // 打点时序列化失败会写这个占位符；历史遗留的坏数据也可能解析不了
    callRecordApi.detail.mockResolvedValue(detail({
      requestJson: '{"_unserializable":true}',
      responseJson: '这不是 JSON'
    }))
    const view = await render()

    await view.find('tr.record').trigger('click')
    await flushPromises()

    const blocks = view.findAll('.json-block')
    expect(blocks[0].text()).toContain('_unserializable')
    expect(blocks[1].text()).toContain('这不是 JSON')
  })

  it('空的入参或返回显示占位符', async () => {
    callRecordApi.detail.mockResolvedValue(detail({ responseJson: null }))
    const view = await render()

    await view.find('tr.record').trigger('click')
    await flushPromises()

    expect(view.findAll('.json-block')[1].text()).toBe('（无）')
  })
})

describe('筛选', () => {
  it('状态分面用 statusCounts 的数字，点一下切换筛选', async () => {
    callRecordApi.search.mockResolvedValue(page({
      statusCounts: { SUCCESS: 12, ERROR: 3, TIMEOUT: 1 }, total: 16
    }))
    const view = await render()

    const facets = view.findAll('.facet')
    expect(facets[0].text()).toContain('16')      // 全部 = 各状态之和
    expect(facets[1].text()).toContain('SUCCESS')
    expect(facets[1].text()).toContain('12')

    await facets[2].trigger('click')              // ERROR
    await flushPromises()

    expect(lastFilters().status).toBe('ERROR')
  })

  it('再点一次同一个分面就取消筛选', async () => {
    const view = await render()

    await view.findAll('.facet')[1].trigger('click')
    await flushPromises()
    expect(lastFilters().status).toBe('SUCCESS')

    await view.findAll('.facet')[1].trigger('click')
    await flushPromises()
    expect(lastFilters().status).toBeUndefined()
  })

  it('点 trace_id 就按那条链路筛选（需求 15.4.3）', async () => {
    const view = await render()

    await view.find('td .linkish').trigger('click')
    await flushPromises()

    expect(lastFilters().traceId).toBe('trace-1')
  })

  it('本地时间转成 ISO instant 再发给服务端', async () => {
    const view = await render()

    await view.find('#f-from').setValue('2026-08-31T10:00')
    await view.find('form').trigger('submit')
    await flushPromises()

    // datetime-local 没有时区，浏览器按本地时间理解，发出去必须是 instant
    expect(lastFilters().from).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/)
    expect(new Date(lastFilters().from!).getHours()).toBe(10)
  })

  it('子 MCP 下拉来自网关详情', async () => {
    const view = await render()

    const options = view.findAll('#f-downstream option')
    expect(options).toHaveLength(2)
    expect(options[1].text()).toBe('kb_a')
  })

  it('重置清掉全部条件', async () => {
    const view = await render()

    await view.find('#f-tool').setValue('kb_a__')
    await view.find('#f-trace').setValue('trace-x')
    await view.find('form').trigger('submit')
    await flushPromises()
    expect(lastFilters().toolName).toBe('kb_a__')

    await view.findAll('.btn-link').at(0)!.trigger('click')
    await flushPromises()

    expect(lastFilters().toolName).toBeUndefined()
    expect(lastFilters().traceId).toBeUndefined()
  })
})

describe('分页', () => {
  it('首页禁用上一页，末页禁用下一页', async () => {
    callRecordApi.search.mockResolvedValue(page({ items: [summary()], page: 0, size: 20, total: 25 }))
    const view = await render()

    const buttons = view.findAll('.pager button')
    expect((buttons[0].element as HTMLButtonElement).disabled).toBe(true)
    expect((buttons[1].element as HTMLButtonElement).disabled).toBe(false)

    callRecordApi.search.mockResolvedValue(page({ items: [summary()], page: 1, size: 20, total: 25 }))
    await buttons[1].trigger('click')
    await flushPromises()

    const after = view.findAll('.pager button')
    expect((after[0].element as HTMLButtonElement).disabled).toBe(false)
    expect((after[1].element as HTMLButtonElement).disabled).toBe(true)
  })

  it('说明当前看的是第几条到第几条', async () => {
    callRecordApi.search.mockResolvedValue(page({
      items: [summary({ callId: 'x' }), summary({ callId: 'y' })],
      page: 2, size: 2, total: 7
    }))
    const view = await render()

    expect(view.text()).toContain('第 5–6 条，共 7 条')
  })

  it('改筛选条件回到第一页 —— 停在第 5 页看一个两页的结果集会让人以为没数据', async () => {
    callRecordApi.search.mockResolvedValue(page({ items: [summary()], page: 0, size: 20, total: 100 }))
    const view = await render()

    await view.findAll('.pager button')[1].trigger('click')
    await flushPromises()
    expect(lastFilters().page).toBe(1)

    await view.find('#f-tool').setValue('ping')
    await view.find('form').trigger('submit')
    await flushPromises()

    expect(lastFilters().page).toBe(0)
  })
})
