<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { toolApi } from '../../api/gateways'
import { ApiError } from '../../api/client'
import type { GatewayTool } from '../../api/types'
import { formatDateTime, formatRelative } from '../../utils/datetime'
import { useAlerts } from '../../composables/useAlerts'
import { AGENT_RELIST_HINT } from '../../utils/sync'

const props = defineProps<{ gatewayId: string, tool: GatewayTool }>()
const emit = defineEmits<{ updated: [GatewayTool] }>()

const alerts = useAlerts()
const savingDescription = ref(false)
const togglingEnabled = ref(false)

/** 本地草稿。服务端返回新值后跟着同步，避免保存完还显示旧内容。 */
const draft = ref('')
watch(() => props.tool.customDescription, value => { draft.value = value ?? '' }, { immediate: true })

/** 没改动就没什么可存的，保存按钮置灰，行里少一个抢眼的可点元素 */
const dirty = computed(() => draft.value.trim() !== (props.tool.customDescription ?? ''))

/*
 * 描述编辑器默认收起。
 *
 * 从前每一行都常驻一个 textarea，五个工具就是满屏空框 —— 而绝大多数时候没人要改描述，
 * 那些框只是在占地方。收起来之后一行就是一行，扫读工具列表快得多。
 */
const editing = ref(false)

function startEditing(): void {
  draft.value = props.tool.customDescription ?? ''
  editing.value = true
}

function cancelEditing(): void {
  draft.value = props.tool.customDescription ?? ''
  editing.value = false
}

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
      (enabled
        ? `${props.tool.exposedName} 立即可被发现和调用。`
        : `${props.tool.exposedName} 已从 tools/list 移除，调用请求不会转发到下游。`)
      + `\n${AGENT_RELIST_HINT}`)
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
    editing.value = false
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
    <td class="toggle-cell">
      <label class="switch">
        <input type="checkbox" :checked="tool.enabled" :disabled="togglingEnabled"
               :aria-label="`启用 ${tool.exposedName}`" @change="toggle">
        <span></span>
      </label>
    </td>
    <td class="mono small tool-name">{{ tool.exposedName }}</td>
    <td class="mono small tool-name muted">{{ tool.originalName }}</td>
    <td class="tool-desc">
      <!-- 收起时只有一行：当前生效的描述 + 它是哪来的。描述长短差很多，截断到两行 -->
      <template v-if="!editing">
        <p class="origin-desc" :title="tool.effectiveDescription ?? ''">
          <span class="label" :class="{ custom: tool.customDescription }">
            {{ tool.customDescription ? '自定义' : '原始' }}
          </span>
          <span v-if="tool.effectiveDescription">{{ tool.effectiveDescription }}</span>
          <span v-else class="subtle">下游没有提供描述</span>
        </p>
        <button class="btn btn-sm btn-link desc-edit" type="button" @click="startEditing">
          {{ tool.customDescription ? '改写描述' : '覆盖描述' }}
        </button>
      </template>

      <template v-else>
        <!-- 编辑时把原始描述亮出来做参照：覆盖之前总得看得见自己在覆盖什么 -->
        <p class="origin-desc" :title="tool.originalDescription ?? ''">
          <span class="label">原始</span>
          <span v-if="tool.originalDescription">{{ tool.originalDescription }}</span>
          <span v-else class="subtle">下游没有提供描述</span>
        </p>
        <textarea v-model="draft" class="control" rows="2" maxlength="4000"
                  :aria-label="`${tool.exposedName} 的自定义描述`"
                  placeholder="自定义描述，留空则回退到原始描述"></textarea>
        <div class="desc-actions">
          <span class="hint">
            {{ dirty ? '未保存' : (tool.customDescription ? '已覆盖原始描述' : '使用原始描述') }}
          </span>
          <div class="btn-row">
            <button class="btn btn-sm btn-link" type="button" :disabled="savingDescription"
                    @click="cancelEditing">取消</button>
            <button class="btn btn-sm btn-primary" type="button"
                    :disabled="savingDescription || !dirty"
                    @click="saveDescription">{{ savingDescription ? '保存中…' : '保存' }}</button>
          </div>
        </div>
      </template>
    </td>
    <td class="small muted synced" :title="formatDateTime(tool.lastSyncedAt)">
      {{ formatRelative(tool.lastSyncedAt) }}
    </td>
  </tr>
</template>
