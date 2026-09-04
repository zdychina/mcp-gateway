<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/client'
import { useSession } from '../composables/useSession'
import AppIcon from '../components/AppIcon.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const failure = ref<string | null>(null)

const usernameInput = ref<HTMLInputElement | null>(null)

onMounted(() => {
  usernameInput.value?.focus()
})

/**
 * 登录后要去的地方。
 *
 * 只接受站内的绝对路径：redirect 参数来自地址栏，直接拿去跳转就成了开放重定向 ——
 * 攻击者可以拿一个 /ui/login?redirect=https://... 的链接把刚登录的人送去钓鱼页。
 * 以 // 开头的形式同样是站外地址（协议相对 URL），一并挡掉。
 */
function safeRedirect(): string {
  const target = route.query.redirect
  if (typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')) {
    return target
  }
  return '/gateways'
}

function describe(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '无法连接到网关，请确认服务是否在运行。'
  }
  if (error.code === 'UNAUTHORIZED') {
    // 服务端刻意不区分用户名错和口令错，前端也不要替它猜。
    return '用户名或口令不正确。'
  }
  if (error.code === 'TOO_MANY_ATTEMPTS') {
    return '失败次数过多，该来源已被临时锁定，请稍后再试。'
  }
  return error.display
}

async function submit(): Promise<void> {
  if (submitting.value) {
    return
  }
  submitting.value = true
  failure.value = null
  try {
    await session.signIn(username.value, password.value)
    await router.replace(safeRedirect())
  }
  catch (error) {
    failure.value = describe(error)
    password.value = ''
    await nextTick()
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <form class="card login-card" @submit.prevent="submit">
      <div class="card-header">
        <AppIcon name="lock" :size="16" />
        <span>登录管理端</span>
      </div>

      <div class="card-body stack">
        <div class="field">
          <label for="login-username">用户名</label>
          <input id="login-username" ref="usernameInput" v-model="username" class="control"
                 type="text" autocomplete="username" required :disabled="submitting" />
        </div>

        <div class="field">
          <label for="login-password">口令</label>
          <input id="login-password" v-model="password" class="control"
                 type="password" autocomplete="current-password" required :disabled="submitting" />
        </div>

        <!-- role="alert" 让读屏软件在失败时立刻播报，而不是等用户自己移到这里 -->
        <p v-if="failure" class="login-error" role="alert">
          <AppIcon name="warning" :size="13" />
          <span>{{ failure }}</span>
        </p>

        <button class="btn btn-primary" type="submit" :disabled="submitting">
          {{ submitting ? '登录中…' : '登录' }}
        </button>

        <p class="hint">
          凭证由部署时的环境变量配置（<code>MCP_GATEWAY_ADMIN_USERNAME</code> /
          <code>MCP_GATEWAY_ADMIN_PASSWORD</code>），修改后需重启网关。
        </p>
      </div>
    </form>
  </div>
</template>
