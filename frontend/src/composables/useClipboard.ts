import { ref } from 'vue'

/**
 * 复制到剪贴板，带一个"已复制"的短暂反馈。
 *
 * `navigator.clipboard` 在非 HTTPS 或用户拒绝授权时会抛异常 —— 内网 HTTP 部署下
 * 这是常态而不是异常，所以必须有退路：选中源元素让操作人自己按 Ctrl+C。
 */
export function useClipboard() {
  const copied = ref(false)

  async function copy(text: string, fallback?: HTMLInputElement | HTMLTextAreaElement | null): Promise<boolean> {
    try {
      await navigator.clipboard.writeText(text)
      copied.value = true
      window.setTimeout(() => { copied.value = false }, 1500)
      return true
    }
    catch {
      fallback?.focus()
      fallback?.select()
      return false
    }
  }

  return { copied, copy }
}
