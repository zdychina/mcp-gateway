<script setup lang="ts">
import CopyField from './CopyField.vue'

/*
 * 需求 FR-05.3：访问令牌只在创建和轮换的那一次响应里出现，服务端只存哈希，
 * 之后任何接口都拿不回明文。所以这个面板必须让操作人当场存走。
 *
 * Thymeleaf 版本靠"创建后刻意不刷新页面"来保住令牌 —— 一个很容易被后来者
 * 无意破坏的约定。改成组件后，令牌只活在这个组件的 props 里，
 * 页面本来就不刷新，也就不存在被冲掉的可能。
 */
defineProps<{ token: string, title?: string }>()
const emit = defineEmits<{ dismiss: [] }>()
</script>

<template>
  <div class="card accent-warning">
    <div class="card-header">{{ title ?? '访问令牌' }}</div>
    <div class="card-body stack">
      <p style="margin: 0">
        <strong>这是唯一一次完整显示该令牌。</strong>
        现在复制保存；离开本页后只能通过轮换生成新的令牌。
      </p>
      <CopyField :value="token" />
      <div class="btn-row">
        <button class="btn btn-sm" type="button" @click="emit('dismiss')">我已保存，关闭</button>
      </div>
    </div>
  </div>
</template>
