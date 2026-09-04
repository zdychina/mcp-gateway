import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import { setUnauthorizedHandler } from './api/client'
import { useSession } from './composables/useSession'
import './styles/app.css'

/*
 * 会话在别处失效时（超时、服务端重启、在另一个标签页退出）把用户送回登录页。
 *
 * 接线放在这里而不是 client.ts 里，是为了不让"发一个请求"依赖"页面往哪跳" ——
 * 那样 client 就没法单独测了。
 */
setUnauthorizedHandler(() => {
  const session = useSession()
  if (!session.state.authenticated) {
    return
  }
  session.markSignedOut()
  const current = router.currentRoute.value
  void router.push({ name: 'login', query: { redirect: current.fullPath } })
})

createApp(App).use(router).mount('#app')
