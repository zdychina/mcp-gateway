<script setup lang="ts">
import { ref, watch } from 'vue'
import { downstreamApi } from '../../api/gateways'
import { ApiError } from '../../api/client'
import type { DownstreamMcp, GatewayDetail, SyncResult, UpdateDownstreamRequest } from '../../api/types'
import { formatDateTime, formatRelative } from '../../utils/datetime'
import { useAlerts } from '../../composables/useAlerts'
import { AGENT_RELIST_HINT, describeSyncResult } from '../../utils/sync'
import SyncStatusBadge from '../SyncStatusBadge.vue'

const props = defineProps<{ gatewayId: string, downstream: DownstreamMcp }>()
const emit = defineEmits<{ replaced: [GatewayDetail], reload: [] }>()

const alerts = useAlerts()
const busy = ref(false)

const form = ref({ name: '', url: '' })
/*
 * 需求 12.4：页面上的 headers 是遮罩值 ******，真凭证不回传前端。
 * 所以默认**不提交** headers 字段 —— 把遮罩值原样交回去会把真凭证覆盖掉。
 * 只有操作人显式勾选"替换 headers"，请求里才会带上这个字段。
 */
const replaceHeaders = ref(false)
const headersJson = ref('')

watch(() => props.downstream, downstream => {
  form.value = { name: downstream.name, url: downstream.url }
  replaceHeaders.value = false
  headersJson.value = ''
}, { immediate: true })

function describe(error: unknown): string {
  return error instanceof ApiError ? error.display : String(error)
}

async function save(): Promise<void> {
  const name = form.value.name.trim()

  // 需求 6.3.6：改名会重算所有聚合工具名，对已接入的 Agent 是破坏性变更
  if (name !== props.downstream.name && !window.confirm(
    `子 MCP 从「${props.downstream.name}」改名为「${name}」会改变它下面所有工具的聚合名，\n`
    + '已接入的 Agent 需要重新拉取工具列表。确定继续？')) {
    return
  }

  const request: UpdateDownstreamRequest = { name, url: form.value.url.trim() }

  if (replaceHeaders.value) {
    const raw = headersJson.value.trim()
    if (raw === '') {
      // 勾了替换但留空 = 清空凭证。这是有意义的操作，但值得确认一次
      if (!window.confirm('headers 留空表示**清空**该子 MCP 的全部凭证。确定继续？')) {
        return
      }
      request.headers = {}
    }
    else {
      try {
        const parsed = JSON.parse(raw)
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
          throw new Error('headers 必须是一个 JSON 对象')
        }
        request.headers = parsed as Record<string, string>
      }
      catch (error) {
        alerts.error('headers 不是合法 JSON', error instanceof Error ? error.message : String(error))
        return
      }
    }
  }

  busy.value = true
  try {
    const result = await downstreamApi.update(props.gatewayId, props.downstream.id, request)

    const credentials = replaceHeaders.value
      ? '凭证已替换。'
      : '凭证保持不变（未勾选「替换 headers」）。'

    if (result.syncResult === null) {
      // 只改了名字：没去摸下游，但工具名全变了
      alerts.success('子 MCP 已保存', `${credentials}\n${AGENT_RELIST_HINT}`)
    }
    else if (result.syncResult.succeeded) {
      alerts.success('子 MCP 已保存，工具已重新同步',
        `${credentials}\n${describeSyncResult(result.syncResult)}\n${AGENT_RELIST_HINT}`)
    }
    else {
      // 配置已经落库了，失败的只是同步 —— 需求 6.4.7 保留上一次成功的快照
      alerts.warning('子 MCP 已保存，但重新同步失败',
        `${credentials}\n${describeSyncResult(result.syncResult)}\n`
        + '工具快照仍是上一次成功同步的内容。')
    }

    emit('replaced', result.gateway)
  }
  catch (error) {
    alerts.error('保存子 MCP 失败', describe(error))
  }
  finally {
    busy.value = false
  }
}

