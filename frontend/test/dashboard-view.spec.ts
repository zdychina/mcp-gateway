import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { Stats } from '../src/api/types'
import { createTestRouter } from './support/router'

/*
 * 总览页。
 *
 * 图表本身不在测试范围里（ECharts 被整体替换掉了）—— 值得钉住的是它周围那些会出错的
 * 判断：时间窗算得对不对、切网关有没有重新查、没有数据时说的是什么、
 * 以及图表之外那条给读屏软件和排查用的表格路径还在不在。
 */
const statsApi = vi.hoisted(() => ({ load: vi.fn() }))
vi.mock('../src/api/stats', () => ({ statsApi }))

/*
 * ECharts 在 jsdom 里跑不起来，也不是这里要验的东西。
 * 换成一个空壳，只保留组件契约：init 出来的对象要能 setOption / resize / dispose。
 */
const chartStub = vi.hoisted(() => ({
  setOption: vi.fn(),
  resize: vi.fn(),
  dispose: vi.fn(),
  on: vi.fn()
}))
vi.mock('echarts/core', () => ({ use: vi.fn(), init: vi.fn(() => chartStub) }))
vi.mock('echarts/charts', () => ({ BarChart: {} }))
vi.mock('echarts/components', () => ({
  GridComponent: {}, LegendComponent: {}, TooltipComponent: {}
}))
vi.mock('echarts/renderers', () => ({ SVGRenderer: {} }))

const { useAlerts } = await import('../src/composables/useAlerts')
const { ApiError } = await import('../src/api/client')
const DashboardView = (await import('../src/views/DashboardView.vue')).default
const EChart = (await import('../src/components/EChart.vue')).default

// ------------------------------------------------------------------ 夹具

function stats(overrides: Partial<Stats> = {}): Stats {
  return {
    from: '2026-09-05T00:00:00Z',
    to: '2026-09-06T00:00:00Z',
    bucket: 'HOUR',
    totals: {
      calls: 1227, success: 1111, error: 72, timeout: 43, started: 1,
      successRate: 0.9054, avgDurationMs: 210.4, p95DurationMs: 30000
    },
    series: [
      { at: '2026-09-05T22:00:00Z', total: 5, success: 4, error: 1, timeout: 0 },
      { at: '2026-09-05T23:00:00Z', total: 0, success: 0, error: 0, timeout: 0 },
      { at: '2026-09-06T00:00:00Z', total: 7, success: 6, error: 0, timeout: 1 }
    ],
    gateways: [
      {
        gatewayId: 'gw-1', name: '知识库网关', slug: 'kb', status: 'READY',
        calls: 1200, failures: 100, successRate: 0.9167, avgDurationMs: 200, p95DurationMs: 900
      },
      {
        gatewayId: 'gw-2', name: '闲置网关', slug: 'idle', status: 'EMPTY',
        calls: 0, failures: 0, successRate: null, avgDurationMs: null, p95DurationMs: null
      }
    ],
    downstreams: [],
    tools: [
      {
        id: 'kb_a__search', name: 'kb_a__search', calls: 800, failures: 12,
        successRate: 0.985, avgDurationMs: 180, p95DurationMs: 700
      }
    ],
    errorCodes: [{ code: 'DOWNSTREAM_TIMEOUT', count: 43 }],
    ...overrides
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  statsApi.load.mockResolvedValue(stats())
  useAlerts().clear()
})

async function render() {
  const router = createTestRouter()
  await router.push('/dashboard')
  await router.isReady()
  const view = mount(DashboardView, { global: { plugins: [router] } })
  await flushPromises()
  return view
}

/** 最近一次请求带的筛选条件。 */
function lastFilters() {
  const calls = statsApi.load.mock.calls
  return calls[calls.length - 1][0]
}

// ------------------------------------------------------------------ 用例

describe('总览：概览数字', () => {
  it('默认查最近 24 小时，不带网关', async () => {
    await render()

    const filters = lastFilters()
    expect(filters.gatewayId).toBeUndefined()
    const hoursBack = (Date.now() - new Date(filters.from!).getTime()) / 3600_000
    expect(hoursBack).toBeCloseTo(24, 1)
  })

  it('四个数字：调用量、成功率、失败、P95', async () => {
    const view = await render()
    const tiles = view.findAll('.stat-tile')

    expect(tiles[0].text()).toContain('1,227')
    expect(tiles[1].text()).toContain('90.5%')
    // 失败是错误和超时之和，两个拆项也要给出来
    expect(tiles[2].text()).toContain('115')
    expect(tiles[2].text()).toContain('错误 72')
    expect(tiles[2].text()).toContain('超时 43')
    expect(tiles[3].text()).toContain('30.00 s')
  })

  /*
   * 成功率 null 是"这段时间没有调用"，不是"全失败了"。
   * 画成 0% 会让人以为出了大事。
   */
  it('没有调用时成功率是破折号，并给出空状态', async () => {
    statsApi.load.mockResolvedValue(stats({
      totals: {
        calls: 0, success: 0, error: 0, timeout: 0, started: 0,
        successRate: null, avgDurationMs: null, p95DurationMs: null
      },
      series: [], tools: [], errorCodes: []
    }))
    const view = await render()

    expect(view.findAll('.stat-tile')[1].text()).toContain('—')
    expect(view.text()).toContain('这段时间没有调用')
  })

  it('加载失败给重试入口', async () => {
    statsApi.load.mockRejectedValue(new ApiError('INTERNAL_ERROR', 'internal error'))
    const view = await render()

    expect(view.text()).toContain('重试')
    expect(useAlerts().items[0].title).toBe('加载统计失败')
  })
})

