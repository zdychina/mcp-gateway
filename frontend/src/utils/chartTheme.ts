/*
 * 图表配色与排版令牌。
 *
 * 配色不是拍脑袋选的：每一组都跑过色觉障碍（deutan/protan/tritan）的可分辨度校验、
 * 亮度带、色度下限和对表面的对比度。结论有两条值得记下来 ——
 *
 * 1. 应用自己的 --success #1a7f4b 和 --danger #c02b30 在绿色盲下几乎分不开
 *    （ΔE 3~5，远低于 8 的下限），所以**图表里的"成功"不复用徽章的绿**，
 *    换成青绿 #2aa198：和红色的 deutan ΔE 拉到 14.6。徽章上不受影响 ——
 *    那里有文字，颜色不是唯一线索。
 * 2. 暗色不是把亮色反过来：暗色模式的亮度带更窄（OKLCH L 0.48~0.67），
 *    亮色下能用的琥珀在暗色下会超出带宽，得单独取步。
 *
 * 暗色下"超时/错误"这一对的 deutan ΔE 是 6.2，落在"必须有第二重编码"的区间里，
 * 所以状态图一律带图例、堆叠段之间留 2px 缝、并且提供表格视图。
 */

export interface ChartTokens {
  /** 图表表面色，堆叠段之间的"缝"就是用它画的 */
  surface: string
  text: string
  textMuted: string
  textSubtle: string
  border: string
  /** 单序列柱状图用的强调色（量的大小，不承担身份） */
  accent: string
  /** 状态色：成功 / 错误 / 超时 */
  success: string
  error: string
  timeout: string
  fontFamily: string
}

const LIGHT: ChartTokens = {
  surface: '#ffffff',
  text: '#1a1e23',
  textMuted: '#5c646e',
  textSubtle: '#8b939d',
  border: '#e9ecef',
  accent: '#3056d3',
  success: '#2aa198',
  error: '#c02b30',
  timeout: '#e0a53b',
  fontFamily: ''
}

const DARK: ChartTokens = {
  surface: '#161a20',
  text: '#e4e7eb',
  textMuted: '#9ba3ae',
  textSubtle: '#767e89',
  border: '#262c35',
  accent: '#6c8cff',
  success: '#25a89c',
  error: '#d1494e',
  timeout: '#b5842c',
  fontFamily: ''
}

/**
 * 当前主题下的图表令牌。
 *
 * 中性色直接从样式表里读，保证图表和页面用的是同一套变量；状态色和强调色用上面
 * 校验过的固定值，不从 CSS 变量取 —— 那几个变量是给徽章和文字用的，改了会悄悄
 * 让图表退回到分不清的配色。
 */
export function chartTokens(isDark: boolean): ChartTokens {
  const base = isDark ? DARK : LIGHT
  if (typeof window === 'undefined' || typeof getComputedStyle !== 'function') {
    return base
  }
  const style = getComputedStyle(document.documentElement)
  const read = (name: string, fallback: string) =>
    style.getPropertyValue(name).trim() || fallback

  return {
    ...base,
    surface: read('--surface', base.surface),
    text: read('--text', base.text),
    textMuted: read('--text-muted', base.textMuted),
    textSubtle: read('--text-subtle', base.textSubtle),
    border: read('--border', base.border),
    fontFamily: read('--font-sans', base.fontFamily)
  }
}

/** 坐标轴、网格线一律弱化：它们是参照物，不是内容。 */
export function axisStyle(tokens: ChartTokens) {
  return {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: tokens.textMuted, fontSize: 11, fontFamily: tokens.fontFamily },
    splitLine: { lineStyle: { color: tokens.border, type: 'dashed' as const } }
  }
}

/** 提示框统一样式，跟着主题走。 */
export function tooltipStyle(tokens: ChartTokens) {
  return {
    backgroundColor: tokens.surface,
    borderColor: tokens.border,
    borderWidth: 1,
    padding: [8, 10] as [number, number],
    textStyle: { color: tokens.text, fontSize: 12, fontFamily: tokens.fontFamily },
    extraCssText: 'box-shadow: 0 4px 12px -2px rgba(16, 24, 40, .16); border-radius: 8px;'
  }
}
