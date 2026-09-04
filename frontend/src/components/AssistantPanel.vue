<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Activity, AlertCircle, ArrowDown, ArrowUp, Bot, Check, CheckCircle2, ChevronDown, ChevronRight, Circle, Clock3, LoaderCircle, MessageSquarePlus, PanelRightClose, RotateCcw, ShieldCheck, Sparkles, Square, Wrench, Zap } from 'lucide-vue-next'
import { useAssistantStore } from '../stores/assistant'
import type { Project } from '../api/client'

const props = defineProps<{ project: Project | null }>()
const emit = defineEmits<{ workbenchAction: [action: Record<string, unknown>] }>()
const assistant = useAssistantStore()
const conversation = ref<HTMLElement | null>(null)
const showSteps = ref(false)
const showEvents = ref(false)
const followingLatest = ref(true)
const now = ref(Date.now())
let clockTimer: number | undefined
let conversationObserver: MutationObserver | undefined

const contextLabel = computed(() => assistant.selection?.range.join('、') || assistant.contextTitle || '项目概览')
const running = computed(() => ['QUEUED', 'RUNNING'].includes(assistant.run?.status ?? '') || assistant.streaming)
const controlsLocked = computed(() => assistant.busy || ['QUEUED', 'RUNNING', 'WAITING_CONFIRMATION'].includes(assistant.run?.status ?? ''))
const taskState = computed(() => {
  if (assistant.error || assistant.run?.status === 'FAILED') return { code: 'failed', label: '未完成', detail: assistant.error || assistant.run?.resultSummary || '执行遇到问题' }
  if (assistant.run?.status === 'CANCELED') return { code: 'canceled', label: '已停止', detail: '后续步骤没有继续执行' }
  if (assistant.run?.status === 'ROLLED_BACK') return { code: 'completed', label: '已撤销', detail: '已恢复到执行前状态' }
  if (assistant.run?.status === 'SUCCEEDED') return { code: 'completed', label: '已完成', detail: assistant.run.resultSummary || '任务已经完成' }
  if (assistant.needsConfirmation && (!assistant.run || assistant.run.status === 'WAITING_CONFIRMATION')) return { code: 'waiting', label: '等待确认', detail: assistant.run?.resultSummary || '确认后会继续修改工作台' }
  if (assistant.run?.status === 'QUEUED') return { code: 'running', label: '准备执行', detail: assistant.progressLabel }
  if (assistant.streaming || assistant.busy) return { code: 'running', label: assistant.plan ? '正在执行' : '正在思考', detail: assistant.progressLabel }
  if (assistant.plan) return { code: 'ready', label: '计划就绪', detail: assistant.plan.summary }
  return { code: 'idle', label: '就绪', detail: '' }
})
const completedSteps = computed(() => {
  if (!assistant.plan) return 0
  return assistant.plan.steps.filter(step => step.status === 'SUCCEEDED').length
})
const latestActivity = computed(() => assistant.timeline.at(-1))
const showJumpToLatest = computed(() => !followingLatest.value && (running.value || assistant.timeline.length > 0))
const activeStep = computed(() => assistant.plan?.steps.find(step => stepState(step.order) === 'running'))
const elapsedLabel = computed(() => {
  const starts = [assistant.timeline[0]?.time, assistant.run?.createdAt, assistant.run?.startedAt]
    .filter((value): value is string => Boolean(value))
    .map(value => new Date(value).getTime())
    .filter(value => Number.isFinite(value))
  if (!starts.length) return '刚刚开始'
  const started = Math.min(...starts)
  const finished = assistant.run?.finishedAt
  const end = finished ? new Date(finished).getTime() : now.value
  const seconds = Math.max(0, Math.floor((end - started) / 1000))
  if (seconds < 60) return `已处理 ${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  const remainder = seconds % 60
  return remainder ? `已处理 ${minutes} 分 ${remainder} 秒` : `已处理 ${minutes} 分钟`
})
function stepState(order: number) {
  const persisted = assistant.plan?.steps.find(step => step.order === order)?.status
  if (persisted === 'SUCCEEDED') return 'completed'
  if (persisted === 'FAILED') return 'failed'
  if (persisted === 'CANCELED') return 'canceled'
  const status = assistant.run?.status
  const current = assistant.run?.currentStep ?? 0
  if (status === 'SUCCEEDED' || status === 'ROLLED_BACK') return 'completed'
  if (order < current) return 'completed'
  if (order === current && status === 'FAILED') return 'failed'
  if (order === current && status === 'CANCELED') return 'canceled'
  if (order === current && ['QUEUED', 'RUNNING'].includes(status ?? '')) return 'running'
  return 'pending'
}
function stepLabel(order: number) {
  return { completed: '完成', running: '进行中', failed: '失败', canceled: '停止', pending: '等待' }[stepState(order)]
}
function send() { if (props.project) assistant.send(props.project.id) }
function switchSession(event: Event) { void assistant.switchSession((event.target as HTMLSelectElement).value) }
function clock(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
function handleConversationScroll(event: Event) {
  const element = conversation.value
  if (!element) return
  if (event.target !== element) {
    followingLatest.value = false
    return
  }
  followingLatest.value = element.scrollHeight - element.clientHeight - element.scrollTop <= 48
}
function handleConversationWheel(event: WheelEvent) {
  if (event.deltaY < 0) followingLatest.value = false
}
async function scrollToLatest(force = false) {
  if (!force && !followingLatest.value) return
  await nextTick()
  window.requestAnimationFrame(() => {
    const element = conversation.value
    if (!element || (!force && !followingLatest.value)) return
    element.scrollTop = element.scrollHeight
    followingLatest.value = true
  })
}
function jumpToLatest() {
  followingLatest.value = true
  void scrollToLatest(true)
}
watch(() => [assistant.timeline.at(-1)?.id, assistant.timeline.at(-1)?.detail, assistant.assistantMessage,
  assistant.history.length, assistant.run?.status, assistant.run?.currentStep,
  assistant.plan?.steps.length], scrollToLatest,
{ flush: 'post' })
watch(() => assistant.currentRequest, (request, previous) => {
  showSteps.value = false
  showEvents.value = false
  if (request && request !== previous) {
    followingLatest.value = true
    void scrollToLatest(true)
  }
})
watch(() => assistant.sessionId, () => {
  followingLatest.value = true
  void scrollToLatest(true)
})
watch(() => [assistant.open, props.project?.id] as const, ([open, projectId]) => {
  if (open && projectId) void assistant.ensureSession(projectId)
}, { immediate: true })
watch(() => assistant.run?.status, status => {
  if (status && ['SUCCEEDED', 'FAILED', 'CANCELED', 'ROLLED_BACK'].includes(status)) showSteps.value = false
  if (status === 'SUCCEEDED') {
    const action = assistant.run?.result?.uiAction
    if (action && typeof action === 'object') emit('workbenchAction', action as Record<string, unknown>)
  }
})
onMounted(() => {
  clockTimer = window.setInterval(() => { now.value = Date.now() }, 1000)
  if (conversation.value) {
    conversationObserver = new MutationObserver(() => {
      if (running.value || assistant.needsConfirmation) void scrollToLatest()
    })
    conversationObserver.observe(conversation.value, { childList: true, subtree: true, characterData: true })
  }
  void scrollToLatest(true)
})
onBeforeUnmount(() => {
  if (clockTimer !== undefined) window.clearInterval(clockTimer)
  conversationObserver?.disconnect()
})
</script>

<template>
  <button v-if="!assistant.open" class="assistant-launcher" type="button" title="打开 AI 助手" @click="assistant.open = true"><Sparkles :size="19" /></button>

  <aside v-else class="assistant-drawer assistant-codex-panel" aria-label="AI 助手">
    <header class="assistant-header">
      <div>
        <div class="assistant-title"><Bot :size="18" /> AI 助手</div>
        <p><span></span>{{ props.project?.name ?? '个人工作台' }} · {{ contextLabel }}</p>
        <div class="assistant-session-switcher">
          <select :value="assistant.sessionId" :disabled="controlsLocked" aria-label="历史对话" @change="switchSession">
            <option v-for="session in assistant.sessions" :key="session.id" :value="session.id">{{ session.title }} · {{ clock(session.updatedAt) }}</option>
          </select>
          <button type="button" title="新对话" :disabled="controlsLocked || !props.project" @click="props.project && assistant.createNewSession(props.project.id)"><MessageSquarePlus :size="14" /></button>
        </div>
      </div>
      <div class="assistant-header-actions">
        <div class="assistant-mode-switch" role="group" aria-label="Agent 执行模式">
          <button type="button" :class="{ active: assistant.executionMode === 'AUTO' }" :disabled="controlsLocked" title="自动执行 LLM 选择的工具" @click="assistant.setExecutionMode('AUTO')"><Zap :size="12" />Auto</button>
          <button type="button" :class="{ active: assistant.executionMode === 'APPROVAL' }" :disabled="controlsLocked" title="修改前需要确认" @click="assistant.setExecutionMode('APPROVAL')"><ShieldCheck :size="12" />审批</button>
        </div>
        <button class="icon-button" type="button" title="收起助手" @click="assistant.open = false"><PanelRightClose :size="18" /></button>
      </div>
    </header>

    <div ref="conversation" class="assistant-body assistant-conversation" @scroll.capture.passive="handleConversationScroll" @wheel.passive="handleConversationWheel">
      <section v-if="!assistant.currentRequest && !assistant.history.length" class="assistant-empty">
        <span><Sparkles :size="21" /></span>
        <strong>让 Agent 直接处理当前工作</strong>
        <p>它会理解当前项目和你正在查看的内容，选择所需能力，并把操作实时呈现在工作台。</p>
        <div class="prompt-suggestions">
          <button type="button" @click="assistant.input = '梳理当前项目的内容，并告诉我还缺什么'">梳理当前项目</button>
          <button type="button" @click="assistant.input = '根据当前项目的资料编排一条分析工作流'">编排分析流程</button>
          <button type="button" @click="assistant.input = '打开当前项目的工作流并检查是否完整'">检查工作流</button>
        </div>
      </section>

      <section v-for="message in assistant.history" :key="message.id" class="assistant-history-turn" :data-role="message.role.toLowerCase()">
        <div class="assistant-turn-label">{{ message.role === 'USER' ? '你' : 'Agent' }}</div>
        <p>{{ message.content }}</p>
        <time>{{ clock(message.createdAt) }}</time>
      </section>

      <template v-if="assistant.currentRequest">
        <article class="assistant-user-turn">
          <div class="assistant-turn-label">你</div>
          <p>{{ assistant.currentRequest }}</p>
        </article>

        <article class="assistant-agent-turn" :data-state="taskState.code">
          <div class="assistant-turn-label"><span><Sparkles :size="13" /></span>Agent <time class="assistant-elapsed"><Clock3 :size="11" />{{ elapsedLabel }}</time></div>
          <div class="assistant-run-state" aria-live="polite">
            <LoaderCircle v-if="taskState.code === 'running'" :size="15" class="assistant-spinner" />
            <CheckCircle2 v-else-if="taskState.code === 'completed'" :size="15" />
            <AlertCircle v-else-if="taskState.code === 'failed' || taskState.code === 'canceled'" :size="15" />
            <Circle v-else :size="13" />
            <strong>{{ taskState.label }}</strong><span>{{ taskState.detail }}</span>
          </div>

          <div v-if="assistant.plan" class="assistant-inline-progress">
            <i><span :style="{ width: `${assistant.progress}%` }"></span></i><small>{{ completedSteps }}/{{ assistant.plan.steps.length }} 步</small>
          </div>

          <section v-if="running || assistant.needsConfirmation" class="assistant-current-activity" aria-live="polite" aria-atomic="true">
            <header><span><Activity :size="13" />当前进展</span><small>{{ elapsedLabel }}</small></header>
            <strong>{{ latestActivity?.title || activeStep?.title || taskState.label }}</strong>
            <p>{{ latestActivity?.detail || activeStep?.description || taskState.detail }}</p>
            <small v-if="latestActivity?.toolName">{{ latestActivity.toolName }}<template v-if="latestActivity.argumentSummary"> · {{ latestActivity.argumentSummary }}</template></small>
          </section>

          <p v-if="assistant.assistantMessage && assistant.run?.status === 'SUCCEEDED'" class="assistant-final-answer">{{ assistant.assistantMessage }}</p>
          <p v-if="assistant.error" class="assistant-error">{{ assistant.error }}</p>

          <section v-if="assistant.plan" class="assistant-tool-group">
            <button type="button" @click="showSteps = !showSteps">
              <span><Wrench :size="14" /><strong>执行步骤</strong><small>{{ completedSteps }}/{{ assistant.plan.steps.length }} 已完成</small></span>
              <ChevronDown v-if="showSteps" :size="15" /><ChevronRight v-else :size="15" />
            </button>
            <div v-if="showSteps" class="assistant-tool-list">
              <div v-for="step in assistant.plan.steps" :key="step.id" class="assistant-tool-row" :data-state="stepState(step.order)">
                <span class="assistant-tool-status">
                  <Check v-if="stepState(step.order) === 'completed'" :size="12" />
                  <LoaderCircle v-else-if="stepState(step.order) === 'running'" :size="13" class="assistant-spinner" />
                  <AlertCircle v-else-if="stepState(step.order) === 'failed' || stepState(step.order) === 'canceled'" :size="13" />
                  <Circle v-else :size="9" />
                </span>
                <div><strong>{{ step.title }}</strong><p>{{ step.description }}</p></div><small>{{ stepLabel(step.order) }}</small>
              </div>
            </div>
          </section>

          <div v-if="assistant.needsConfirmation && (!assistant.run || assistant.run.status === 'WAITING_CONFIRMATION')" class="assistant-confirm-inline">
            <ShieldCheck :size="17" /><p><strong>需要你的确认</strong><span>这些步骤会更新工作台，原内容会保留。</span></p>
            <button class="primary-button" type="button" :disabled="assistant.busy" @click="assistant.confirm">确认执行</button>
          </div>

          <details v-if="assistant.timeline.length" class="assistant-event-details assistant-activity-stream" :open="showEvents" @toggle="showEvents = ($event.target as HTMLDetailsElement).open">
            <summary>详细活动 · {{ assistant.timeline.length }} 条</summary>
            <div>
              <article v-for="item in assistant.timeline" :key="item.id" class="assistant-activity-item" :data-tone="item.tone">
                <time>{{ clock(item.time) }}</time>
                <span>{{ item.title }}</span>
                <p>{{ item.detail }}</p>
                <small v-if="item.toolName">{{ item.toolName }}<template v-if="item.argumentSummary"> · {{ item.argumentSummary }}</template></small>
                <small v-if="item.resultSummary && item.resultSummary !== item.detail">结果：{{ item.resultSummary }}</small>
                <details v-if="item.error" class="assistant-error-detail">
                  <summary>错误详情</summary>
                  <pre>{{ item.error }}</pre>
                </details>
              </article>
            </div>
          </details>

          <div v-if="assistant.run?.status === 'SUCCEEDED' && assistant.run.result?.createdProjectId" class="result-actions">
            <button class="secondary-button" type="button" @click="assistant.rollback"><RotateCcw :size="15" />撤销本次操作</button>
          </div>
        </article>
      </template>
    </div>

    <button v-if="showJumpToLatest" class="assistant-jump-latest" type="button" title="恢复自动跟随" @click="jumpToLatest">
      <ArrowDown :size="14" />查看最新进展
    </button>

    <footer class="assistant-composer">
      <div class="assistant-context-chip">正在使用：{{ contextLabel }}</div>
      <div>
        <textarea v-model="assistant.input" rows="2" placeholder="安排一项工作，或继续追问..." @keydown.meta.enter.prevent="send" @keydown.ctrl.enter.prevent="send"></textarea>
        <button v-if="running && assistant.run" class="send-button stop" type="button" title="停止" @click="assistant.cancel"><Square :size="14" /></button>
        <button v-else class="send-button" type="button" title="发送" :disabled="assistant.busy || !assistant.input.trim()" @click="send"><ArrowUp :size="18" /></button>
      </div>
      <p>{{ assistant.executionMode === 'AUTO' ? 'Auto 模式会直接执行 Agent 选择的工具' : '审批模式会在修改工作台前请求确认' }}</p>
    </footer>
  </aside>
</template>