async function sync(): Promise<void> {
  busy.value = true
  try {
    const result: SyncResult = await downstreamApi.sync(props.gatewayId, props.downstream.id)
    if (result.succeeded) {
      alerts.success('同步成功', `${describeSyncResult(result)}\n${AGENT_RELIST_HINT}`)
    }
    else {
      // 同步失败不是请求失败：需求 6.4.7 保留上一次成功的快照
      alerts.warning('同步失败，已保留上一次成功的工具快照', describeSyncResult(result))
    }
    // 工具快照变了，让父组件重新取一次详情
    emit('reload')
  }
  catch (error) {
    alerts.error('同步失败', describe(error))
  }
  finally {
    busy.value = false
  }
}

async function remove(): Promise<void> {
  if (!window.confirm(
    `确定删除子 MCP「${props.downstream.name}」？\n\n`
    + '它的工具会立即从总 MCP 的工具列表中消失，且不可再调用。')) {
    return
  }
  busy.value = true
  try {
    const detail = await downstreamApi.remove(props.gatewayId, props.downstream.id)
    alerts.success('子 MCP 已删除',
      `「${props.downstream.name}」的工具已从 tools/list 移除。\n${AGENT_RELIST_HINT}`)
    emit('replaced', detail)
  }
  catch (error) {
    alerts.error('删除子 MCP 失败', describe(error))
  }
  finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="card nested">
    <div class="card-header split wrap">
      <div class="btn-row">
        <strong class="mono">{{ downstream.name }}</strong>
        <SyncStatusBadge :status="downstream.syncStatus" />
        <span v-if="downstream.lastSyncAt" class="small muted"
              :title="formatDateTime(downstream.lastSyncAt)">
          快照更新于 {{ formatRelative(downstream.lastSyncAt) }}
        </span>
        <span v-else class="small muted">尚未同步过</span>
      </div>
      <div class="btn-row">
        <button class="btn btn-sm" type="button" :disabled="busy" @click="sync">测试并同步</button>
        <button class="btn btn-sm btn-danger" type="button" :disabled="busy" @click="remove">删除</button>
      </div>
    </div>

    <div class="card-body stack">
      <!--
        行内提示，不是全局提示栈那套（见 .notice 的说明）：这里说的是"这个子 MCP
        现在的状态"，不是"刚刚发生了什么"。
      -->
      <p v-if="downstream.lastSyncError" class="notice notice-danger">
        {{ downstream.lastSyncError }}
      </p>

      <form @submit.prevent="save">
        <div class="form-grid">
        <div class="field">
          <label :for="`ds-name-${downstream.id}`">名称</label>
          <input :id="`ds-name-${downstream.id}`" v-model="form.name" class="control mono"
                 maxlength="64" required>
          <span class="hint warn">改名会连带改掉所有聚合工具名。</span>
        </div>

        <div class="field span-2">
          <label :for="`ds-url-${downstream.id}`">URL</label>
          <input :id="`ds-url-${downstream.id}`" v-model="form.url" class="control mono" required>
          <span class="hint">改了地址或凭证会自动重新同步一次工具。</span>
        </div>

        <div class="field span-all">
          <!-- 同 DownstreamSection：这组不指向单个控件，所以是 span 而不是 label -->
          <span :id="`ds-headers-label-${downstream.id}`" class="field-label">
            Headers
            <span v-if="Object.keys(downstream.headers).length === 0" class="muted">（未配置）</span>
          </span>
          <!-- 需求 12.4：只显示 header 名称和遮罩值，真实值不回传前端 -->
          <div role="group" :aria-labelledby="`ds-headers-label-${downstream.id}`">
            <div v-for="(masked, name) in downstream.headers" :key="name" class="mono small muted">
              {{ name }}: {{ masked }}
            </div>
          </div>
          <label class="check headers-toggle">
            <input v-model="replaceHeaders" type="checkbox">
            <span>替换 headers</span>
          </label>
          <textarea v-if="replaceHeaders" v-model="headersJson" class="control mono" rows="3"
                    placeholder='{"Authorization": "Bearer 新令牌"}'
                    aria-label="新的 headers JSON"></textarea>
          <span class="hint">不勾选就保持原有凭证不变；勾选后留空表示清空。</span>
        </div>
        </div>

        <div class="form-actions">
          <div class="actions-end">
            <button class="btn btn-primary btn-sm" type="submit" :disabled="busy">保存子 MCP</button>
          </div>
        </div>
      </form>
    </div>
  </div>
</template>
