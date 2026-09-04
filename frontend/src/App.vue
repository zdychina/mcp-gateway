<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import AlertStack from './components/AlertStack.vue'
import AppIcon from './components/AppIcon.vue'
import { useTheme } from './composables/useTheme'
import { useSession } from './composables/useSession'
import { useAlerts } from './composables/useAlerts'
import { ApiError } from './api/client'

const { theme, resolvedIsDark, toggle } = useTheme()
const session = useSession()
const alerts = useAlerts()
const router = useRouter()

const label = computed(() => {
  const current = theme.value === 'system' ? '跟随系统' : (theme.value === 'dark' ? '暗色' : '亮色')
  return `切换主题（当前：${current}）`
})

const signingOut = ref(false)

async function signOut(): Promise<void> {
  signingOut.value = true
  try {
    await session.signOut()
    // 退出后清掉提示栈：上一段会话的成功/失败提示留在新的登录页上只会让人困惑。
    alerts.clear()
    await router.push({ name: 'login' })
  }
  catch (error) {
    // signOut 内部已经把本地状态清成未登录，这里只是把服务端那边的失败说清楚。
    alerts.warning('退出登录时服务端返回了错误',
      error instanceof ApiError ? error.display : String(error))
    await router.push({ name: 'login' })
  }
  finally {
    signingOut.value = false
  }
}
</script>

<template>
  <nav class="navbar">
    <div class="container">
      <a class="navbar-brand" href="/ui/gateways">
        <AppIcon class="logo" name="hub" :size="18" />
        MCP 聚合网关
      </a>

      <span class="navbar-spacer"></span>

      <!--
        需求 12.8：管理端已有登录，这里显示的是当前身份。
        未登录时（登录页）整块不渲染 —— 那个页面上没有身份可显示，也没有可退的登录。
      -->
      <template v-if="session.state.authenticated">
        <span class="navbar-user" :title="`已登录：${session.state.username}`">
          <AppIcon name="lock" :size="13" />
          <span>{{ session.state.username }}</span>
        </span>

        <button class="btn btn-sm" type="button" :disabled="signingOut" @click="signOut">
          <AppIcon name="exit" :size="14" />
          <span>{{ signingOut ? '退出中…' : '退出登录' }}</span>
        </button>
      </template>

      <button class="btn btn-icon" type="button" :title="label" :aria-label="label" @click="toggle">
        <AppIcon :name="resolvedIsDark() ? 'sun' : 'moon'" :size="16" />
      </button>
    </div>
  </nav>

  <AlertStack />

  <main class="container">
    <RouterView />
  </main>
</template>
