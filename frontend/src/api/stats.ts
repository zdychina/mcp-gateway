import { http } from './client'
import type { Stats, StatsFilters } from './types'

/**
 * 统计接口（总览页）。
 *
 * 一个接口一次拿全：这一页上的所有图都是同一个时间窗、同一份数据的不同切法，
 * 拆成多个请求只会带来"总量对得上、分组对不上"的中间态和四份各自可能失败的加载状态。
 */
export const statsApi = {
  load: (filters: StatsFilters = {}) => {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(filters)) {
      if (value !== undefined && value !== null && value !== '') {
        params.set(key, String(value))
      }
    }
    const query = params.toString()
    return http.get<Stats>(`/api/stats${query ? '?' + query : ''}`)
  }
}
