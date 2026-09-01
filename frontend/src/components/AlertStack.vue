<script setup lang="ts">
import { useAlerts } from '../composables/useAlerts'

const { items, dismiss } = useAlerts()
</script>

<template>
  <!--
    aria-live 是必须的：Thymeleaf 版本的 #alerts 容器没有它，
    屏幕阅读器完全读不到任何操作反馈。
  -->
  <div class="alerts container" role="status" aria-live="polite">
    <div v-for="item in items" :key="item.id" class="alert" :class="'alert-' + item.kind">
      <div class="alert-body">
        <div class="alert-title">{{ item.title }}</div>
        <!-- 一律走文本插值，绝不拼 HTML：错误文案里可能带下游返回的内容 -->
        <div v-if="item.detail" class="alert-detail">{{ item.detail }}</div>
      </div>
      <button class="alert-close" type="button" aria-label="关闭" @click="dismiss(item.id)">×</button>
    </div>
  </div>
</template>
