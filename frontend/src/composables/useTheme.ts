import { ref, watchEffect } from 'vue'

export type Theme = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'mcp-gateway.theme'

/**
 * 主题偏好。
 *
 * 三态而不是两态：'system' 表示跟随系统，没有这一档就没法从手动选择退回自动。
 * 显式选择时给 <html> 打上 data-theme，样式表里那两组变量据此切换；
 * 选 system 就把属性去掉，交给 prefers-color-scheme 的媒体查询。
 *
 * localStorage 可能不可用（隐私模式、站点数据被禁），所以读写都吞异常 ——
 * 拿不到偏好就跟随系统，不该因此让界面起不来。
 */
function readStored(): Theme {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY)
    return value === 'light' || value === 'dark' ? value : 'system'
  }
  catch {
    return 'system'
  }
}

const theme = ref<Theme>(readStored())

watchEffect(() => {
  const root = document.documentElement
  if (theme.value === 'system') {
    root.removeAttribute('data-theme')
  }
  else {
    root.setAttribute('data-theme', theme.value)
  }
  try {
    if (theme.value === 'system') {
      window.localStorage.removeItem(STORAGE_KEY)
    }
    else {
      window.localStorage.setItem(STORAGE_KEY, theme.value)
    }
  }
  catch {
    // 存不下就只在本次会话里生效，不影响显示
  }
})

/** 当前实际呈现的是不是暗色（'system' 时看系统偏好）。 */
function resolvedIsDark(): boolean {
  if (theme.value !== 'system') {
    return theme.value === 'dark'
  }
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  }
  catch {
    return false
  }
}

export function useTheme() {
  return {
    theme,
    resolvedIsDark,
    /** 在明暗之间切换。从 system 出发时切到与当前呈现相反的那一档。 */
    toggle() {
      theme.value = resolvedIsDark() ? 'light' : 'dark'
    }
  }
}
