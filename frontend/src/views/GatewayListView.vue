<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { gatewayApi } from '../api/gateways'
import { ApiError } from '../api/client'
import type { GatewaySummary } from '../api/types'
import { formatDateTime, formatRelative } from '../utils/datetime'
import { useAlerts } from '../composables/useAlerts'
import StatusBadge from '../components/StatusBadge.vue'
import TokenReveal from '../components/TokenReveal.vue'
import AppIcon from '../components/AppIcon.vue'
import TableSkeleton from '../components/TableSkeleton.vue'

const alerts = useAlerts()

const gateways = ref<GatewaySummary[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const busy = ref(false)

const keyword = ref('')
const showCreate = ref(false)
/** 明文令牌只在内存里活到用户关掉面板为止，不写进任何持久化存储。 */
const freshToken = ref<string | null>(null)

const form = ref({ name: '', slug: '', description: '' })

const SLUG_PATTERN = /^[A-Za-z0-9_-]{1,64}$/

const filtered = computed(() => {
  const needle = keyword.value.trim().toLowerCase()
  if (!needle) {
    return gateways.value
  }
  return gateways.value.filter(gateway =>
    gateway.name.toLowerCase().includes(needle)
    || gateway.slug.toLowerCase().includes(needle)
    || (gateway.description ?? '').toLowerCase().includes(needle))
})

function describe(error: unknown): string {
  return error instanceof ApiError ? error.display : String(error)
}

/**
 * 重新拉取列表。
 *
 * 这是 Vue 版相对 Thymeleaf 版最直接的收益：变更之后只刷新数据，不整页重载，
 * 所以提示框留得住、滚动位置不动、刚显示出来的令牌也不会被冲掉。
 */
async function reload(): Promise<void> {
  try {
    gateways.value = await gatewayApi.list()
    loadFailed.value = false
  }
  catch (error) {
    loadFailed.value = true
    alerts.error('加载网关列表失败', describe(error))
  }
  finally {
    loading.value = false
  }
}

onMounted(reload)

async function submitCreate(): Promise<void> {
  const name = form.value.name.trim()
  const slug = form.value.slug.trim()
  const description = form.value.description.trim()

  if (!SLUG_PATTERN.test(slug)) {
    alerts.error('slug 不合法', '只能包含字母、数字、短横线和下划线，长度 1–64。')
    return
  }

  busy.value = true
  try {
    const created = await gatewayApi.create({
      name,
      slug,
      description: description === '' ? null : description
    })
    freshToken.value = created.accessToken
    alerts.success('网关已创建', '令牌显示在下方，只显示这一次。')
    form.value = { name: '', slug: '', description: '' }
    showCreate.value = false
    await reload()
  }
  catch (error) {
    alerts.error('创建网关失败', describe(error))
  }
  finally {
    busy.value = false
  }
}

async function remove(gateway: GatewaySummary): Promise<void> {
  // 需求 6.1.7：删除必须二次确认，并说清连带影响。
  // 原生 confirm 是临时方案，第 4 步接组件库时换成可样式化的对话框。
  const confirmed = window.confirm(
    `确定删除网关「${gateway.name}」？\n\n`
    + '它的子 MCP 配置、工具快照和全部调用记录都会一并删除，且无法恢复。')
  if (!confirmed) {
    return
  }

  busy.value = true
  try {
    await gatewayApi.remove(gateway.id)
    alerts.success('网关已删除', `「${gateway.name}」及其全部关联数据已移除。`)
    await reload()
  }
  catch (error) {
    alerts.error('删除网关失败', describe(error))
  }
  finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="page-head">
    <h1>网关列表</h1>
    <button class="btn btn-primary" type="button" @click="showCreate = !showCreate">
      {{ showCreate ? '收起' : '创建网关' }}
    </button>
  </div>

  <!-- 创建表单 -->
  <div v-if="showCreate" class="card">
    <div class="card-header">创建网关</div>
    <div class="card-body stack">
      <form class="grid" @submit.prevent="submitCreate">
        <div class="field">
          <label for="create-name">名称</label>
          <input id="create-name" v-model="form.name" class="control" maxlength="64" required>
          <span class="hint">1–64 个字符</span>
        </div>
        <div class="field">
          <label for="create-slug">标识 slug</label>
          <input id="create-slug" v-model="form.slug" class="control mono" maxlength="64" required>
          <span class="hint">字母、数字、短横线和下划线；决定 Agent 的 MCP 地址</span>
        </div>
        <div class="field">
          <label for="create-description">描述（可选）</label>
          <input id="create-description" v-model="form.description" class="control" maxlength="4000">
          <span class="hint">会作为 instructions 传给 Agent</span>
        </div>
        <div class="field" style="justify-content: flex-end">
          <div class="btn-row">
            <button class="btn btn-primary" type="submit" :disabled="busy">
              {{ busy ? '处理中…' : '创建' }}
            </button>
            <button class="btn btn-link" type="button" @click="showCreate = false">取消</button>
          </div>
        </div>
      </form>
    </div>
  </div>

  <!-- 需求 FR-05.3：令牌只完整显示一次 -->
  <TokenReveal v-if="freshToken" :token="freshToken" @dismiss="freshToken = null" />

  <div class="toolbar">
    <input v-model="keyword" class="control search" type="search"
           placeholder="搜索名称、slug 或描述" aria-label="搜索网关">
    <span class="muted small">
      共 {{ gateways.length }} 个网关<span v-if="keyword.trim()">，命中 {{ filtered.length }} 个</span>
    </span>
  </div>

  <div class="card">
    <TableSkeleton v-if="loading" />

    <div v-else-if="loadFailed" class="empty-state">
      <AppIcon name="warning" :size="28" />
      <div class="title">没能取到网关列表</div>
      <button class="btn" type="button" @click="reload">重试</button>
    </div>

    <div v-else-if="gateways.length === 0" class="empty-state">
      <AppIcon name="inbox" :size="30" />
      <div class="title">还没有网关</div>
      <p>点右上角「创建网关」开始：一个网关聚合最多 3 个子 MCP，对 Agent 只暴露一个地址和一个令牌。</p>
    </div>

    <div v-else-if="filtered.length === 0" class="empty-state">
      <AppIcon name="search" :size="28" />
      <div class="title">没有匹配「{{ keyword }}」的网关</div>
    </div>

    <div v-else class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>slug</th>
            <th>状态</th>
            <th class="num">子 MCP</th>
            <th class="num">工具</th>
            <th>更新时间</th>
            <th class="actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="gateway in filtered" :key="gateway.id">
            <td>
              <RouterLink :to="`/gateways/${gateway.id}`">{{ gateway.name }}</RouterLink>
              <div v-if="gateway.description" class="small muted">{{ gateway.description }}</div>
            </td>
            <td class="mono small">{{ gateway.slug }}</td>
            <td><StatusBadge :status="gateway.status" /></td>
            <td class="num">{{ gateway.downstreamCount }}</td>
            <td class="num">{{ gateway.toolCount }}</td>
            <td class="small muted" :title="formatDateTime(gateway.updatedAt)">
              {{ formatRelative(gateway.updatedAt) }}
            </td>
            <td class="actions">
              <div class="btn-row" style="justify-content: flex-end">
                <RouterLink class="btn btn-sm" :to="`/gateways/${gateway.id}`">详情</RouterLink>
                <button class="btn btn-sm btn-danger" type="button" :disabled="busy"
                        @click="remove(gateway)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
