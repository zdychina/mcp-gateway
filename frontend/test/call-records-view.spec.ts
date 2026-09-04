import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { CallRecordDetail, CallRecordPage, CallRecordSummary } from '../src/api/types'
import type { Router } from 'vue-router'
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
  detail: vi.fn(),
  exportFile: vi.fn()
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
    extracted: {},
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
  // 列配置存在 localStorage 里，jsdom 的存储跨用例是共享的
  window.localStorage.clear()
  callRecordApi.search.mockResolvedValue(page())
  callRecordApi.detail.mockResolvedValue(detail())
  callRecordApi.exportFile.mockResolvedValue({
    blob: new Blob(['x']),
    headers: new Headers({ 'X-Export-Rows': '1', 'X-Export-Total': '1', 'X-Export-Truncated': 'false' })
  })
  gatewayApi.detail.mockResolvedValue({
    id: 'gw-1',
    name: '知识库网关',
    slug: 'kb',
    downstreams: [{ id: 'ds-1', name: 'kb_a' }]
  })
  useAlerts().clear()
})

/** 当前这次 render 用的 router，筛选条件是否落到 URL 上要靠它断言。 */
let router: Router

async function render(path = '/gateways/gw-1/calls') {
  router = createTestRouter()
  await router.push(path)
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

    await view.find('td.col-traceId .linkish').trigger('click')
    await flushPromises()

    expect(lastFilters().traceId).toBe('trace-1')
  })

  it('点聚合工具名就只看这个工具的调用', async () => {
    const view = await render()

    await view.find('td.col-exposedToolName .linkish').trigger('click')
    await flushPromises()

    expect(lastFilters().toolName).toBe('kb_a__search')
  })

  it('本地时间转成 ISO instant 再发给服务端', async () => {
    const view = await render()

    await view.find('#f-from').setValue('2026-08-31T10:00')
    await view.find('form.filters-form').trigger('submit')
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
    await view.find('form.filters-form').trigger('submit')
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

    expect((view.find('.pager-prev').element as HTMLButtonElement).disabled).toBe(true)
    expect((view.find('.pager-next').element as HTMLButtonElement).disabled).toBe(false)

    callRecordApi.search.mockResolvedValue(page({ items: [summary()], page: 1, size: 20, total: 25 }))
    await view.find('.pager-next').trigger('click')
    await flushPromises()

    expect((view.find('.pager-prev').element as HTMLButtonElement).disabled).toBe(false)
    expect((view.find('.pager-next').element as HTMLButtonElement).disabled).toBe(true)
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

    await view.find('.pager-next').trigger('click')
    await flushPromises()
    expect(lastFilters().page).toBe(1)

    await view.find('#f-tool').setValue('ping')
    await view.find('form.filters-form').trigger('submit')
    await flushPromises()

    expect(lastFilters().page).toBe(0)
  })
})

