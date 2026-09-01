<script setup lang="ts">
import { computed } from 'vue'
import AlertStack from './components/AlertStack.vue'
import AppIcon from './components/AppIcon.vue'
import { useTheme } from './composables/useTheme'

const { theme, resolvedIsDark, toggle } = useTheme()

const label = computed(() => {
  const current = theme.value === 'system' ? '跟随系统' : (theme.value === 'dark' ? '暗色' : '亮色')
  return `切换主题（当前：${current}）`
})
</script>

<template>
  <nav class="navbar">
    <div class="container">
      <a class="navbar-brand" href="/ui/gateways">
        <AppIcon class="logo" name="hub" :size="18" />
        MCP 聚合网关
      </a>

      <span class="navbar-spacer"></span>

      <!-- 需求 4.3 / 12.8：管理端没有登录，这条提示不能去掉 -->
      <span class="navbar-note">
        <AppIcon name="warning" :size="13" />
        <span>管理端无登录，仅限本机或可信内网访问</span>
      </span>

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
