<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { gatewayApi } from '../../api/gateways'
import { ApiError } from '../../api/client'
import type { AgentConfig } from '../../api/types'
import { useAlerts } from '../../composables/useAlerts'
import CopyField from '../CopyField.vue'
import TokenReveal from '../TokenReveal.vue'

const props = defineProps<{ gatewayId: string, mcpUrl: string }>()

const alerts = useAlerts()
const config = ref<AgentConfig | null>(null)
const busy = ref(false)
/** 明文令牌只活在这个 ref 里，不写进任何持久化存储。 */
const freshToken = ref<string | null>(null)

/** 缩进过的版本更适合直接粘进 Agent 的配置文件。 */
const configJson = computed(() => config.value ? JSON.stringify(config.value, null, 2) : '')

function describe(error: unknown): string {
  return error instanceof ApiError ? error.display : String(error)
}

async function loadConfig(): Promise<void> {
  try {
    config.value = await gatewayApi.agentConfig(props.gatewayId)
  }
  catch (error) {
    alerts.error('取接入 JSON 失败', describe(error))
  }
}

onMounted(loadConfig)


async function rotate(): Promise<void> {
  if (!window.confirm('确定轮换访问令牌？\n\n旧令牌立即失效，正在使用它的 Agent 会断开连接。')) {
    return
  }
  busy.value = true
  try {
    const rotated = await gatewayApi.rotateToken(props.gatewayId)
    freshToken.value = rotated.accessToken
    alerts.success('令牌已轮换', '新令牌只显示这一次，请立即复制保存。')
  }
  catch (error) {
    alerts.error('轮换令牌失败', describe(error))
  }
  finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="card">
    <div class="card-header">Agent 接入</div>
    <div class="card-body stack">
      <CopyField label="MCP 地址" :value="mcpUrl" />

      <CopyField label="接入 JSON" :value="configJson" multiline :rows="12" preserve-whitespace />
      <span class="hint">
        把 <code>&lt;gateway-access-token&gt;</code> 换成实际令牌。服务端只保存令牌哈希，
        拿不回明文；忘了就轮换一个新的。
      </span>

      <div class="btn-row">
        <button class="btn btn-danger" type="button" :disabled="busy" @click="rotate">
          轮换访问令牌
        </button>
        <span class="hint">旧令牌立即失效，已接入的 Agent 会断开。</span>
      </div>

      <TokenReveal v-if="freshToken" :token="freshToken" title="新的访问令牌"
                   @dismiss="freshToken = null" />
    </div>
  </section>
</template>
