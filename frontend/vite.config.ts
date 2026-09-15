/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

/*
 * 子路径部署的前缀，例如 "/kbmcp"；默认空串，应用占据整个根路径。
 *
 * 取值走 loadEnv 而不是 process.env：vue-tsc 会把这个配置文件一起类型检查，直接读
 * process 需要额外装 @types/node。loadEnv 是 Vite 自带的、带类型的入口，且同时认
 * .env 文件和进程环境里的 VITE_ 变量 —— 命令行 `VITE_BASE_PATH=/kbmcp npm run build`
 * 与 pom.xml 里 npm-build 那个 execution 注入的形式都能取到。
 *
 * 归一化与 src/basePath.ts 的 normalizeBasePath 同义。不直接 import 那个模块，是因为
 * 它读 import.meta.env，而配置文件在 Node 里执行，求值会当场抛。
 *
 * 这个值必须与服务端的 server.servlet.context-path 一致，且反向代理**不要**再剥一次
 * 前缀（nginx 里 proxy_pass 结尾不带路径）。剥两次等于没设。
 */
function basePathFrom(mode: string): string {
  const raw = (loadEnv(mode, '.', 'VITE_').VITE_BASE_PATH ?? '').trim().replace(/\/+$/, '')
  return raw === '' || raw.startsWith('/') ? raw : `/${raw}`
}

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
export default defineConfig(({ command, mode }) => ({
  plugins: [vue()],

  /*
   * 资源前缀在两种模式下不一样，这是必须的：
   *
   * - 构建产物用 /app/：Vue 独占这个前缀，与 Thymeleaf 时代的 /css /js 完全隔离，
   *   迁移期两套前端并存互不干扰。页面地址 /ui/gateways 由 Spring 转发到
   *   /app/index.html，路由再从 window.location 读出 /gateways。
   * - 开发期用 /：dev server 的 SPA fallback 按 base 决定把哪些请求回落到
   *   index.html。若这里也写 /app/，直接访问 /ui/gateways 会落空 ——
   *   而那正是开发时要打开的地址。这也是 BASE_PATH 只作用于构建产物的原因，
   *   见 src/basePath.ts。
   *
   * 两种模式下路由的 base 都是 `${BASE_PATH}/ui/`，见 src/router/index.ts。
   */
  base: command === 'build' ? `${basePathFrom(mode)}/app/` : '/',

  build: {
    /*
     * 总览页的 ECharts 单独成块（约 530 KB / gzip 180 KB），超过 rollup 默认的 500 KB 提醒线。
     * 它是路由级懒加载的，只在真正打开总览时才下载，首屏产物仍在 170 KB 量级 ——
     * 所以这里把阈值抬到刚好放得下它，而不是让每次构建都打印一条不需要处理的告警：
     * 一直响的警报等于没有警报。
     */
    chunkSizeWarningLimit: 600,
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
