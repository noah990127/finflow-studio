<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Check, LoaderCircle, Settings2, Trash2, X } from 'lucide-vue-next'
import { api, type AgentModelSettings } from '../api/client'

const props = defineProps<{ sessionId: string; disabled: boolean }>()
const saved = ref<AgentModelSettings>({ mode: 'DEFAULT', baseUrl: '', model: '', hasKey: false })
const dialog = ref<HTMLDialogElement | null>(null)
const mode = ref<'DEFAULT' | 'CUSTOM'>('DEFAULT')
const baseUrl = ref('')
const model = ref('')
const apiKey = ref('')
const busy = ref(false)
const loading = ref(false)
const ready = ref(false)
const error = ref('')
const result = ref('')
const label = computed(() => !ready.value ? '模型设置' : saved.value.mode === 'CUSTOM' ? saved.value.model : '默认模型')
const valid = computed(() => ready.value && (mode.value === 'DEFAULT' || (baseUrl.value.trim() && model.value.trim()
  && (apiKey.value.trim() || (saved.value.hasKey && baseUrl.value.trim().replace(/\/+$/, '') === saved.value.baseUrl)))))
const payload = () => ({ mode: mode.value, baseUrl: baseUrl.value, model: model.value, apiKey: apiKey.value })

watch(() => props.sessionId, async id => {
  dialog.value?.close()
  apiKey.value = ''
  saved.value = { mode: 'DEFAULT', baseUrl: '', model: '', hasKey: false }
  ready.value = false
  error.value = ''
  if (!id) return
  loading.value = true
  try {
    const value = await api.getAgentModel(id)
    if (id === props.sessionId) { saved.value = value; ready.value = true }
  } catch { if (id === props.sessionId) error.value = '模型配置暂时无法读取，请重试' }
  finally { if (id === props.sessionId) loading.value = false }
}, { immediate: true })

watch([mode, baseUrl, model, apiKey], () => { result.value = ''; error.value = '' })
watch(() => props.disabled, disabled => { if (disabled && !busy.value) close() })

async function open() {
  if (!props.sessionId || props.disabled || loading.value) return
  const id = props.sessionId
  loading.value = true
  try {
    const value = await api.getAgentModel(id)
    if (id !== props.sessionId) return
    saved.value = value
    ready.value = true
  } catch { ready.value = false }
  finally { if (id === props.sessionId) loading.value = false }
  if (id !== props.sessionId || props.disabled) return
  mode.value = saved.value.mode
  baseUrl.value = saved.value.baseUrl
  model.value = saved.value.model
  apiKey.value = ''
  await nextTick()
  if (!ready.value) error.value = '模型配置暂时无法读取，请关闭后重试'
  dialog.value?.showModal()
}
function close() { dialog.value?.close(); apiKey.value = ''; result.value = ''; error.value = '' }
async function perform(action: 'save' | 'test' | 'clear') {
  if (props.disabled || busy.value) return
  const session = props.sessionId
  busy.value = true
  error.value = ''; result.value = ''
  try {
    if (action === 'test') {
      const response = await api.testAgentModel(session, payload())
      if (session !== props.sessionId) return
      if (response.success) result.value = response.message
      else error.value = response.message
    } else {
      if (action === 'clear') await api.clearAgentModel(session)
      const response = action === 'clear' ? await api.getAgentModel(session) : await api.saveAgentModel(session, payload())
      if (session !== props.sessionId) return
      saved.value = response
      close()
    }
  } catch (cause) {
    if (session === props.sessionId) error.value = cause instanceof Error ? cause.message : '设置未完成，请重试'
  } finally { busy.value = false }
}
</script>

