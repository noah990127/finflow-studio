<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Code2, RefreshCw, Play, Square, ExternalLink } from 'lucide-vue-next'
import { api, type AgentWork, type FixedWorkState, type AssistantEvent, type WorkspaceResource } from '../api/client'
import { useAssistantStore } from '../stores/assistant'
import { browserInteraction } from '../domain/browserInteraction'

const props = defineProps<{ work: AgentWork; projectId: string; resources: WorkspaceResource[] }>()
const emit = defineEmits<{ executed: []; state: [sessionId: string, state: FixedWorkState] }>()
const assistant = useAssistantStore()
const open = ref(false), loading = ref(false), stopping = ref(false), error = ref('')
const events = ref<AssistantEvent[]>([]), runId = ref('')
const fixed = ref<FixedWorkState | null>(null), starting = ref(false), fixedLoading = ref(true)
const fixedError = ref(''), historicalDefinitions = ref<Record<string, NonNullable<FixedWorkState['definition']>>>({})
const browserTasks = ref<Awaited<ReturnType<typeof api.browserAgentTasks>>>([])
const executionDetails = ref<Record<string, Awaited<ReturnType<typeof api.fixedExecutionDetails>>>>({})
async function loadExecution(id: string) {
  const project = props.projectId, session = props.work.sessionId
  try {
    const details = await api.fixedExecutionDetails(project, session, id)
    if (props.projectId === project && props.work.sessionId === session) executionDetails.value[id] = details
  } catch (e) { error.value = e instanceof Error ? e.message : '执行记录读取失败' }
}
const procedures = computed(() => browserTasks.value.filter(task => task.savedProcedure || task.procedureError || task.metadata?.procedureError))
async function loadDefinition(id: string) {
  const project = props.projectId, session = props.work.sessionId
  try {
    const definition = await api.fixedWorkDefinition(project, session, id)
    if (props.projectId === project && props.work.sessionId === session) historicalDefinitions.value[id] = definition
  } catch (e) { error.value = e instanceof Error ? e.message : '实现版本读取失败' }
}
const fixedRun = computed(() => fixed.value?.runs[0])
const fixedActive = computed(() => Boolean(fixedRun.value && ['QUEUED', 'RUNNING', 'WAITING_INPUT', 'CANCEL_REQUESTED'].includes(fixedRun.value.status)))
const interactionBusy = ref(false)
const waiting = computed(() => fixedRun.value?.status === 'WAITING_INPUT' ? fixedRun.value.interaction : null)
async function interact(action: 'resume' | 'approve' | 'focus') {
  if (!waiting.value || !fixedRun.value || interactionBusy.value) return
  const project = props.projectId, session = props.work.sessionId
  const run = fixedRun.value.id, task = waiting.value.taskId, prompt = waiting.value.promptId
  interactionBusy.value = true; error.value = ''
  try {
    await api.controlFixedInteraction(project, session, run, task, prompt, action)
    if (project === props.projectId && session === props.work.sessionId) { clearTimeout(pollTimer); await pollFixed() }
  } catch (e) { if (project === props.projectId && session === props.work.sessionId) error.value = e instanceof Error ? e.message : '操作失败，请重试' }
  finally { interactionBusy.value = false }
}
let fixedGeneration = 0, pollTimer: ReturnType<typeof setTimeout> | undefined
let startKey = ''
async function pollFixed() {
  const generation = ++fixedGeneration, project = props.projectId, session = props.work.sessionId
  try {
    const state = await api.fixedWorkState(project, session)
    if (generation !== fixedGeneration || session !== props.work.sessionId || project !== props.projectId) return
    const oldOutputs = JSON.stringify(fixed.value?.runs[0]?.stages.map(s => s.output))
    fixed.value = state; fixedError.value = ''; emit('state', session, state)
    if (oldOutputs !== JSON.stringify(state.runs[0]?.stages.map(s => s.output))) emit('executed')
    if (open.value) void api.browserAgentTasks(session).then(browser => {
      if (generation === fixedGeneration) browserTasks.value = browser
    }).catch(() => {})
  } catch (e) { if (generation === fixedGeneration) { fixedError.value = e instanceof Error ? e.message : '固定执行状态读取失败'; if (fixed.value) error.value = fixedError.value } }
  finally { if (generation === fixedGeneration) { fixedLoading.value = false; pollTimer = setTimeout(() => void pollFixed(), fixedActive.value ? 1500 : 4000) } }
}
watch([() => props.projectId, () => props.work.sessionId], () => {
  executionDetails.value = {}
  clearTimeout(pollTimer); fixedGeneration++; fixed.value = null; browserTasks.value = []; fixedLoading.value = true; startKey = ''; historicalDefinitions.value = {}; fixedError.value = ''
  void pollFixed()
}, { immediate: true })
onBeforeUnmount(() => { fixedGeneration++; clearTimeout(pollTimer) })
let generation = 0
const running = computed(() => fixedActive.value || ['QUEUED', 'RUNNING', 'WAITING_CONFIRMATION'].includes(props.work.status))
const runIds = computed(() => [...new Set([props.work.id, ...events.value.map(e => e.runId).filter(Boolean)])])
const visibleEvents = computed(() => events.value.filter(e => e.runId === runId.value))
watch(() => props.work.id, () => {
  generation++; events.value = []; runId.value = props.work.id; error.value = ''; loading.value = false
  if (open.value) void load()
}, { immediate: true })
async function load() {
  const request = ++generation, sessionId = props.work.sessionId
  loading.value = true; error.value = ''
  try {
    const result = await api.listAssistantEvents(sessionId)
    if (request === generation) events.value = result
  } catch (e) { if (request === generation) error.value = e instanceof Error ? e.message : '执行记录读取失败' }
  finally { if (request === generation) loading.value = false }
}
function toggle() { open.value = !open.value; if (open.value) void load() }
async function startExecution() {
  if (starting.value || running.value || fixedLoading.value) return
  if (fixedError.value) { error.value = '执行服务暂不可用，请确认后端已更新并重试'; return }
  if (!fixed.value?.definition) {
    error.value = '当前工作尚未固化实现，请在 Agent 对话中建立固定工序实现。'
    return
  }
  const project = props.projectId, session = props.work.sessionId, definitionId = fixed.value.definition.id
  starting.value = true; error.value = ''; startKey ||= crypto.randomUUID()
  try {
    await api.startFixedWork(project, session, definitionId, startKey)
    startKey = ''
    if (props.projectId !== project || props.work.sessionId !== session) return
    clearTimeout(pollTimer); await pollFixed(); emit('executed')
  } catch (e) { error.value = e instanceof Error ? e.message : '执行未能开始' }
  finally { starting.value = false }
}
async function stopExecution() {
  if (!running.value || stopping.value || fixedLoading.value) return
  const id = props.work.id
  stopping.value = true; error.value = ''
  try {
    if (fixedActive.value && fixedRun.value) await api.cancelFixedWork(props.projectId, props.work.sessionId, fixedRun.value.id)
    else await api.cancelAssistantRun(id)
    clearTimeout(pollTimer); await pollFixed()
    emit('executed')
  } catch (e) { error.value = e instanceof Error ? e.message : '中止请求失败，请重试' }
  finally { stopping.value = false }
}
</script>