describe('总览：时间窗与网关', () => {
  it('切时间范围就换一个窗口重查', async () => {
    const view = await render()

    const sevenDays = view.findAll('.chip-btn').find(button => button.text() === '7 天')!
    await sevenDays.trigger('click')
    await flushPromises()

    const daysBack = (Date.now() - new Date(lastFilters().from!).getTime()) / 86400_000
    expect(daysBack).toBeCloseTo(7, 1)
  })

  /*
   * 连着切两次时间范围，慢的那个后回来不能盖掉新的 ——
   * 否则标题写着 24 小时、数据却是 1 小时的，而且看不出哪里不对。
   */
  it('慢的那个响应后回来也盖不掉新的', async () => {
    const view = await render()

    let resolveSlow: (() => void) | null = null
    statsApi.load.mockImplementationOnce(() => new Promise((resolve) => {
      resolveSlow = () => resolve(stats({
        totals: {
          calls: 1, success: 1, error: 0, timeout: 0, started: 0,
          successRate: 1, avgDurationMs: 10, p95DurationMs: 10
        }
      }))
    }))
    statsApi.load.mockResolvedValueOnce(stats({
      totals: {
        calls: 999, success: 999, error: 0, timeout: 0, started: 0,
        successRate: 1, avgDurationMs: 20, p95DurationMs: 20
      }
    }))

    // 先点慢的那个，再点快的那个
    await view.findAll('.chip-btn').find(button => button.text() === '1 小时')!.trigger('click')
    await view.findAll('.chip-btn').find(button => button.text() === '7 天')!.trigger('click')
    await flushPromises()

    // 快的那个已经画上去了
    expect(view.findAll('.stat-tile')[0].text()).toContain('999')

    // 慢的那个这时才回来，必须被丢掉
    resolveSlow!()
    await flushPromises()

    expect(view.findAll('.stat-tile')[0].text()).toContain('999')
    expect(view.findAll('.stat-tile')[0].text()).not.toContain('最近 1 小时')
  })

  it('网关下拉有无障碍名称 —— 原生元素上得写 aria-label', async () => {
    const view = await render()

    expect(view.find('.scope-picker select').attributes('aria-label')).toBe('按网关筛选')
  })

  it('选网关后带上 gatewayId，并画出子 MCP 拆分', async () => {
    const view = await render()
    // 没选网关时子 MCP 那块是提示，不是空图
    expect(view.text()).toContain('先在上面选一个网关')

    statsApi.load.mockResolvedValue(stats({
      downstreams: [
        {
          id: 'ds-1', name: 'kb_a', calls: 900, failures: 10,
          successRate: 0.99, avgDurationMs: 150, p95DurationMs: 600
        }
      ]
    }))
    await view.find('.scope-picker select').setValue('gw-1')
    await flushPromises()

    expect(lastFilters().gatewayId).toBe('gw-1')
    expect(view.text()).not.toContain('先在上面选一个网关')
  })

  it('点网关的柱子就钻进那个网关', async () => {
    const view = await render()

    // 图表被替换掉了，直接触发它的选中事件 —— 要验的是"点了之后做什么"
    const charts = view.findAllComponents(EChart)
    const gatewayChart = charts[1]
    gatewayChart.vm.$emit('select', 0)
    await flushPromises()

    expect(lastFilters().gatewayId).toBe('gw-1')
  })
})

describe('总览：图表之外的那条路', () => {
  /*
   * 图表对读屏软件是不可读的，暗色下"超时/错误"那一对的可分辨度也只是刚够，
   * 所以趋势图必须有一份能读到具体数字的表格。
   */
  it('趋势图可以切成表格，每个桶的数字都在', async () => {
    const view = await render()

    const toggle = view.findAll('.chip-btn').find(button => button.text() === '看表格')!
    await toggle.trigger('click')

    const rows = view.findAll('.card table tbody tr')
    expect(rows.length).toBeGreaterThanOrEqual(3)
    // 空桶也在表里，和图上补零一致
    expect(rows[1].text()).toContain('0')
  })

  it('图表都带读屏说明', async () => {
    const view = await render()

    const labels = view.findAll('.chart').map(chart => chart.attributes('aria-label'))
    expect(labels.length).toBeGreaterThan(0)
    expect(labels.every(label => Boolean(label))).toBe(true)
  })

  it('全部网关表里也列出一次调用都没有的网关', async () => {
    const view = await render()

    const text = view.find('.card:last-of-type').text()
    expect(text).toContain('闲置网关')
    expect(text).toContain('idle')
  })

  it('工具榜给出调用量、失败数和 P95', async () => {
    const view = await render()

    const row = view.find('.tools-rank tbody tr').text()
    expect(row).toContain('kb_a__search')
    expect(row).toContain('800')
    expect(row).toContain('700 ms')
  })
})
