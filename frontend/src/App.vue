<script setup lang="ts">
import AlertStack from './components/AlertStack.vue'
</script>

<template>
  <nav class="navbar">
    <div class="container">
      <a class="navbar-brand" href="/ui/gateways">MCP 聚合网关</a>
      <!-- 需求 4.3 / 12.8：管理端没有登录，这条提示不能去掉 -->
      <span class="navbar-note">管理端无登录，仅限本机或可信内网访问</span>
    </div>
  </nav>

  <AlertStack />

  <main class="container">
    <RouterView />
  </main>
</template>