<template>
  <section class="work-execution-global" aria-label="工作执行">
    <div class="execution-heading">
      <div class="execution-heading-main"><slot name="heading" /></div>
      <div class="execution-tools">
      <span v-if="waiting" class="waiting-label">等待操作</span>
      <button v-if="waiting?.canFocus" class="execution-icon" title="打开操作窗口" aria-label="打开操作窗口" :disabled="interactionBusy" @click="interact('focus')"><ExternalLink :size="16"/></button>
      <button v-if="waiting" class="execution-command" :disabled="interactionBusy || !!fixedError" @click="interact(browserInteraction(waiting.kind).action)"><Play :size="14"/>{{ interactionBusy ? '正在处理' : browserInteraction(waiting.kind).label }}</button>
      <button class="execution-icon" title="技术执行详情" aria-label="技术执行详情" :aria-expanded="open" @click="toggle"><Code2 :size="16"/></button>
      <button v-if="running" class="execution-command" :disabled="stopping || fixedLoading || fixedRun?.status === 'CANCEL_REQUESTED'" @click="stopExecution"><Square :size="14"/>{{ stopping || fixedRun?.status === 'CANCEL_REQUESTED' ? '正在中止' : '中止' }}</button>
      <button v-else class="execution-command" :disabled="assistant.canInterrupt || starting || fixedLoading" @click="startExecution"><Play :size="15"/>{{ starting ? '正在开始' : '开始执行' }}</button>
      </div>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="fixedError" role="alert">执行状态暂不可用，不能确认任务仍在运行。{{ fixedError }}</p>
    <p v-if="waiting" class="interaction-message" role="status">{{ waiting.message }}</p>
    <div v-if="open" class="execution-content">
      <details v-for="task in procedures" :key="task.id"><summary>浏览器技术产物 · {{ task.savedProcedure ? '已保存' : '待整理' }}</summary>
        <p v-if="task.savedProcedure">{{ task.metadata?.procedure?.engine }} · {{ task.metadata?.procedure?.hash }}</p>
        <p v-if="task.savedProcedure?.notice || task.procedureError || task.metadata?.procedureError" role="status">{{ task.savedProcedure?.notice || task.procedureError || task.metadata?.procedureError }}</p>
        <a v-for="file in task.files?.filter(file => file.name === 'browser-procedure.json') || []" :key="file.url" :href="file.url" :download="file.name">下载固定浏览器脚本</a>
      </details>
      <template v-if="fixed?.definition"><p>实现 v{{ fixed.definition.version }} · {{ fixed.definition.verified ? '执行验证通过' : '尚未执行验证' }}</p><details v-for="step in fixed.definition.implementation.steps" :key="step.id"><summary>{{ step.title }} · {{ step.kind === 'SQL' ? '固定 SQL' : step.kind === 'TOOL' ? '固定工具调用' : '固定提示词' }}</summary><pre>{{ step.code }}</pre><pre>{{ JSON.stringify(step.inputs, null, 2) }}</pre></details><details><summary>实现校验值与模型配置</summary><pre>{{ fixed.definition.hash }}
{{ JSON.stringify(fixed.definition.implementation.modelSnapshot, null, 2) }}</pre></details></template>
      <p v-else>尚未固化实现</p>
      <details v-if="fixed?.artifacts?.length"><summary>已归档技术资源</summary>
        <p v-for="file in fixed.artifacts" :key="file.part"><a :href="`/api/files/${file.resourceId}/download?version=${file.version}`">{{ file.part === '@manifest' ? '实现清单' : file.part }} · 资源版本 {{ file.version }}</a></p>
      </details>
      <details v-for="run in fixed?.runs ?? []" :key="run.id"><summary>{{ new Date(run.createdAt).toLocaleString() }} · {{ run.status }} · {{ run.id.slice(0, 8) }}</summary><p v-if="run.error" role="alert">{{ run.error }}</p><pre>{{ JSON.stringify(run, null, 2) }}</pre>
        <div class="execution-actions"><button class="text-button" @click="loadDefinition(run.definitionId)">查看本轮实现版本</button><button class="text-button" @click="loadExecution(run.id)">查看步骤执行记录</button></div>
        <pre v-if="historicalDefinitions[run.definitionId]">{{ JSON.stringify(historicalDefinitions[run.definitionId], null, 2) }}</pre>
        <template v-if="executionDetails[run.id]"><pre>{{ JSON.stringify(executionDetails[run.id].steps, null, 2) }}</pre>
          <p v-for="file in executionDetails[run.id].artifacts" :key="file.part"><a :href="`/api/files/${file.resourceId}/download?version=${file.version}`">下载执行记录</a></p>
        </template>
      </details>
      <details><summary>Agent 设计过程</summary>
      <div class="execution-actions"><label>执行记录 <select v-model="runId"><option v-for="id in runIds" :key="id" :value="id">{{ id === work.id ? '当前执行' : '历史执行' }} · {{ id }}</option></select></label><button class="text-button" :disabled="loading" @click="load"><RefreshCw :size="14"/>刷新</button></div>
      <p v-if="loading" role="status">正在读取执行记录</p>
      <template v-else><p v-if="!visibleEvents.length">本轮暂无技术事件记录</p><details v-for="event in visibleEvents" :key="event.eventId"><summary>{{ event.payload.toolName || event.type }} · {{ new Date(event.createdAt).toLocaleString() }}</summary><pre>{{ JSON.stringify(event.payload, null, 2) }}</pre></details></template>
      <template v-if="runId === work.id"><p>{{ work.resultSummary }}</p><details><summary>全局工序与产物版本</summary><pre>{{ JSON.stringify(work.stages, null, 2) }}</pre></details><details><summary>执行结果与来源记录</summary><pre>{{ JSON.stringify(work.result, null, 2) }}</pre></details></template>
      </details>
    </div>
  </section>
