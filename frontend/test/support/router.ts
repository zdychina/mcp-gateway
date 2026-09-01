import { createRouter, createWebHistory } from 'vue-router'
import type { Router } from 'vue-router'

const blank = { template: '<div />' }

/**
 * 与 src/router 同构的测试路由：base 同样是 /ui/，这样 RouterLink 解析出的
 * href 和线上一致（/ui/gateways/xxx），断言才有意义。
 *
 * 目标组件用空壳替换 —— 这里要验的是链接指向哪，不是跳过去渲染成什么。
 */
export function createTestRouter(): Router {
  return createRouter({
    history: createWebHistory('/ui/'),
    routes: [
      { path: '/', redirect: '/gateways' },
      { path: '/gateways', name: 'gateway-list', component: blank },
      { path: '/gateways/:id', name: 'gateway-detail', component: blank },
      { path: '/gateways/:id/calls', name: 'call-records', component: blank }
    ]
  })
}
