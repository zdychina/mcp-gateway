import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * 主题偏好。
 *
 * 这个 composable 的状态是模块级的（整个应用共用一份），所以每个用例都要
 * resetModules 重新导入，否则上一条的选择会漏到下一条。
 */
function freshTheme() {
  vi.resetModules()
  return import('../src/composables/useTheme')
}

beforeEach(() => {
  // 先撤掉上一条留下的全局桩，再动 localStorage —— 那些桩可能没有 clear()
  vi.unstubAllGlobals()
  document.documentElement.removeAttribute('data-theme')
  window.localStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('主题切换', () => {
  it('没有存过偏好时跟随系统 —— 不给 <html> 打任何标记', async () => {
    const { useTheme } = await freshTheme()

    expect(useTheme().theme.value).toBe('system')
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })

  it('切换后给 <html> 打上 data-theme 并记住选择', async () => {
    const { useTheme } = await freshTheme()
    const { toggle, theme } = useTheme()

    toggle()
    await Promise.resolve()

    expect(theme.value).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(window.localStorage.getItem('mcp-gateway.theme')).toBe('dark')
  })

  it('再切一次回到亮色', async () => {
    const { useTheme } = await freshTheme()
    const { toggle, theme } = useTheme()

    toggle()
    await Promise.resolve()
    toggle()
    await Promise.resolve()

    expect(theme.value).toBe('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('启动时读回上次的选择', async () => {
    window.localStorage.setItem('mcp-gateway.theme', 'dark')
    const { useTheme } = await freshTheme()

    expect(useTheme().theme.value).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })

  /*
   * 隐私模式或"禁止站点数据"下 localStorage 的读写会直接抛异常。
   * 界面不该因为记不住一个显示偏好就起不来。
   */
  it('localStorage 不可用时退回跟随系统，不抛异常', async () => {
    vi.stubGlobal('localStorage', {
      getItem() { throw new DOMException('denied') },
      setItem() { throw new DOMException('denied') },
      removeItem() { throw new DOMException('denied') }
    })

    const { useTheme } = await freshTheme()
    expect(useTheme().theme.value).toBe('system')

    // 切换仍然生效，只是这次会话结束后记不住
    expect(() => useTheme().toggle()).not.toThrow()
  })

  it('matchMedia 不可用时按亮色处理，不抛异常', async () => {
    vi.stubGlobal('matchMedia', undefined)
    const { useTheme } = await freshTheme()

    expect(useTheme().resolvedIsDark()).toBe(false)
  })
})
