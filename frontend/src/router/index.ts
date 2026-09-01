import { createRouter, createWebHistory } from 'vue-router'
import GatewayListView from '../views/GatewayListView.vue'
import GatewayDetailView from '../views/GatewayDetailView.vue'
import CallRecordsView from '../views/CallRecordsView.vue'

/*
 * history 的 base 是 /ui/，与 Thymeleaf 时代的页面地址保持一致 ——
 * 已有的书签和文档里的链接不会失效。
 *
 * 服务端把 /ui/** 全部转发给 SPA 的入口文档（见 GatewayPageController），
 * 所以直接访问、刷新、后退都能落到正确的路由上，新增路由不需要动 Java。
 */
export const router = createRouter({
  history: createWebHistory('/ui/'),
  routes: [
    { path: '/', redirect: '/gateways' },
    { path: '/gateways', name: 'gateway-list', component: GatewayListView },
    { path: '/gateways/:id', name: 'gateway-detail', component: GatewayDetailView, props: true },
    { path: '/gateways/:id/calls', name: 'call-records', component: CallRecordsView, props: true }
  ]
})
