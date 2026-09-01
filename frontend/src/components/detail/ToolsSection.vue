<script setup lang="ts">
import type { DownstreamMcp, GatewayTool } from '../../api/types'
import ToolRow from './ToolRow.vue'

defineProps<{ gatewayId: string, downstreams: DownstreamMcp[] }>()
const emit = defineEmits<{ toolUpdated: [GatewayTool] }>()
</script>

<template>
  <section class="card">
    <div class="card-header">聚合工具</div>
    <div class="card-body stack">
      <p v-if="downstreams.length === 0" class="muted" style="margin: 0">
        先配置子 MCP 并同步，工具会出现在这里。
      </p>

      <div v-for="downstream in downstreams" :key="downstream.id">
        <h2 class="section-title mono">{{ downstream.name }}</h2>

        <p v-if="downstream.tools.length === 0" class="muted small" style="margin: 0">
          这个子 MCP 还没有同步到工具。
        </p>

        <div v-else class="table-wrap">
          <table class="table tools">
            <thead>
              <tr>
                <th style="width: 4.5rem">启用</th>
                <th>聚合工具名</th>
                <th>原工具名</th>
                <th>描述</th>
                <th style="width: 8rem">最近同步</th>
              </tr>
            </thead>
            <tbody>
              <ToolRow v-for="tool in downstream.tools" :key="tool.id"
                       :gateway-id="gatewayId" :tool="tool"
                       @updated="emit('toolUpdated', $event)" />
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </section>
</template>
