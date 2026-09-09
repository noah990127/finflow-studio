<script setup lang="ts">
import { Eye, EyeOff, LockKeyhole, LogIn, UserRound } from 'lucide-vue-next'
import { ref } from 'vue'

const emit = defineEmits<{ login: [credentials: { username: string; password: string }] }>()
const username = ref('')
const password = ref('')
const showPassword = ref(false)
const error = ref('')
const submitting = ref(false)

async function submit() {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入账号和密码'
    return
  }
  submitting.value = true
  await new Promise(resolve => window.setTimeout(resolve, 180))
  if (username.value.trim() !== 'test1' || password.value !== 'Pr0d1234') {
    error.value = '账号或密码不正确'
    password.value = ''
    submitting.value = false
    return
  }
  emit('login', { username: username.value.trim(), password: password.value })
}
</script>

<template>
  <main class="login-page">
    <section class="login-brand-panel" aria-label="FinBTP Studio">
      <div class="login-brand-mark">F</div>
      <div class="login-brand-copy">
        <span>FinBTP Studio</span>
        <h1>把资料、数据和分析<br>组织成可交付的成果</h1>
        <p>在一个安静、连续的工作空间里，完成研究、分析、工作流编排和结果生成。</p>
      </div>
      <div class="login-brand-footer">
        <span>个人专注工作台</span>
        <small>数据和项目内容仅在当前工作空间中使用</small>
      </div>
    </section>

    <section class="login-form-panel">
      <form class="login-form" novalidate @submit.prevent="submit">
        <header>
          <span>欢迎回来</span>
          <h2>登录工作台</h2>
          <p>输入账号信息继续你的工作。</p>
        </header>

        <label>
          <span>账号</span>
          <div class="login-input" :class="{ invalid: error }">
            <UserRound :size="18" aria-hidden="true" />
            <input v-model="username" autocomplete="username" autofocus placeholder="请输入账号" @input="error = ''">
          </div>
        </label>

        <label>
          <span>密码</span>
          <div class="login-input" :class="{ invalid: error }">
            <LockKeyhole :size="18" aria-hidden="true" />
            <input v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" @input="error = ''">
            <button type="button" :title="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
              <EyeOff v-if="showPassword" :size="17" />
              <Eye v-else :size="17" />
            </button>
          </div>
        </label>

        <p v-if="error" class="login-error" role="alert">{{ error }}</p>
        <button class="login-submit" type="submit" :disabled="submitting">
          <LogIn :size="18" />
          {{ submitting ? '正在登录' : '登录' }}
        </button>
        <small class="login-session-note">为保护工作内容，关闭浏览器后需要重新登录。</small>
      </form>
    </section>
  </main>
</template>
