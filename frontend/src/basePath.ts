/*
 * 部署前缀。
 *
 * 应用默认占据整个根路径，前缀是空串。挂在子路径下时（例如
 * https://host/kbmcp）它是 "/kbmcp"，且必须与服务端的
 * server.servlet.context-path 是同一个值 —— 一边有一边没有，页面能打开但
 * 资源和接口全部落空。
 *
 * 值在构建期由 VITE_BASE_PATH 注入，也就是说前缀是**打进产物**的：换前缀要重新
 * 构建，不是改个运行时配置就行。这是刻意的取舍 —— 让一份产物适配任意前缀，就得让
 * 服务端在发 index.html 时动态注入，SPA 入口那句 forward: 会变成读文件做替换。
 * 详见 DEPLOY.md 的「挂在子路径下」。
 *
 * 前缀要出现在三个地方，少一个就坏一样东西：
 *   - vite 的 base（见 vite.config.ts）—— 少了就是满屏资源 404；
 *   - 路由的 history base（见 router/index.ts）—— 少了路由解析不出任何页面；
 *   - fetch 的 URL 前缀（见 api/client.ts）—— 少了请求会打到同域的其他应用上去，
 *     那才是最难查的一种：不是 404，是别人的 404。
 */

/**
 * 去掉末尾斜杠、补上开头斜杠；空值和单个 "/" 都归一成空串。
 *
 * 归一化本身就是防御：拼接处一律写成 `${BASE_PATH}/ui/`，前缀带不带尾斜杠
 * 都不会拼出 "//ui/" 这种看起来能用、实际会被当成协议相对 URL 的地址。
 */
export function normalizeBasePath(raw: string | undefined): string {
  const trimmed = (raw ?? '').trim().replace(/\/+$/, '')
  if (trimmed === '') {
    return ''
  }
  return trimmed.startsWith('/') ? trimmed : `/${trimmed}`
}

/*
 * 开发期恒为空串，哪怕环境里设了 VITE_BASE_PATH。
 *
 * dev server 的 base 固定是 "/"（见 vite.config.ts 里为什么必须如此），这里要是跟着
 * 环境变量走，路由的 base 就会指向一个 dev server 根本不会回落到 index.html 的前缀 ——
 * 打开页面直接空白。构建产物才需要前缀，开发期不需要。
 */
export const BASE_PATH = import.meta.env.DEV
  ? ''
  : normalizeBasePath(import.meta.env.VITE_BASE_PATH as string | undefined)
