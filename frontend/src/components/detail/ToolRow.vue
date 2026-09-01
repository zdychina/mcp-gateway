<script setup lang="ts">
import { ref, watch } from 'vue'
import { toolApi } from '../../api/gateways'
import { ApiError } from '../../api/client'
import type { GatewayTool } from '../../api/types'
import { formatDateTime, formatRelative } from '../../utils/datetime'
import { useAlerts } from '../../composables/useAlerts'

const props = defineProps<{ gatewayId: string, tool: GatewayTool }>()
const emit = defineEmits<{ updated: [GatewayTool] }>()

const alerts = useAlerts()
const savingDescription = ref(false)
const togglingEnabled = ref(false)

/** 本地草稿。服务端返回新值后跟着同步，避免保存完还显示旧内容。 */
const draft = ref('')
watch(() => props.tool.customDescription, value => { draft.value = value ?? '' }, { immediate: true })

function describe(error: unknown): string {
  return error instanceof ApiError ? error.display : String(error)
}

async function toggle(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const enabled = input.checked

  togglingEnabled.value = true
  try {
    const updated = await toolApi.update(props.gatewayId, props.tool.id, { enabled })
    emit('updated', updated)
    alerts.success(enabled ? '工具已启用' : '工具已停用',
      enabled
        ? `${props.tool.exposedName} 立即可被发现和调用。`
        : `${props.tool.exposedName} 已从 tools/list 移除，调用请求不会转发到下游。`)
  }
  catch (error) {
    // 服务端没改成，界面上的开关也要拨回去，否则显示的状态是假的
    input.checked = !enabled
    alerts.error('修改启用状态失败', describe(error))
  }
  finally {
    togglingEnabled.value = false
  }
}

async function saveDescription(): Promise<void> {
  const value = draft.value.trim()
  savingDescription.value = true
  try {
    /*
     * 需求 6.5.5：显式传 null 表示清除并回退到原始描述。
     * "不传这个字段"才是"不改动" —— 两者不能混，见 UpdateToolRequest 的说明。
     */
    const updated = await toolApi.update(props.gatewayId, props.tool.id,
      { customDescription: value === '' ? null : value })
    emit('updated', updated)
    alerts.success('描述已保存',
      value === '' ? '已清空，回退到下游的原始描述。' : undefined)
  }
  catch (error) {
    alerts.error('保存描述失败', describe(error))
  }
  finally {
    savingDescription.value = false
  }
}
</script>

<template>
  <tr :class="{ disabled: !tool.enabled }">
    <td>
      <label class="switch">
        <input type="checkbox" :checked="tool.enabled" :disabled="togglingEnabled"
               :aria-label="`启用 ${tool.exposedName}`" @change="toggle">
        <span></span>
      </label>
    </td>
    <td class="mono small">{{ tool.exposedName }}</td>
    <td class="mono small muted">{{ tool.originalName }}</td>
    <td>
      <div class="small muted mb-1">
        原始描述：{{ tool.originalDescription || '（无）' }}
      </div>
      <div class="input-group">
        <textarea v-model="draft" class="control" rows="2" maxlength="4000"
                  :aria-label="`${tool.exposedName} 的自定义描述`"
                  placeholder="自定义描述，留空则回退到原始描述"></textarea>
        <button class="btn btn-sm" type="button" :disabled="savingDescription"
                @click="saveDescription">保存</button>
      </div>
    </td>
    <td class="small muted" :title="formatDateTime(tool.lastSyncedAt)">
      {{ formatRelative(tool.lastSyncedAt) }}
    </td>
  </tr>
</template>
