import { describe, expect, it } from 'vitest'
import { formatDateTime, formatRelative } from '../src/utils/datetime'

describe('时间展示', () => {
  const now = Date.parse('2026-08-31T12:00:00Z')

  it('空值显示为占位符而不是 Invalid Date', () => {
    expect(formatDateTime(null)).toBe('—')
    expect(formatRelative(undefined)).toBe('—')
    expect(formatDateTime('not-a-date')).toBe('—')
  })

  it('按跨度选择相对时间的粒度', () => {
    expect(formatRelative('2026-08-31T11:59:30Z', now)).toBe('刚刚')
    expect(formatRelative('2026-08-31T11:45:00Z', now)).toBe('15 分钟前')
    expect(formatRelative('2026-08-31T09:00:00Z', now)).toBe('3 小时前')
    expect(formatRelative('2026-08-29T12:00:00Z', now)).toBe('2 天前')
  })

  it('超过 30 天回落到绝对时间，避免出现「87 天前」这种读不出信息的文案', () => {
    expect(formatRelative('2026-01-01T12:00:00Z', now)).toMatch(/^2026-01-01 /)
  })

  it('时钟偏差导致的未来时间不显示成负数', () => {
    expect(formatRelative('2026-08-31T12:30:00Z', now)).toMatch(/^2026-08-31 /)
  })
})
