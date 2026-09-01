<script setup lang="ts">
import { ref, watch } from 'vue'
import { downstreamApi } from '../../api/gateways'
import { ApiError } from '../../api/client'
import type { DownstreamMcp, GatewayDetail, SyncResult, UpdateDownstreamRequest } from '../../api/types'
import { formatDateTime, formatRelative } from '../../utils/datetime'
import { useAlerts } from '../../composables/useAlerts'
import { describeSyncResult } from '../../utils/sync'
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
    const detail = await downstreamApi.update(props.gatewayId, props.downstream.id, request)
    alerts.success('子 MCP 已保存',
      replaceHeaders.value ? '凭证已替换。' : '凭证保持不变（未勾选「替换 headers」）。')
    emit('replaced', detail)
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
      alerts.success('同步成功', describeSyncResult(result))
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
    alerts.success('子 MCP 已删除', `「${props.downstream.name}」的工具已从 tools/list 移除。`)
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
      <div v-if="downstream.lastSyncError" class="alert alert-danger compact">
        <div class="alert-body">
          <div class="alert-detail">{{ downstream.lastSyncError }}</div>
        </div>
      </div>

      <form class="grid" @submit.prevent="save">
        <div class="field">
          <label :for="`ds-name-${downstream.id}`">名称</label>
          <input :id="`ds-name-${downstream.id}`" v-model="form.name" class="control mono"
                 maxlength="64" required>
          <span class="hint warn">改名会连带改掉所有聚合工具名。</span>
        </div>

        <div class="field span-2">
          <label :for="`ds-url-${downstream.id}`">URL</label>
          <input :id="`ds-url-${downstream.id}`" v-model="form.url" class="control mono" required>
        </div>

        <div class="field">
          <label>
            Headers
            <span v-if="Object.keys(downstream.headers).length === 0" class="muted">（未配置）</span>
          </label>
          <!-- 需求 12.4：只显示 header 名称和遮罩值，真实值不回传前端 -->
          <div v-for="(masked, name) in downstream.headers" :key="name" class="mono small muted">
            {{ name }}: {{ masked }}
          </div>
          <label class="check">
            <input v-model="replaceHeaders" type="checkbox">
            <span>替换 headers</span>
          </label>
          <textarea v-if="replaceHeaders" v-model="headersJson" class="control mono" rows="3"
                    placeholder='{"Authorization": "Bearer 新令牌"}'
                    aria-label="新的 headers JSON"></textarea>
          <span class="hint">不勾选就保持原有凭证不变；勾选后留空表示清空。</span>
        </div>

        <div class="field justify-end">
          <div class="btn-row">
            <button class="btn btn-primary btn-sm" type="submit" :disabled="busy">保存子 MCP</button>
          </div>
        </div>
      </form>
    </div>
  </div>
</template>