</template>

<style scoped>
.execution-command{display:inline-flex;align-items:center;gap:5px;height:32px;padding:0 8px;border:0;border-radius:4px;background:transparent;color:#236dcc;white-space:nowrap;cursor:pointer}.execution-command:hover{background:#f0f4f8}.execution-command:disabled{opacity:.5;cursor:default}
.execution-content{max-height:480px;overflow:auto;scrollbar-gutter:stable}
.execution-content p{overflow-wrap:anywhere;min-width:0}
.work-execution-global{border-block:1px solid #e4eaf1;margin:16px 0;padding:12px 0;font-size:12px;min-width:0}.execution-actions{display:flex;align-items:center;gap:20px;flex-wrap:wrap}.text-button{display:inline-flex;align-items:center;gap:6px;border:0;background:none;color:#236dcc;cursor:pointer;padding:6px 0}.text-button:disabled{opacity:.5;cursor:default}.repeat-work,.execution-content{padding:16px 0}.repeat-work h2{font-size:16px}.repeat-work textarea{box-sizing:border-box;display:block;width:100%;margin:8px 0 16px;padding:12px;border:1px solid #dce4ee;border-radius:6px;font:inherit;resize:vertical}.repeat-work fieldset{border:1px solid #e4eaf1;max-height:220px;overflow:auto}.input-choice{display:flex;gap:8px;padding:8px 0;align-items:flex-start;overflow-wrap:anywhere}.repeat-work p{color:#627895;line-height:1.8}.execution-content label{min-width:0;max-width:100%}select{max-width:100%;padding:6px;border:1px solid #dce4ee;background:white}details{margin:12px 0}summary{cursor:pointer;overflow-wrap:anywhere}pre{padding:12px;background:#f6f8fb;white-space:pre-wrap;overflow-wrap:anywhere;max-height:420px;overflow:auto}button:focus-visible,select:focus-visible{outline:2px solid #236dcc;outline-offset:3px}
</style>

<style scoped>
.work-execution-global{border:0;margin:0;padding:0}
.execution-heading{display:flex;align-items:flex-start;gap:12px}
.execution-heading-main{flex:1;min-width:0}
.execution-tools{display:flex;gap:4px;flex-shrink:0;padding-top:8px}
.waiting-label{align-self:center;color:#865d20;white-space:nowrap}.interaction-message{white-space:pre-wrap;overflow-wrap:anywhere;max-height:120px;overflow:auto;color:#65756d;margin:6px 0;line-height:1.6}
@media(max-width:600px){.execution-heading{flex-wrap:wrap}.execution-tools{flex-wrap:wrap;max-width:100%;padding-top:0}}
.execution-icon{display:inline-flex;align-items:center;justify-content:center;width:32px;height:32px;padding:0;border:0;border-radius:4px;background:transparent;color:#738195;cursor:pointer}
.execution-icon:hover,.execution-icon[aria-expanded="true"]{background:#f0f4f8;color:#236dcc}
.execution-icon:disabled{opacity:.4;cursor:default}
</style>