describe('筛选条件落在 URL 上', () => {
  it('筛完之后地址栏带得上条件 —— 排障的产物就是一个能贴给别人的链接', async () => {
    const view = await render()

    await view.find('#f-tool').setValue('kb_a__')
    await view.find('form.filters-form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.query.tool).toBe('kb_a__')
  })

  it('带条件的链接直接打开就是筛过的结果', async () => {
    await render('/gateways/gw-1/calls?tool=kb_a__&status=ERROR&page=2&size=50')

    expect(lastFilters()).toMatchObject({
      toolName: 'kb_a__', status: 'ERROR', page: 2, size: 50
    })
  })

  it('URL 上的垃圾值退回默认，而不是原样发给服务端', async () => {
    await render('/gateways/gw-1/calls?status=NOPE&size=999&page=-3')

    expect(lastFilters().status).toBeUndefined()
    expect(lastFilters().size).toBe(20)
    expect(lastFilters().page).toBe(0)
  })
})

describe('时间窗与生效中的条件', () => {
  it('快捷键按"最近多久"算出 from —— 排障问的是刚才，不是某个具体时刻', async () => {
    const view = await render()

    const oneHour = view.findAll('.chip-btn').find(button => button.text() === '最近 1 小时')!
    await oneHour.trigger('click')
    await flushPromises()

    const from = new Date(lastFilters().from!).getTime()
    expect(Math.abs(Date.now() - 3600_000 - from)).toBeLessThan(5000)
    expect(lastFilters().to).toBeUndefined()
  })

  it('生效中的条件逐条列出，也能逐条撤销', async () => {
    const view = await render()

    await view.find('#f-tool').setValue('kb_a__')
    await view.find('#f-trace').setValue('trace-x')
    await view.find('form.filters-form').trigger('submit')
    await flushPromises()

    const chips = view.findAll('.chip')
    expect(chips).toHaveLength(2)
    expect(view.find('.chip-bar').text()).toContain('kb_a__')

    await chips[0].find('.chip-x').trigger('click')
    await flushPromises()

    // 撤掉工具名，trace 还留着
    expect(lastFilters().toolName).toBeUndefined()
    expect(lastFilters().traceId).toBe('trace-x')
  })

  it('没有任何条件时不显示那一条', async () => {
    const view = await render()

    expect(view.find('.chip-bar').exists()).toBe(false)
  })
})

describe('刷新', () => {
  it('手动刷新会重新取一次，即使条件一个字都没改', async () => {
    const view = await render()
    expect(callRecordApi.search).toHaveBeenCalledTimes(1)

    await view.findAll('.page-head button').at(0)!.trigger('click')
    await flushPromises()

    expect(callRecordApi.search).toHaveBeenCalledTimes(2)
  })

  it('自动刷新按选定的间隔重取；关掉就停', async () => {
    vi.useFakeTimers()
    try {
      const view = await render()
      await view.find('.page-head select').setValue('10')

      await vi.advanceTimersByTimeAsync(10_000)
      expect(callRecordApi.search).toHaveBeenCalledTimes(2)

      await view.find('.page-head select').setValue('0')
      await vi.advanceTimersByTimeAsync(60_000)
      expect(callRecordApi.search).toHaveBeenCalledTimes(2)
    }
    finally {
      vi.useRealTimers()
    }
  })

  it('自动刷新失败就自己停下来，不然错误提示会按秒往外冒', async () => {
    vi.useFakeTimers()
    try {
      const view = await render()
      await view.find('.page-head select').setValue('10')

      callRecordApi.search.mockRejectedValue(new ApiError('INTERNAL_ERROR', 'internal error'))
      await vi.advanceTimersByTimeAsync(10_000)
      await vi.advanceTimersByTimeAsync(60_000)

      expect(callRecordApi.search).toHaveBeenCalledTimes(2)
      expect(useAlerts().items[0].title).toBe('自动刷新已停止')
      // 停下来是为了不刷屏，已经显示出来的结果不该被清掉
      expect(view.find('table.records').exists()).toBe(true)
    }
    finally {
      vi.useRealTimers()
    }
  })
})

describe('每页条数', () => {
  it('可以调到 50 / 100 —— 后端上限就是 100', async () => {
    const view = await render()

    await view.find('.page-size select').setValue('50')
    await flushPromises()

    expect(lastFilters().size).toBe(50)
    expect(view.findAll('.page-size option').map(option => option.text()))
      .toEqual(['20', '50', '100'])
  })

  it('下拉框反映 URL 上的条数，而不是永远停在 20', async () => {
    const view = await render('/gateways/gw-1/calls?size=100')

    expect((view.find('.page-size select').element as HTMLSelectElement).value).toBe('100')
  })
})

describe('列配置', () => {
  /** 列菜单里某一列的那一行。 */
  function row(view: ReturnType<typeof mount>, label: string) {
    return view.findAll('.col-list li').find(item => item.text().includes(label))!
  }

  it('默认列和这个页面原来的样子一致', async () => {
    const view = await render()

    expect(view.findAll('thead th').map(th => th.text()))
      .toEqual(['展开', '开始时间', '聚合工具', '子 MCP', '状态', '耗时', '错误码', '错误摘要', 'trace_id'])
  })

  it('关掉一列，表头和单元格一起消失', async () => {
    const view = await render()

    await row(view, 'trace_id').find('input[type="checkbox"]').trigger('change')

    expect(view.findAll('thead th').map(th => th.text())).not.toContain('trace_id')
    expect(view.find('td.col-traceId').exists()).toBe(false)
    // 详情行的 colspan 跟着列数走，否则展开一条会把表格撑歪
    await view.find('tr.record').trigger('click')
    await flushPromises()
    expect(view.find('.detail-row td').attributes('colspan')).toBe('8')
  })

  it('打开一列默认没显示的字段', async () => {
    const view = await render()

    await row(view, 'call_id').find('input[type="checkbox"]').trigger('change')

    expect(view.findAll('thead th').map(th => th.text())).toContain('call_id')
  })

  it('上下移动改变列顺序', async () => {
    const view = await render()

    // 「子 MCP」上移一格，排到「聚合工具」前面
    await row(view, '子 MCP').findAll('.icon-btn')[0].trigger('click')

    expect(view.findAll('thead th').map(th => th.text()).slice(1, 4))
      .toEqual(['开始时间', '子 MCP', '聚合工具'])
  })

  it('列配置按网关存下来，重新打开还在', async () => {
    let view = await render()
    await row(view, 'call_id').find('input[type="checkbox"]').trigger('change')

    view = await render()

    expect(view.findAll('thead th').map(th => th.text())).toContain('call_id')
    // 换个网关不套用另一个网关的列 —— 抽取路径是跟着工具参数结构走的
    view = await render('/gateways/gw-2/calls')
    expect(view.findAll('thead th').map(th => th.text())).not.toContain('call_id')
  })

  it('恢复默认清掉全部改动', async () => {
    const view = await render()
    await row(view, 'call_id').find('input[type="checkbox"]').trigger('change')

    await view.find('.col-panel-head .chip-btn').trigger('click')

    expect(view.findAll('thead th').map(th => th.text())).not.toContain('call_id')
  })
})

describe('正文抽取列', () => {
  /** 填「加一列正文字段」的表单并提交。 */
  async function addColumn(view: ReturnType<typeof mount>, source: string, pointer: string, label: string) {
    await view.find('.col-add select').setValue(source)
    await view.find('.col-add input.mono').setValue(pointer)
    await view.find('.col-add input:not(.mono)').setValue(label)
    await view.find('.col-add').trigger('submit')
    await flushPromises()
  }

  it('加一列就把 extract 参数带上，值由服务端抽', async () => {
    const view = await render()

    await addColumn(view, 'response', '/content/0/text', '返回首段')

    expect(lastFilters().extract).toEqual(['response:/content/0/text'])
    expect(view.findAll('thead th').map(th => th.text())).toContain('返回首段')
  })

  it('渲染抽到的值，截断的标出来', async () => {
    callRecordApi.search.mockResolvedValue(page({
      items: [summary({
        extracted: { 'request:/q': { value: '制度条款在哪', state: 'OK', truncated: false } }
      })]
    }))
    const view = await render()

    await addColumn(view, 'request', '/q', '查询词')

    expect(view.find('td.col-extract').text()).toContain('制度条款在哪')
  })

  it('抽不到时说明是哪一种抽不到 —— 路径不对和正文过大要做的事不一样', async () => {
    callRecordApi.search.mockResolvedValue(page({
      items: [summary({
        extracted: { 'request:/q': { value: null, state: 'TOO_LARGE', truncated: false } }
      })]
    }))
    const view = await render()

    await addColumn(view, 'request', '/q', '查询词')

    expect(view.find('td.col-extract').text()).toBe('正文过大')
  })

  it('关掉这一列就不再请求抽取', async () => {
    const view = await render()
    await addColumn(view, 'request', '/q', '查询词')
    expect(lastFilters().extract).toEqual(['request:/q'])

    const columnRow = view.findAll('.col-list li').find(item => item.text().includes('查询词'))!
    await columnRow.find('input[type="checkbox"]').trigger('change')
    await flushPromises()

    expect(lastFilters().extract).toBeUndefined()
  })

  it('路径写错在前端就拦下来，不白跑一次 400', async () => {
    const view = await render()
    const before = callRecordApi.search.mock.calls.length

    // 最常见的手误：把 JSON Pointer 写成点号路径
    await addColumn(view, 'request', 'q', '查询词')

    expect(view.find('.col-add .hint.warn').text()).toContain('/')
    expect(callRecordApi.search.mock.calls).toHaveLength(before)
  })

  it('最多四列 —— 和服务端的上限对齐', async () => {
    const view = await render()

    for (const field of ['/a', '/b', '/c', '/d']) {
      await addColumn(view, 'request', field, field)
    }
    await addColumn(view, 'request', '/e', '第五个')

    expect(view.find('.col-add .hint.warn').text()).toContain('4')
    expect(lastFilters().extract).toHaveLength(4)
  })
})

describe('跳页', () => {
  it('列出页码，点一下就过去', async () => {
    callRecordApi.search.mockResolvedValue(page({ page: 0, size: 20, total: 100 }))
    const view = await render()

    expect(view.findAll('.page-btn').map(button => button.text()))
      .toEqual(['1', '2', '3', '5'])

    await view.findAll('.page-btn')[2].trigger('click')
    await flushPromises()

    expect(lastFilters().page).toBe(2)
  })

  it('当前页标出来，也告诉读屏软件', async () => {
    callRecordApi.search.mockResolvedValue(page({ page: 2, size: 20, total: 100 }))
    const view = await render()

    const current = view.find('.page-btn.current')
    expect(current.text()).toBe('3')
    expect(current.attributes('aria-current')).toBe('page')
  })

  /*
   * 页码按钮个数保持恒定：翻到第 2 页时整排按钮左右跳一下，鼠标就落空了。
   */
  it('页数多时中间省略，按钮个数不随当前页跳动', async () => {
    callRecordApi.search.mockResolvedValue(page({ page: 0, size: 20, total: 2000 }))
    let view = await render()
    const atStart = view.findAll('.page-btn').map(button => button.text())
    expect(atStart).toEqual(['1', '2', '3', '100'])
    expect(view.findAll('.page-gap')).toHaveLength(1)

    callRecordApi.search.mockResolvedValue(page({ page: 49, size: 20, total: 2000 }))
    view = await render('/gateways/gw-1/calls?page=49')
    expect(view.findAll('.page-btn').map(button => button.text()))
      .toEqual(['1', '49', '50', '51', '100'])
    expect(view.findAll('.page-gap')).toHaveLength(2)
  })

  it('页码没被省略时不给跳转框 —— 想去第 3 页点一下就是了', async () => {
    callRecordApi.search.mockResolvedValue(page({ page: 0, size: 20, total: 60 }))
    const view = await render()

    expect(view.findAll('.page-btn')).toHaveLength(3)
    expect(view.find('.pager-jump').exists()).toBe(false)
  })

  it('页数多了给跳转框，填几就去第几页', async () => {
    callRecordApi.search.mockResolvedValue(page({ page: 0, size: 20, total: 2000 }))
    const view = await render()

    await view.find('.pager-jump input').setValue('42')
    await view.find('.pager-jump').trigger('submit')
    await flushPromises()

    expect(lastFilters().page).toBe(41)
    // 跳完清空，免得下次跳转还得先删掉上一次的数字
    expect((view.find('.pager-jump input').element as HTMLInputElement).value).toBe('')
  })

  /*
   * 越界收敛到边界，而不是照发给服务端 ——
   * 服务端会老实返回空列表，而空列表把分页栏一起换成"没有符合条件的记录"，人就困住了。
   */
  it('跳到范围外收敛到首页或末页，不会把人甩到一个空列表上', async () => {
    callRecordApi.search.mockResolvedValue(page({ page: 0, size: 20, total: 2000 }))
    const view = await render()

    await view.find('.pager-jump input').setValue('9999')
    await view.find('.pager-jump').trigger('submit')
    await flushPromises()
    expect(lastFilters().page).toBe(99)

    await view.find('.pager-jump input').setValue('0')
    await view.find('.pager-jump').trigger('submit')
    await flushPromises()
    expect(lastFilters().page).toBe(0)
  })

  it('跳转框留空就什么都不做', async () => {
    callRecordApi.search.mockResolvedValue(page({ page: 3, size: 20, total: 2000 }))
    const view = await render('/gateways/gw-1/calls?page=3')
    const before = callRecordApi.search.mock.calls.length

    await view.find('.pager-jump').trigger('submit')
    await flushPromises()

    expect(callRecordApi.search.mock.calls).toHaveLength(before)
  })
})

describe('导出 Excel', () => {
  /** jsdom 没有 createObjectURL，也不该真的去下载文件。 */
  function stubDownload() {
    const clicked: string[] = []
    const createObjectURL = vi.fn(() => 'blob:preview')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(function (this: HTMLAnchorElement) {
        clicked.push(this.download)
      })
    return { clicked, click, createObjectURL }
  }

  function exportButton(view: ReturnType<typeof mount>) {
    return view.findAll('.page-head button').find(button => button.text().includes('导出'))!
  }

  it('导的是筛选后的全部，不是当前这一页', async () => {
    callRecordApi.search.mockResolvedValue(page({ total: 137, page: 1, size: 20 }))
    const stub = stubDownload()
    const view = await render('/gateways/gw-1/calls?tool=kb_a__&status=ERROR&page=1')

    await exportButton(view).trigger('click')
    await flushPromises()

    const [gateway, filters] = callRecordApi.exportFile.mock.calls[0]
    expect(gateway).toBe('gw-1')
    expect(filters).toMatchObject({ toolName: 'kb_a__', status: 'ERROR' })
    // 分页参数不发 —— 翻到第几页跟导出无关
    expect(filters).not.toHaveProperty('page')
    expect(stub.clicked[0]).toMatch(/^调用记录-kb-\d{6}\.xlsx$/)
    stub.click.mockRestore()
  })

  it('列和表头跟着页面上的列配置走', async () => {
    const stub = stubDownload()
    const view = await render()

    await exportButton(view).trigger('click')
    await flushPromises()

    const [, , columns, labels] = callRecordApi.exportFile.mock.calls[0]
    expect(columns).toEqual(['startedAt', 'exposedToolName', 'downstream', 'status',
      'durationMs', 'errorCode', 'errorMessage', 'traceId'])
    expect(labels[0]).toBe('开始时间')
    expect(columns).toHaveLength(labels.length)
    stub.click.mockRestore()
  })

  it('一条都没有就不发请求', async () => {
    callRecordApi.search.mockResolvedValue(page({ items: [], total: 0, statusCounts: { SUCCESS: 0 } }))
    const view = await render()

    // 空结果时列表被空状态替换，导出按钮仍在页头
    await exportButton(view).trigger('click')
    await flushPromises()

    expect(callRecordApi.exportFile).not.toHaveBeenCalled()
    expect(useAlerts().items[0].title).toBe('没有可导出的记录')
  })

  it('被上限截断时明说，不让人以为文件是全的', async () => {
    callRecordApi.exportFile.mockResolvedValue({
      blob: new Blob(['x']),
      headers: new Headers({
        'X-Export-Rows': '5000', 'X-Export-Total': '12345', 'X-Export-Truncated': 'true'
      })
    })
    const stub = stubDownload()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    callRecordApi.search.mockResolvedValue(page({ total: 12345 }))
    const view = await render()

    await exportButton(view).trigger('click')
    await flushPromises()

    // 超上限先问一句，别让人白等一个不完整的文件
    expect(confirmSpy).toHaveBeenCalled()
    const alert = useAlerts().items[0]
    expect(alert.kind).toBe('warning')
    expect(alert.title).toContain('5000')
    expect(alert.detail).toContain('12345')
    confirmSpy.mockRestore()
    stub.click.mockRestore()
  })

  it('超上限时点取消就真的不导', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    callRecordApi.search.mockResolvedValue(page({ total: 12345 }))
    const view = await render()

    await exportButton(view).trigger('click')
    await flushPromises()

    expect(callRecordApi.exportFile).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })

  it('导出失败给出错误，不是一个空文件', async () => {
    callRecordApi.exportFile.mockRejectedValue(new ApiError('INVALID_REQUEST', 'unknown column: x'))
    const view = await render()

    await exportButton(view).trigger('click')
    await flushPromises()

    expect(useAlerts().items[0].title).toBe('导出失败')
    expect(useAlerts().items[0].detail).toContain('unknown column')
  })
})
