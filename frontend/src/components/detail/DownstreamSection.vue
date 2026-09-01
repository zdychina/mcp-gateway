<script setup lang="ts">
import { computed, ref } from 'vue'
import { downstreamApi } from '../../api/gateways'
import { ApiError } from '../../api/client'
import type { GatewayDetail } from '../../api/types'
import { useAlerts } from '../../composables/useAlerts'
import { describeSyncResults } from '../../utils/sync'
import DownstreamCard from './DownstreamCard.vue'

const props = defineProps<{ gateway: GatewayDetail, maxDownstreams?: number }>()
const emit = defineEmits<{ replaced: [GatewayDetail], reload: [] }>()

const alerts = useAlerts()
const busy = ref(false)
const importJson = ref('')

/** 需求 6.2.1：默认上限 3，但它是配置项 —— 服务端才是权威，这里只用于提示。 */
const limit = computed(() => props.maxDownstreams ?? 3)
const atLimit = computed(() => props.gateway.downstreams.length >= limit.value)

const PLACEHOLDER = `{
  "mcpServers": {
    "knowledge_base_a": {
      "type": "streamable-http",
      "url": "https://example.com/mcp",
      "headers": { "Authorization": "Bearer 真实令牌" }
    }
  }
}`

function fillExample(): void {
  importJson.value = PLACEHOLDER
}

async function submitImport(): Promise<void> {
  const raw = importJson.value.trim()
  if (raw === '') {
    alerts.error('请先粘贴配置 JSON', undefined)
    return
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  }
  catch (error) {
    alerts.error('配置 JSON 格式错误', error instanceof Error ? error.message : String(error))
    return
  }

  busy.value = true
  try {
    /*
     * 原样把解析出的对象转发给服务端，不先过一道自己的模型 ——
     * 服务端要靠里面有没有 command/args/env 判定 stdio 配置并报 UNSUPPORTED_TRANSPORT。
     */
    const result = await downstreamApi.import(props.gateway.id, parsed)
    const failed = result.syncResults.filter(item => !item.succeeded)

    // 配置一定全部落库，同步逐个成败 —— 这两件事必须分开说清楚
    if (failed.length > 0) {
      alerts.warning(
        `子 MCP 已导入，但 ${failed.length} 个同步失败`,
        describeSyncResults(result.syncResults))
    }
    else {
      alerts.success('子 MCP 已导入并同步', describeSyncResults(result.syncResults))
    }
    importJson.value = ''
    emit('replaced', result.gateway)
  }
  catch (error) {
    alerts.error('导入子 MCP 失败', error instanceof ApiError ? error.display : String(error))
  }
  finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="card">
    <div class="card-header">
      子 MCP（最多 {{ limit }} 个，当前 {{ gateway.downstreams.length }} 个）
    </div>
    <div class="card-body stack">

      <form id="import-form" class="stack-sm" @submit.prevent="submitImport">
        <div class="field">
          <label for="import-json">粘贴 mcpServers 配置 JSON</label>
          <textarea id="import-json" v-model="importJson" class="control mono nowrap" rows="8"
                    :placeholder="PLACEHOLDER" :disabled="atLimit"></textarea>
          <span class="hint">
            仅支持 <code>streamable-http</code>；<code>command</code> / <code>args</code> /
            <code>env</code> 这类 stdio 配置会被拒绝。导入后会立即同步一次工具。
          </span>
        </div>
        <div class="btn-row">
          <button class="btn btn-primary" type="submit" :disabled="busy || atLimit">
            {{ busy ? '处理中…' : '导入并同步' }}
          </button>
          <button class="btn btn-sm btn-link" type="button" :disabled="atLimit" @click="fillExample">
            填入示例
          </button>
          <span v-if="atLimit" class="small muted">已达到 {{ limit }} 个的上限，先删掉一个再导入。</span>
        </div>
      </form>

      <div v-if="gateway.downstreams.length === 0" class="empty-state">
        还没有子 MCP。粘贴上面的配置 JSON 开始。
      </div>

      <DownstreamCard v-for="downstream in gateway.downstreams" :key="downstream.id"
                      :gateway-id="gateway.id" :downstream="downstream"
                      @replaced="emit('replaced', $event)" @reload="emit('reload')" />
    </div>
  </section>
</template>
