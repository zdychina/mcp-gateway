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

/**
 * 带秒的绝对时间，形如 2026-08-31 14:05:09。
 *
 * 调用记录排障要按秒对齐日志，分钟级精度不够 —— 一秒内几十次调用是常态。
 */
export function formatExact(iso: string | null | undefined): string {
  const date = toDate(iso)
  if (!date) {
    return '—'
  }
  return `${formatDateTime(iso)}:${pad(date.getSeconds())}`
}

/** 只有时钟部分，形如 14:05:09。 */
export function formatClock(iso: string | number | null | undefined): string {
  const date = toDate(iso)
  if (!date) {
    return '—'
  }
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/**
 * 列表时间戳：当天只给时分秒，跨天才补上日期。
 *
 * 一页 20 条大多落在同一天，每行都重复年月日会把真正在变的那部分（秒）淹掉。
 */
export function formatStamp(iso: string | null | undefined, now: number = Date.now()): string {
  const date = toDate(iso)
  if (!date) {
    return '—'
  }
  const today = new Date(now)
  const sameDay = date.getFullYear() === today.getFullYear()
    && date.getMonth() === today.getMonth()
    && date.getDate() === today.getDate()
  const clock = formatClock(iso)
  return sameDay ? clock : `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${clock}`
}

/**
 * ISO instant → `<input type="datetime-local">` 认识的本地时间串。
 *
 * 反向转换在 CallRecordsView 里（toInstant）：URL 上存 instant，输入框里是本地时间，
 * 两边必须用同一套换算，否则把链接发给另一个时区的同事会看到错开的窗口。
 */
export function toLocalInput(iso: string | null | undefined): string {
  const date = toDate(iso)
  if (!date) {
    return ''
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function toDate(value: string | number | null | undefined): Date | null {
  if (value === null || value === undefined || value === '') {
    return null
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}
