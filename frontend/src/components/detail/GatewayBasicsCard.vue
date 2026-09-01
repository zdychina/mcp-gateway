<script setup lang="ts">
import { ref, watch } from 'vue'
import { gatewayApi } from '../../api/gateways'
import { ApiError } from '../../api/client'
import type { GatewayDetail } from '../../api/types'
import { useAlerts } from '../../composables/useAlerts'
import StatusBadge from '../StatusBadge.vue'

const props = defineProps<{ gateway: GatewayDetail }>()
const emit = defineEmits<{ updated: [GatewayDetail], deleted: [] }>()

const alerts = useAlerts()
const busy = ref(false)

const form = ref({ name: '', slug: '', description: '' })

// 保存成功后父组件会把新的 gateway 传下来，表单跟着回到"已保存"的状态
watch(() => props.gateway, gateway => {
  form.value = {
    name: gateway.name,
    slug: gateway.slug,
    description: gateway.description ?? ''
  }
}, { immediate: true })

function describe(error: unknown): string {
  return error instanceof ApiError ? error.display : String(error)
}

async function save(): Promise<void> {
  const slug = form.value.slug.trim()

  // 需求 6.1.2：slug 决定 Agent 的 MCP 地址，改它是破坏性变更
  if (slug !== props.gateway.slug && !window.confirm(
    `slug 从「${props.gateway.slug}」改为「${slug}」会改变 Agent 的 MCP 地址，\n`
    + '已接入的 Agent 需要更新配置。确定继续？')) {
    return
  }

  const description = form.value.description.trim()
  busy.value = true
  try {
    const updated = await gatewayApi.update(props.gateway.id, {
      name: form.value.name.trim(),
      slug,
      description: description === '' ? null : description
    })
    alerts.success('网关已保存', slug !== props.gateway.slug ? `MCP 地址已变为 ${updated.mcpUrl}` : undefined)
    emit('updated', updated)
  }
  catch (error) {
    alerts.error('保存网关失败', describe(error))
  }
  finally {
    busy.value = false
  }
}

async function remove(): Promise<void> {
  // 需求 6.1.7：删除必须二次确认，并说清连带影响
  if (!window.confirm(
    `确定删除网关「${props.gateway.name}」？\n\n`
    + '它的子 MCP 配置、工具快照和全部调用记录都会一并删除，且无法恢复。')) {
    return
  }
  busy.value = true
  try {
    await gatewayApi.remove(props.gateway.id)
    emit('deleted')
  }
  catch (error) {
    alerts.error('删除网关失败', describe(error))
    busy.value = false
  }
}
</script>

<template>
  <section class="card">
    <div class="card-header split">
      <span>基本信息</span>
      <StatusBadge :status="gateway.status" />
    </div>
    <div class="card-body">
      <form id="gateway-form" class="grid" @submit.prevent="save">
        <div class="field">
          <label for="gw-name">名称</label>
          <input id="gw-name" v-model="form.name" class="control" maxlength="64" required>
        </div>
        <div class="field">
          <label for="gw-slug">标识 slug</label>
          <input id="gw-slug" v-model="form.slug" class="control mono" maxlength="64"
                 pattern="[A-Za-z0-9_-]{1,64}" required>
          <span class="hint warn">改动会改变 Agent 的 MCP 地址，已接入的 Agent 需要更新配置。</span>
        </div>
        <div class="field">
          <label for="gw-description">描述</label>
          <textarea id="gw-description" v-model="form.description" class="control"
                    rows="2" maxlength="4000"></textarea>
          <span class="hint">作为 instructions 传给 Agent</span>
        </div>
        <div class="field justify-end">
          <div class="btn-row split">
            <button class="btn btn-primary" type="submit" :disabled="busy">保存</button>
            <button class="btn btn-danger" type="button" :disabled="busy" @click="remove">删除网关</button>
          </div>
        </div>
      </form>
    </div>
  </section>
</template>
