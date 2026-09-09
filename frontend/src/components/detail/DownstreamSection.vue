<script setup lang="ts">
import { computed, ref } from 'vue'
import { downstreamApi } from '../../api/gateways'
import { ApiError } from '../../api/client'
import type { GatewayDetail } from '../../api/types'
import { useAlerts } from '../../composables/useAlerts'
import { describeSyncResults } from '../../utils/sync'
import AppIcon from '../AppIcon.vue'
import DownstreamCard from './DownstreamCard.vue'

const props = defineProps<{ gateway: GatewayDetail, maxDownstreams?: number }>()
const emit = defineEmits<{ replaced: [GatewayDetail], reload: [] }>()

const alerts = useAlerts()
const busy = ref(false)
const importJson = ref('')

/*
 * 新增子 MCP 的两种输入方式。
 *
 * 表单和 JSON 是**同一份数据的两种视图**，切换时互相灌一次 —— 不是两条独立的路。
 * 提交时两种模式最终都构造出同一个 mcpServers 对象走同一个导入接口：
 * 服务端只有这一条创建路径，表单也就不可能和 JSON 在校验语义上慢慢漂开。
 */
type InputMode = 'form' | 'json'

const mode = ref<InputMode>('form')

interface HeaderRow {
  name: string
  value: string
}

const form = ref<{ name: string, url: string, headers: HeaderRow[] }>({
  name: '',
  url: '',
  headers: [{ name: '', value: '' }]
})

function addHeaderRow(): void {
  form.value.headers.push({ name: '', value: '' })
}

function removeHeaderRow(index: number): void {
  form.value.headers.splice(index, 1)
  if (form.value.headers.length === 0) {
    addHeaderRow()
  }
}

function resetForm(): void {
  form.value = { name: '', url: '', headers: [{ name: '', value: '' }] }
}

/** 表单 → mcpServers 对象。名字空着也照样构造，让服务端去报那个错。 */
function formToConfig(): Record<string, unknown> {
  const headers: Record<string, string> = {}
  for (const row of form.value.headers) {
    // 只留有名字的行：留一行空的是给下次输入用的，不该变成一个空 header
    if (row.name.trim() !== '') {
      headers[row.name.trim()] = row.value
    }
  }

  const server: Record<string, unknown> = {
    type: 'streamable-http',
    url: form.value.url.trim()
  }
  if (Object.keys(headers).length > 0) {
    server.headers = headers
  }
  return { mcpServers: { [form.value.name.trim()]: server } }
}

/**
 * JSON → 表单。
 *
 * 只在"恰好一个子 MCP"时才回填 —— 表单一次只描述一个，JSON 里有两个的话
 * 硬塞进表单必然丢东西，那还不如老实说清楚、留在 JSON 模式。
 *
 * @returns 没能回填时的原因
 */
function configToForm(raw: string): string | null {
  if (raw.trim() === '') {
    return null
  }

  let parsed: { mcpServers?: Record<string, Record<string, unknown>> }
  try {
    parsed = JSON.parse(raw)
  }
  catch {
    return 'JSON 还解析不了，先修好再切到表单。'
  }

  const servers = parsed?.mcpServers
  if (!servers || typeof servers !== 'object') {
    return 'JSON 里没有 mcpServers，切不过去。'
  }
  const names = Object.keys(servers)
  if (names.length === 0) {
    return null
  }
  if (names.length > 1) {
    return `JSON 里有 ${names.length} 个子 MCP，表单一次只能填一个 —— 继续用 JSON 模式导入。`
  }

  const name = names[0]
  const server = servers[name] ?? {}
  const headers = (server.headers ?? {}) as Record<string, string>
  const rows = Object.entries(headers).map(([key, value]) => ({ name: key, value: String(value) }))

  form.value = {
    name,
    url: typeof server.url === 'string' ? server.url : '',
    headers: rows.length > 0 ? rows : [{ name: '', value: '' }]
  }
  return null
}

