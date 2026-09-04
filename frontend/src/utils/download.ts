/*
 * 触发一次浏览器下载。
 *
 * 为什么不直接 window.location = url：那样出错时会把当前页面导航走 —— 服务端在开始写文件
 * 之前做参数校验，失败返回的是一个 JSON 错误信封，用户会看到一屏裸 JSON 而不是页面上的提示。
 * 先 fetch 成 Blob，错误就还能按普通请求处理，成功才落盘。
 */
export function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  // 必须挂到文档里 —— 部分浏览器不响应游离节点上的 click
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // 立刻回收会让下载在部分浏览器里断掉，给一拍时间
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
