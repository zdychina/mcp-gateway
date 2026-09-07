/*
 * 数字与比率的展示格式。
 *
 * 抽出来是因为调用记录页和总览页要用同一套写法 —— 同一个 120 毫秒，一处写 "120 ms"
 * 另一处写 "0.12 s"，会让人以为看的是两回事。
 */

/** 耗时。毫秒级给整数，过秒换成秒，没有值给破折号。 */
export function durationLabel(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) {
    return '—'
  }
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(2)} s`
}

/**
 * 比率。
 *
 * null 是"没有样本"，不是 0% —— 两者在界面上必须长得不一样。
 */
export function percentLabel(rate: number | null | undefined): string {
  return rate === null || rate === undefined ? '—' : `${(rate * 100).toFixed(1)}%`
}

/** 计数。四位以上加千分位，一屏几个大数字时更好扫。 */
export function countLabel(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : value.toLocaleString('zh-CN')
}