/** 切换输入方式，顺手把当前内容灌到另一边。 */
function switchMode(next: InputMode): void {
  if (next === mode.value) {
    return
  }

  if (next === 'json') {
    // 表单是空的就不要生成一段 {"": {...}} 的垃圾 JSON 覆盖掉用户原来粘的东西
    if (form.value.name.trim() !== '' || form.value.url.trim() !== '') {
      importJson.value = JSON.stringify(formToConfig(), null, 2)
    }
    mode.value = 'json'
    return
  }

  const reason = configToForm(importJson.value)
  if (reason) {
    alerts.warning('这段 JSON 没法用表单表示', reason)
    return
  }
  mode.value = 'form'
}

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
  let parsed: unknown

  if (mode.value === 'form') {
    if (form.value.name.trim() === '' || form.value.url.trim() === '') {
      alerts.error('名称和 URL 都要填', undefined)
      return
    }
    parsed = formToConfig()
  }
  else {
    const raw = importJson.value.trim()
    if (raw === '') {
      alerts.error('请先粘贴配置 JSON', undefined)
      return
    }
    try {
      parsed = JSON.parse(raw)
    }
    catch (error) {
      alerts.error('配置 JSON 格式错误', error instanceof Error ? error.message : String(error))
      return
    }
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
    resetForm()
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

      <!--
        部署把下游 TLS 校验关掉时，这条提示常驻。
        用 .notice 而不是 .alert：它说的是"这个部署现在是什么状态"，不是"刚刚发生了什么"，
        不会自己消失，也不该被 AlertStack 的关闭按钮收掉。
      -->
      <p v-if="gateway.insecureDownstreamTls" class="notice notice-danger">
        <strong>下游 TLS 校验已关闭。</strong>
        子 MCP 的证书和主机名都不再验证，下面配置的凭证会在无法确认对方身份的连接上传输。
        这由部署时的 <code>MCP_GATEWAY_DOWNSTREAM_INSECURE_SKIP_TLS_VERIFY</code> 决定，
        界面上改不了。
      </p>

      <form id="import-form" @submit.prevent="submitImport">
        <div class="add-head">
          <strong class="add-title">新增子 MCP</strong>
          <!-- 两种输入方式是同一份数据的两种视图，切换时互相灌一次 -->
          <div class="mode-switch" role="group" aria-label="输入方式">
            <button type="button" class="chip-btn" :class="{ active: mode === 'form' }"
                    :aria-pressed="mode === 'form'" @click="switchMode('form')">填表单</button>
            <button type="button" class="chip-btn" :class="{ active: mode === 'json' }"
                    :aria-pressed="mode === 'json'" @click="switchMode('json')">粘 JSON</button>
          </div>
        </div>

        <template v-if="mode === 'form'">
          <div class="form-grid">
            <div class="field">
              <label for="new-ds-name">名称</label>
              <input id="new-ds-name" v-model="form.name" class="control mono" maxlength="64"
                     placeholder="knowledge_base_a" :disabled="atLimit">
              <span class="hint">工具名会带上这个前缀，如 <code>{{ form.name || 'kb_a' }}__search</code></span>
            </div>
            <div class="field span-2">
              <label for="new-ds-url">URL</label>
              <input id="new-ds-url" v-model="form.url" class="control mono"
                     placeholder="https://example.com/mcp" :disabled="atLimit">
              <span class="hint">
                仅支持 <code>streamable-http</code>；stdio 那套 <code>command</code> /
                <code>args</code> / <code>env</code> 会被拒绝。
              </span>
            </div>

            <div class="field span-all">
              <!--
                这里不能用 <label>：一组键值对没有"那一个"控件可以指向。
                用 span 顶名字，再用 role="group" + aria-labelledby 把它绑到整组上。
              -->
              <span id="new-ds-headers-label" class="field-label">Headers（可选）</span>
              <div role="group" aria-labelledby="new-ds-headers-label">
              <div v-for="(row, index) in form.headers" :key="index" class="header-row">
                <input v-model="row.name" class="control mono" placeholder="Authorization"
                       :aria-label="`第 ${index + 1} 个 header 的名称`" :disabled="atLimit">
                <input v-model="row.value" class="control mono" placeholder="Bearer 真实令牌"
                       :aria-label="`第 ${index + 1} 个 header 的值`" :disabled="atLimit">
                <button class="btn btn-sm" type="button" :disabled="atLimit"
                        :aria-label="`删除第 ${index + 1} 个 header`"
                        @click="removeHeaderRow(index)">
                  <AppIcon name="close" :size="13" />
                </button>
              </div>
              <div>
                <button class="btn btn-sm btn-link" type="button" :disabled="atLimit"
                        @click="addHeaderRow">+ 再加一个 header</button>
              </div>
              </div>
              <span class="hint">凭证会加密后落库，页面上永远只显示遮罩值。</span>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="field">
            <label for="import-json">粘贴 mcpServers 配置 JSON</label>
            <textarea id="import-json" v-model="importJson" class="control mono nowrap" rows="8"
                      :placeholder="PLACEHOLDER" :disabled="atLimit"></textarea>
            <span class="hint">
              仅支持 <code>streamable-http</code>；<code>command</code> / <code>args</code> /
              <code>env</code> 这类 stdio 配置会被拒绝。一次可以放多个子 MCP。
            </span>
          </div>
        </template>

        <div class="form-actions">
          <button v-if="mode === 'json'" class="btn btn-sm btn-link" type="button"
                  :disabled="atLimit" @click="fillExample">填入示例</button>
          <span v-if="atLimit" class="small muted">
            已达到 {{ limit }} 个的上限，先删掉一个再新增。
          </span>
          <div class="actions-end">
            <button class="btn btn-primary" type="submit" :disabled="busy || atLimit">
              {{ busy ? '处理中…' : '导入并同步' }}
            </button>
          </div>
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
