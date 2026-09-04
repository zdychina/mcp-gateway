import { http } from './client'
import type { Session } from './types'

/**
 * 管理端登录（需求 12.8）。
 *
 * 三个接口都收发 JSON，和其余管理接口用的是同一套 {success, data, error} 信封 ——
 * 服务端刻意没有用 Spring Security 自带的表单登录，正是为了不破坏"只收 JSON"这道保护。
 */
export const authApi = {
  /** 公开。前端启动时拉一次，决定渲染登录页还是主界面。 */
  current: () => http.get<Session>('/api/auth/session'),

  /** 失败一律 UNAUTHORIZED，服务端不区分用户名错还是口令错。 */
  signIn: (username: string, password: string) =>
    http.post<Session>('/api/auth/login', { username, password }),

  signOut: () => http.post<Session>('/api/auth/logout')
}