<template>
  <button class="agent-model-trigger" type="button" :disabled="disabled || loading || !sessionId" title="配置当前对话的 Agent 模型" @click="open">
    <Settings2 :size="13" /><span>{{ loading ? '读取模型配置' : label }}</span>
  </button>
  <dialog ref="dialog" class="agent-model-dialog" aria-labelledby="agent-model-title" @cancel.prevent="!busy && close()" @close="apiKey = ''">
    <form @submit.prevent="perform('save')">
      <header><h2 id="agent-model-title">Agent 模型</h2><button class="icon-button" type="button" title="关闭模型设置" :disabled="busy" @click="close"><X :size="18" /></button></header>
      <div class="agent-model-scope">当前对话</div>
      <fieldset :disabled="busy">
        <div class="agent-model-modes">
          <label><input v-model="mode" type="radio" value="DEFAULT" name="model-mode" />默认模型</label>
          <label><input v-model="mode" type="radio" value="CUSTOM" name="model-mode" />自定义模型</label>
        </div>
        <template v-if="mode === 'CUSTOM'">
          <label class="agent-model-field">API 地址（OpenAI 兼容）<input v-model="baseUrl" type="url" autocomplete="off" placeholder="https://api.example.com/v1" required maxlength="2000" /></label>
          <label class="agent-model-field">模型名称<input v-model="model" autocomplete="off" placeholder="服务商提供的模型 ID" required maxlength="200" /></label>
          <label class="agent-model-field">API Key<input v-model="apiKey" type="password" autocomplete="new-password" :placeholder="saved.hasKey ? '已保存，留空保持不变' : '输入 API Key'" maxlength="4096" /></label>
          <p class="agent-model-notice">对话内容及必要的项目上下文将发送至该服务商。</p>
        </template>
      </fieldset>
      <p v-if="error" class="agent-model-error" role="alert">{{ error }}</p>
      <p v-if="result" class="agent-model-success" role="status"><Check :size="14" />{{ result }}</p>
      <footer>
        <button v-if="saved.hasKey" class="icon-button" type="button" title="清除自定义配置和密钥" :disabled="busy" @click="perform('clear')"><Trash2 :size="16" /></button>
        <span class="agent-model-spacer"></span>
        <button v-if="mode === 'CUSTOM'" class="secondary-button" type="button" :disabled="busy || !valid" @click="perform('test')">测试连接</button>
        <button class="primary-button" type="submit" :disabled="busy || !valid"><LoaderCircle v-if="busy" :size="14" class="assistant-spinner" />{{ busy ? '处理中' : '保存' }}</button>
      </footer>
    </form>
  </dialog>
</template>

<style scoped>
.agent-model-trigger { display: inline-flex; align-items: center; gap: 6px; max-width: 100%; padding: 5px 0; border: 0; background: transparent; color: #52616c; font: inherit; font-size: 12px; cursor: pointer; }
.agent-model-trigger span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.agent-model-trigger:disabled { opacity: .55; cursor: default; }
.agent-model-dialog { width: min(440px, calc(100vw - 32px)); box-sizing: border-box; max-height: calc(100dvh - 40px); overflow: auto; border: 1px solid #d6dce0; border-radius: 8px; padding: 22px; color: #27333c; background: white; box-shadow: 0 16px 56px #0002; }
.agent-model-dialog::backdrop { background: #15212e66; }
header, footer { display: flex; align-items: center; gap: 10px; }
header { justify-content: space-between; }
h2 { margin: 0; font-size: 17px; }
.agent-model-scope { color: #73808a; font-size: 12px; margin: 5px 0 18px; }
fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
.agent-model-modes { display: flex; flex-wrap: wrap; gap: 22px; font-size: 13px; margin-bottom: 18px; }
.agent-model-modes label { display: flex; align-items: center; gap: 6px; }
.agent-model-field { display: grid; gap: 7px; font-size: 13px; margin-top: 15px; }
.agent-model-field input { min-width: 0; width: 100%; box-sizing: border-box; border: 1px solid #cdd5da; border-radius: 5px; padding: 9px 10px; font: inherit; background: #fff; color: #27333c; }
.agent-model-field input:focus { outline: 2px solid #88b9b0; outline-offset: 1px; }
.agent-model-notice { font-size: 12px; line-height: 1.6; color: #697780; }
.agent-model-error, .agent-model-success { font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.agent-model-error { color: #b42332; }
.agent-model-success { color: #16704b; display: flex; gap: 6px; align-items: center; }
footer { margin-top: 22px; }
.agent-model-spacer { flex: 1; }
</style>
