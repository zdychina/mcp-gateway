<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { gatewayApi } from '../api/gateways'
import { ApiError } from '../api/client'
import type { GatewayDetail, GatewayTool } from '../api/types'
import { formatDateTime } from '../utils/datetime'
import { useAlerts } from '../composables/useAlerts'
import GatewayBasicsCard from '../components/detail/GatewayBasicsCard.vue'
import DownstreamSection from '../components/detail/DownstreamSection.vue'
import ToolsSection from '../components/detail/ToolsSection.vue'
import AgentAccessCard from '../components/detail/AgentAccessCard.vue'

/*
 * 需求 10.2 的四段：基本信息 / 子 MCP 配置 / 聚合工具 / Agent 接入。
 *
 * 这里只做编排：持有 gateway 这一份状态，接住各段的变更。
 * 大多数写接口本来就返回完整的 GatewayDetailResponse，所以直接用返回值替换状态即可，
 * 不需要额外再取一次 —— Thymeleaf 版本每次变更都 location.reload()，
 * 提示框显示 600ms 就被冲掉，用户根本来不及读。
 */
const route = useRoute()
const router = useRouter()
const alerts = useAlerts()

const gatewayId = computed(() => String(route.params.id ?? ''))

const gateway = ref<GatewayDetail | null>(null)
const loading = ref(true)
const notFound = ref(false)

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  try {
    gateway.value = await gatewayApi.detail(gatewayId.value)
  }
  catch (error) {
    if (error instanceof ApiError && error.code === 'GATEWAY_NOT_FOUND') {
      notFound.value = true
    }
    else {
      alerts.error('加载网关失败', error instanceof ApiError ? error.display : String(error))
    }
  }
  finally {
    loading.value = false
  }
}

watch(gatewayId, load, { immediate: true })

/** 写接口返回了完整详情，直接换掉本地状态。 */
function replace(detail: GatewayDetail): void {
  gateway.value = detail
}

/** 同步之后工具快照变了，而同步接口只返回统计，得重新取一次。 */
function reload(): void {
  void load()
}

/** 启停或改描述只影响一条工具，就地替换，不必重取整个详情。 */
function replaceTool(updated: GatewayTool): void {
  const current = gateway.value
  if (!current) {
    return
  }
  for (const downstream of current.downstreams) {
    const index = downstream.tools.findIndex(tool => tool.id === updated.id)
    if (index >= 0) {
      downstream.tools[index] = updated
      return
    }
  }
}

async function onDeleted(): Promise<void> {
  alerts.success('网关已删除', '已返回网关列表。')
  await router.push('/gateways')
}
</script>

<template>
  <div v-if="loading" class="empty-state">加载中…</div>

  <div v-else-if="notFound" class="card">
    <div class="empty-state stack">
      <p>没有找到这个网关，它可能已经被删除。</p>
      <div><RouterLink class="btn" to="/gateways">返回网关列表</RouterLink></div>
    </div>
  </div>

  <template v-else-if="gateway">
    <nav class="breadcrumb" aria-label="面包屑">
      <RouterLink to="/gateways">网关列表</RouterLink>
      <span aria-hidden="true">/</span>
      <span>{{ gateway.name }}</span>
    </nav>

    <div class="page-head">
      <h1>{{ gateway.name }}</h1>
      <RouterLink class="btn" :to="`/gateways/${gateway.id}/calls`">查看调用记录</RouterLink>
    </div>

    <GatewayBasicsCard :gateway="gateway" @updated="replace" @deleted="onDeleted" />

    <DownstreamSection :gateway="gateway" @replaced="replace" @reload="reload" />

    <ToolsSection :gateway-id="gateway.id" :downstreams="gateway.downstreams"
                  @tool-updated="replaceTool" />

    <!-- slug 改了 mcpUrl 就变了，用它做 key 让接入 JSON 自动重取 -->
    <AgentAccessCard :key="gateway.mcpUrl" :gateway-id="gateway.id" :mcp-url="gateway.mcpUrl" />

    <p class="small muted">
      创建于 {{ formatDateTime(gateway.createdAt) }}　·　
      更新于 {{ formatDateTime(gateway.updatedAt) }}
    </p>
  </template>
</template>
