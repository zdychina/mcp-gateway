/*
 * 时间展示。
 *
 * 服务端返回的是 ISO-8601 的 Instant（UTC），这里转成浏览器本地时区显示 ——
 * Thymeleaf 时代用的是 JVM 默认时区，运维在另一个时区看页面会得到错误的时间。
 */

/** 绝对时间，形如 2026-08-31 14:05。 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return '—'
  }
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + ` ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 相对时间，形如「3 分钟前」。
 *
 * 「最近同步」「更新时间」这类字段真正要回答的是"多久以前"，
 * 绝对时间戳放 title 里备查。
 */
export function formatRelative(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) {
    return '—'
  }
  const time = new Date(iso).getTime()
  if (Number.isNaN(time)) {
    return '—'
  }
  const seconds = Math.round((now - time) / 1000)
  if (seconds < 0) {
    // 时钟偏差，别显示"-3 秒前"这种东西
    return formatDateTime(iso)
  }
  if (seconds < 60) {
    return '刚刚'
  }
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    return `${minutes} 分钟前`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${hours} 小时前`
  }
  const days = Math.floor(hours / 24)
  if (days < 30) {
    return `${days} 天前`
  }
  return formatDateTime(iso)
}
