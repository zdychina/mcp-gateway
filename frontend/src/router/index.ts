import { createRouter, createWebHistory } from 'vue-router'
import GatewayListView from '../views/GatewayListView.vue'
import GatewayDetailView from '../views/GatewayDetailView.vue'
import CallRecordsView from '../views/CallRecordsView.vue'
import LoginView from '../views/LoginView.vue'
import { useSession } from '../composables/useSession'

/*
 * history 的 base 是 /ui/，与 Thymeleaf 时代的页面地址保持一致 ——
 * 已有的书签和文档里的链接不会失效。
 *
 * 服务端把 /ui/** 全部转发给 SPA 的入口文档（见 GatewayPageController），
 * 所以直接访问、刷新、后退都能落到正确的路由上，新增路由不需要动 Java。
 * /ui/** 在 SecurityConfig 里是公开的 —— 未登录时要靠它把登录页本身发出来。
 */
export const router = createRouter({
  history: createWebHistory('/ui/'),
  routes: [
    { path: '/', redirect: '/gateways' },
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/gateways', name: 'gateway-list', component: GatewayListView },
    { path: '/gateways/:id', name: 'gateway-detail', component: GatewayDetailView, props: true },
    { path: '/gateways/:id/calls', name: 'call-records', component: CallRecordsView, props: true }
  ]
})

/*
 * 未登录就送去登录页。
 *
 * 这是体验，不是访问控制 —— 真正的门在服务端，每个 /api 请求都被独立校验，
 * 绕过这个守卫只能看到一个拉不到任何数据的空壳。放在这里的价值是让用户看见登录页，
 * 而不是一屏"加载失败"。
 *
 * 首次导航要等会话查询回来（useSession.load 内部只发一次请求），
 * 否则刷新任何一个页面都会先闪一下登录页再跳回来。
 */
router.beforeEach(async (to) => {
  const session = useSession()
  await session.load()

  if (to.meta.public) {
    // 已经登录了就别再停在登录页上。
    return session.state.authenticated ? { path: '/gateways' } : true
  }
  if (session.state.authenticated) {
    return true
  }
  return { name: 'login', query: { redirect: to.fullPath } }
})
