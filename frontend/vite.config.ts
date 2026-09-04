/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/*
 * 关键取舍：产物同源打进 Spring Boot，而不是独立部署。
 *
 * 管理端有登录、有会话 Cookie、有 CSRF 令牌，但挡住跨站请求的仍有一层是"同源"：
 * 服务端一个 CORS 响应头都不发。真做成前后端分离部署就必须放开 CORS，那一层会直接消失
 * （SecurityInvariantsTest.noCorsIsEnabled 正是为此在构建期把关），
 * 会话 Cookie 的 SameSite=Strict 也会连带失效 —— 跨源请求根本带不上它。
 *
 * 所以：开发期用下面的 proxy 让浏览器看到的始终是同源；生产期产物落进
 * static/app/ 随 jar 一起发布。两种形态都不需要 CORS。
 */
export default defineConfig(({ command }) => ({
  plugins: [vue()],

  /*
   * 资源前缀在两种模式下不一样，这是必须的：
   *
   * - 构建产物用 /app/：Vue 独占这个前缀，与 Thymeleaf 时代的 /css /js 完全隔离，
   *   迁移期两套前端并存互不干扰。页面地址 /ui/gateways 由 Spring 转发到
   *   /app/index.html，路由再从 window.location 读出 /gateways。
   * - 开发期用 /：dev server 的 SPA fallback 按 base 决定把哪些请求回落到
   *   index.html。若这里也写 /app/，直接访问 /ui/gateways 会落空 ——
   *   而那正是开发时要打开的地址。
   *
   * 两种模式下路由的 base 都是 /ui/，见 src/router/index.ts。
   */
  base: command === 'build' ? '/app/' : '/',

  build: {
    outDir: '../src/main/resources/static/app',
    // 只清空 static/app/ 这个子目录，动不到同级的 bootstrap.min.css 等既有资源。
    emptyOutDir: true,
    sourcemap: true
  },

  server: {
    port: 5173,
    proxy: {
      // 开发期把管理 API 转发给后端。浏览器发出的仍是同源请求，
      // 因此不需要在服务端放开任何 CORS。
      '/api': { target: 'http://127.0.0.1:8080' }
    }
  },

  test: {
    environment: 'jsdom',
    include: ['test/**/*.spec.ts']
  }
}))
