import { reactive, readonly } from 'vue'
import { authApi } from '../api/auth'
import type { Session } from '../api/types'

/*
 * 全局登录态。
 *
 * 只在应用启动时拉一次会话，之后靠登录/退出和 401 回调维护 —— 每次路由跳转都去问
 * 服务端会让翻页变慢，而这里的状态本来就只是用来决定"渲染哪个界面"。
 *
 * 它不是访问控制：真正的门在服务端，每个 /api 请求都会被独立校验。
 * 把这个状态改成 true 只能骗到自己的浏览器，一个字节的数据也拿不到。
 */
const state = reactive({
  /** 首次会话查询是否已经回来。守卫要等它，否则刷新页面会闪一下登录页。 */
  ready: false,
  authenticated: false,
  username: null as string | null
})

let pending: Promise<void> | null = null

function apply(session: Session): void {
  state.authenticated = session.authenticated
  state.username = session.username
  state.ready = true
}

function markSignedOut(): void {
  state.authenticated = false
  state.username = null
  state.ready = true
}

/** 启动时调用一次。并发调用共用同一个请求。 */
function load(): Promise<void> {
  if (state.ready) {
    return Promise.resolve()
  }
  pending ??= authApi.current()
    .then(apply)
    // 拉不到会话就按未登录处理：让用户看见登录页，比卡在空白页上强。
    .catch(markSignedOut)
    .finally(() => { pending = null })
  return pending
}

export function useSession() {
  return {
    state: readonly(state),

    load,

    async signIn(username: string, password: string): Promise<void> {
      apply(await authApi.signIn(username, password))
    },

    async signOut(): Promise<void> {
      try {
        await authApi.signOut()
      }
      finally {
        // 服务端那边成没成功都要清掉本地状态：会话已经不可信了。
        markSignedOut()
      }
    },

    /** 收到 401 时调用 —— 会话在别处过期了。 */
    markSignedOut
  }
}
