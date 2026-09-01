<script setup lang="ts">
/**
 * 表格骨架屏。
 *
 * 比一行"加载中…"更有用：它提前把内容的形状画出来，用户知道马上要出现一张表，
 * 而且内容填进来时布局不会跳。
 */
withDefaults(defineProps<{ rows?: number }>(), { rows: 4 })
</script>

<template>
  <div class="skeleton-rows" role="status" aria-label="加载中">
    <div v-for="row in rows" :key="row" class="skeleton-row">
      <div class="skeleton" :style="{ width: '70%' }"></div>
      <div class="skeleton" :style="{ width: '50%' }"></div>
      <div class="skeleton" :style="{ width: '40%' }"></div>
      <div class="skeleton" :style="{ width: '60%' }"></div>
    </div>
  </div>
</template>
