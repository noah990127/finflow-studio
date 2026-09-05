<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  Activity, AlertCircle, ArrowDown, ArrowUp, Bot, BrainCircuit, Check, CheckCircle2,
  ChevronDown, ChevronRight, Circle, Clock3, FileOutput, History, ListChecks, LoaderCircle,
  MessageSquarePlus, PanelRightClose, Radio, RotateCcw, Search, ShieldCheck,
  Sparkles, Square, Zap,
} from 'lucide-vue-next'
import { useAssistantStore } from '../stores/assistant'
import type { AssistantMessage, Project } from '../api/client'
import { businessError, businessText } from '../domain/workProgress'
import AssistantModelSettings from './AssistantModelSettings.vue'

const props = defineProps<{ project: Project | null }>()
const emit = defineEmits<{ workbenchAction: [action: Record<string, unknown>] }>()
const assistant = useAssistantStore()
const conversation = ref<HTMLElement | null>(null)
const composer = ref<HTMLTextAreaElement | null>(null)
const showSteps = ref(false)
const showEvents = ref(false)
const followingLatest = ref(true)
const unseenActivityCount = ref(0)
const now = ref(Date.now())
let clockTimer: number | undefined
let conversationObserver: MutationObserver | undefined

type HistoryTurn = { id: string; user?: AssistantMessage; assistant: AssistantMessage[] }

const contextLabel = computed(() => assistant.selection?.range.join('、') || assistant.contextTitle || '项目概览')
const running = computed(() => !assistant.interrupted && (['QUEUED', 'RUNNING'].includes(assistant.run?.status ?? '') || assistant.streaming))
const controlsLocked = computed(() => assistant.busy || ['QUEUED', 'RUNNING'].includes(assistant.run?.status ?? ''))
const waitingConfirmation = computed(() => assistant.needsConfirmation && (!assistant.run || assistant.run.status === 'WAITING_CONFIRMATION'))
const taskState = computed(() => {
  if (assistant.stopping) return { code: 'running', label: '正在中断', detail: '正在停止后续处理' }
  if (assistant.interrupted) return { code: 'canceled', label: '已中断', detail: assistant.progressLabel || '已停止本次处理，可以继续发送新的要求' }
  if (assistant.error || assistant.run?.status === 'FAILED') return { code: 'failed', label: '未完成', detail: businessError(assistant.error || assistant.run?.resultSummary || '执行遇到问题') }
  if (assistant.run?.status === 'CANCELED') return { code: 'canceled', label: '已停止', detail: '后续步骤没有继续执行' }
  if (assistant.run?.status === 'ROLLED_BACK') return { code: 'completed', label: '已撤销', detail: '已恢复到执行前状态' }
  if (assistant.run?.status === 'SUCCEEDED') return { code: 'completed', label: '已完成', detail: '' }
  if (waitingConfirmation.value) return { code: 'waiting', label: '等待确认', detail: assistant.run?.resultSummary || '确认后会继续修改工作台' }
  if (assistant.run?.status === 'QUEUED') return { code: 'running', label: '准备执行', detail: assistant.progressLabel }
  if (assistant.streaming || assistant.busy) return { code: 'running', label: assistant.plan ? '正在执行' : '正在分析', detail: assistant.progressLabel }
  if (assistant.plan) return { code: 'ready', label: '计划就绪', detail: assistant.plan.summary }
  return { code: 'idle', label: '就绪', detail: '' }
})
const completedSteps = computed(() => assistant.plan?.steps.filter(step => step.status === 'SUCCEEDED').length ?? 0)
const directReply = computed(() => assistant.plan?.steps.length === 1
  && assistant.plan.steps[0]?.tool === 'assistant.respond'
  && typeof assistant.plan.steps[0]?.arguments.prepared_answer === 'string')
const latestActivity = computed(() => assistant.timeline.at(-1))
const visibleActivities = computed(() => {
  if (showEvents.value) return assistant.timeline
  const summary = assistant.timeline.findLast(item => item.eventType === 'agent.thinking_summary' && item.status === 'completed')
  const latest = assistant.run?.status === 'SUCCEEDED' ? undefined : latestActivity.value
  return [summary, latest].filter((item, index, items) => item && items.indexOf(item) === index)
})
const showJumpToLatest = computed(() => !followingLatest.value && (running.value || unseenActivityCount.value > 0))
const activeStep = computed(() => assistant.plan?.steps.find(step => stepState(step.order) === 'running'))
const streamHealthLabel = computed(() => assistant.eventConnection === 'live' ? '实时' : assistant.eventConnection === 'reconnecting' ? '正在重连' : '连接中')
const elapsedLabel = computed(() => {
  const starts = [assistant.timeline[0]?.time, assistant.run?.createdAt, assistant.run?.startedAt]
    .filter((value): value is string => Boolean(value))
    .map(value => new Date(value).getTime())
    .filter(value => Number.isFinite(value))
  if (!starts.length) return '刚刚开始'
  const started = Math.min(...starts)
  const finished = assistant.interruptedAt || assistant.run?.finishedAt
  const end = finished ? new Date(finished).getTime() : now.value
  const seconds = Math.max(0, Math.floor((end - started) / 1000))
  const prefix = finished ? '耗时' : '已处理'
  if (seconds < 60) return `${prefix} ${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  const remainder = seconds % 60
  return remainder ? `${prefix} ${minutes} 分 ${remainder} 秒` : `${prefix} ${minutes} 分钟`
})
const historyTurns = computed<HistoryTurn[]>(() => {
  const turns: HistoryTurn[] = []
  for (const message of assistant.history) {
    if (message.role === 'USER') {
      turns.push({ id: message.id, user: message, assistant: [] })
      continue
    }
    if (message.role !== 'ASSISTANT') continue
    const current = turns.at(-1)
    if (current) current.assistant.push(message)
    else turns.push({ id: message.id, assistant: [message] })
  }
  return turns
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
function activityIcon(kind: string) {
  if (kind === 'planning') return ListChecks
  if (kind === 'skill') return History
  if (kind === 'tool') return Search
  if (kind === 'observation') return CheckCircle2
  if (kind === 'confirmation') return ShieldCheck
  if (kind === 'generation') return FileOutput
  if (kind === 'result') return CheckCircle2
  return BrainCircuit
}
function activityStatus(item: (typeof assistant.timeline)[number]) {
  if (item.error || item.status === 'failed') return '失败'
  if (item.status === 'waiting') return '待确认'
  if (item.status === 'completed' || item.tone === 'success') return '完成'
  return running.value && item.id === latestActivity.value?.id ? '进行中' : '已记录'
}
function lastAssistant(turn: HistoryTurn) { return turn.assistant.at(-1) }
function earlierAssistant(turn: HistoryTurn) { return turn.assistant.slice(0, -1) }
function send() {
  if (!props.project) return
  const text = assistant.input.trim()
  if (!text) return
  assistant.input = ''
  void assistant.send(props.project.id, text)
}
function switchSession(event: Event) { void assistant.switchSession((event.target as HTMLSelectElement).value) }
function clock(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
function handleComposerKeydown(event: KeyboardEvent) {
  if (event.isComposing || event.key !== 'Enter' || event.shiftKey) return
  event.preventDefault()
  send()
}
function resizeComposer() {
  const element = composer.value
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 160)}px`
}
function handleConversationScroll(event: Event) {
  const element = conversation.value
  if (!element || event.target !== element) return
  followingLatest.value = element.scrollHeight - element.clientHeight - element.scrollTop <= 56
  if (followingLatest.value) unseenActivityCount.value = 0
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
    unseenActivityCount.value = 0
  })
}
function jumpToLatest() {
  followingLatest.value = true
  void scrollToLatest(true)
}
function toggleActivities() {
  showEvents.value = !showEvents.value
  if (showEvents.value) followingLatest.value = false
}

watch(() => assistant.input, () => nextTick(resizeComposer))
watch(() => assistant.timeline.at(-1)?.id, (id, previous) => {
  if (id && previous && !followingLatest.value) unseenActivityCount.value += 1
})
watch(() => [assistant.timeline.at(-1)?.id, assistant.timeline.at(-1)?.detail, assistant.assistantMessage,
  assistant.history.length, assistant.run?.status, assistant.run?.currentStep,
  assistant.plan?.steps.length], () => scrollToLatest(), { flush: 'post' })
watch(() => assistant.currentRequest, (request, previous) => {
  showSteps.value = false
  showEvents.value = false
  unseenActivityCount.value = 0
  if (request && request !== previous) {
    followingLatest.value = true
    void scrollToLatest(true)
  }
})
watch(() => assistant.sessionId, () => {
  followingLatest.value = true
  unseenActivityCount.value = 0
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
      if ((running.value || waitingConfirmation.value) && followingLatest.value) void scrollToLatest()
    })
    conversationObserver.observe(conversation.value, { childList: true, subtree: true, characterData: true })
  }
  resizeComposer()
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
      <div class="assistant-heading-row">
        <div>
          <div class="assistant-title"><Bot :size="18" /> Agent</div>
          <p><span></span>{{ props.project?.name ?? '个人工作台' }}<i>/</i>{{ contextLabel }}</p>
        </div>
        <button class="icon-button" type="button" title="收起助手" @click="assistant.open = false"><PanelRightClose :size="18" /></button>
      </div>
      <div class="assistant-control-row">
        <label class="assistant-session-switcher">
          <History :size="14" />
          <select :value="assistant.sessionId" :disabled="controlsLocked" aria-label="历史对话" @change="switchSession">
            <option v-for="session in assistant.sessions" :key="session.id" :value="session.id">{{ session.title }} · {{ clock(session.updatedAt) }}</option>
          </select>
          <button type="button" title="新对话" :disabled="controlsLocked || !props.project" @click="props.project && assistant.createNewSession(props.project.id)"><MessageSquarePlus :size="15" /></button>
        </label>
        <div class="assistant-mode-switch" role="group" aria-label="Agent 执行模式">
          <button type="button" :class="{ active: assistant.executionMode === 'AUTO' }" :disabled="controlsLocked" title="自动执行 LLM 选择的工具" @click="assistant.setExecutionMode('AUTO')"><Zap :size="13" />Auto</button>
          <button type="button" :class="{ active: assistant.executionMode === 'APPROVAL' }" :disabled="controlsLocked" title="修改前需要确认" @click="assistant.setExecutionMode('APPROVAL')"><ShieldCheck :size="13" />审批</button>
        </div>
      </div>
      <AssistantModelSettings :session-id="assistant.sessionId" :disabled="controlsLocked || waitingConfirmation || assistant.stopping" />
    </header>

    <div ref="conversation" class="assistant-body assistant-conversation" @scroll.passive="handleConversationScroll" @wheel.passive="handleConversationWheel">
      <section v-if="!assistant.currentRequest && !assistant.history.length" class="assistant-empty">
        <span><Sparkles :size="21" /></span>
        <strong>今天想完成什么？</strong>
        <p>Agent 会结合当前项目选择能力，执行过程会实时出现在这里。</p>
        <div class="prompt-suggestions">
          <button type="button" @click="assistant.input = '梳理当前项目的内容，并告诉我还缺什么'">梳理当前项目</button>
          <button type="button" @click="assistant.input = '根据当前项目的资料编排一条分析工作流'">编排分析流程</button>
          <button type="button" @click="assistant.input = '打开当前项目的工作流并检查是否完整'">检查工作流</button>
        </div>
      </section>

      <section v-for="turn in historyTurns" :key="turn.id" class="assistant-history-group">
        <article v-if="turn.user" class="assistant-history-turn" data-role="user">
          <div class="assistant-turn-label">你 <time>{{ clock(turn.user.createdAt) }}</time></div>
          <p>{{ turn.user.content }}</p>
        </article>
        <article v-if="lastAssistant(turn)" class="assistant-history-turn" data-role="assistant">
          <div class="assistant-agent-avatar"><Sparkles :size="13" /></div>
          <div class="assistant-turn-label">Agent <time>{{ clock(lastAssistant(turn)!.createdAt) }}</time></div>
          <details v-if="earlierAssistant(turn).length" class="assistant-history-plan">
            <summary>查看当时计划</summary>
            <p v-for="message in earlierAssistant(turn)" :key="message.id">{{ message.content }}</p>
          </details>
          <p>{{ lastAssistant(turn)!.content }}</p>
        </article>
      </section>

      <template v-if="assistant.currentRequest">
        <article class="assistant-user-turn">
          <div class="assistant-turn-label">你</div>
          <p>{{ assistant.currentRequest }}</p>
        </article>

        <article class="assistant-agent-turn" :data-state="taskState.code">
          <div class="assistant-agent-avatar"><Sparkles :size="13" /></div>
          <div class="assistant-turn-label">
            Agent
            <span class="assistant-stream-health" :data-state="assistant.eventConnection"><Radio :size="10" />{{ streamHealthLabel }}</span>
            <time class="assistant-elapsed"><Clock3 :size="12" />{{ elapsedLabel }}</time>
          </div>

          <div class="assistant-run-state" aria-live="polite">
            <LoaderCircle v-if="taskState.code === 'running'" :size="16" class="assistant-spinner" />
            <CheckCircle2 v-else-if="taskState.code === 'completed'" :size="16" />
            <AlertCircle v-else-if="taskState.code === 'failed' || taskState.code === 'canceled'" :size="16" />
            <Circle v-else :size="14" />
            <strong>{{ taskState.label }}</strong>
            <span v-if="taskState.detail">{{ businessText(taskState.detail) }}</span>
          </div>

          <div v-if="assistant.plan && !directReply" class="assistant-inline-progress">
            <i><span :style="{ width: `${assistant.progress}%` }"></span></i><small>{{ completedSteps }}/{{ assistant.plan.steps.length }} 步</small>
          </div>

          <section v-if="assistant.timeline.length || running || waitingConfirmation" class="assistant-work-process" aria-label="工作过程">
            <button type="button" class="assistant-process-toggle" :aria-expanded="showEvents" aria-controls="assistant-process-entries" @click="toggleActivities">
              <span><Activity :size="14" />工作过程 <small>{{ assistant.timeline.length }} 条进展</small></span>
              <ChevronDown v-if="showEvents" :size="15" /><ChevronRight v-else :size="15" />
            </button>
            <div id="assistant-process-entries" class="assistant-process-entries" :aria-live="showEvents ? 'off' : 'polite'">
              <template v-for="item in visibleActivities" :key="item?.id">
                <article v-if="item" class="assistant-activity-item" :data-tone="item.tone">
                  <span class="assistant-activity-icon"><component :is="activityIcon(item.kind)" :size="13" /></span>
                  <div>
                    <header><strong>{{ item.title }}</strong><time>{{ clock(item.time) }} · {{ activityStatus(item) }}</time></header>
                    <p>{{ item.detail }}</p>
                    <small v-if="item.resultSummary && item.resultSummary !== item.detail">结果：{{ businessText(item.resultSummary, '已收到这一步的处理结果') }}</small>
                    <small v-if="item.provenanceSummary">来源：{{ businessText(item.provenanceSummary, '已记录所用资料') }}</small>
                    <details v-if="item.toolName || item.technicalDetail !== item.detail" class="assistant-error-detail"><summary>技术详情</summary><code>{{ item.toolName }}<template v-if="item.argumentSummary"> · {{ item.argumentSummary }}</template></code><pre>{{ item.technicalDetail }}</pre></details>
                    <details v-if="item.error" class="assistant-error-detail"><summary>错误详情</summary><pre>{{ item.error }}</pre></details>
                  </div>
                </article>
              </template>
              <p v-if="!visibleActivities.length && (running || waitingConfirmation)">{{ businessText(activeStep?.description || taskState.detail) }}</p>
            </div>
          </section>

          <p v-if="assistant.assistantMessage && assistant.run?.status === 'SUCCEEDED'" class="assistant-final-answer">{{ assistant.assistantMessage }}</p>
          <div v-if="assistant.error" class="assistant-error"><AlertCircle :size="15" /><span>{{ businessError(assistant.error) }}</span><details><summary>问题详情</summary><pre>{{ assistant.error }}</pre></details></div>

          <section v-if="assistant.plan && !directReply" class="assistant-tool-group">
            <button type="button" @click="showSteps = !showSteps">
              <span><ListChecks :size="15" /><strong>执行步骤</strong><small>{{ completedSteps }}/{{ assistant.plan.steps.length }} 已完成</small></span>
              <ChevronDown v-if="showSteps" :size="16" /><ChevronRight v-else :size="16" />
            </button>
            <div v-if="showSteps" class="assistant-tool-list">
              <div v-for="step in assistant.plan.steps" :key="step.id" class="assistant-tool-row" :data-state="stepState(step.order)">
                <span class="assistant-tool-status">
                  <Check v-if="stepState(step.order) === 'completed'" :size="13" />
                  <LoaderCircle v-else-if="stepState(step.order) === 'running'" :size="14" class="assistant-spinner" />
                  <AlertCircle v-else-if="stepState(step.order) === 'failed' || stepState(step.order) === 'canceled'" :size="14" />
                  <Circle v-else :size="10" />
                </span>
                <div><strong>{{ businessText(step.title, '处理当前步骤') }}</strong><p>{{ businessText(step.description) }}</p><details><summary>技术详情</summary><code>{{ step.tool }}</code><pre>{{ step.description }}</pre></details></div><small>{{ stepLabel(step.order) }}</small>
              </div>
            </div>
          </section>

          <div v-if="waitingConfirmation" class="assistant-confirm-inline">
            <ShieldCheck :size="18" /><p><strong>需要你的确认</strong><span>将执行上方列出的工作台修改。</span></p>
            <button class="primary-button" type="button" :disabled="assistant.busy" @click="assistant.confirm">确认执行</button>
          </div>

          <div v-if="assistant.run?.status === 'SUCCEEDED' && assistant.run.result?.createdProjectId" class="result-actions">
            <button class="secondary-button" type="button" @click="assistant.rollback"><RotateCcw :size="15" />撤销本次操作</button>
          </div>
        </article>
      </template>
    </div>

    <button v-if="showJumpToLatest" class="assistant-jump-latest" type="button" title="恢复自动跟随" @click="jumpToLatest">
      <ArrowDown :size="15" />{{ unseenActivityCount ? `${unseenActivityCount} 条新进展` : '回到最新' }}
    </button>

    <footer class="assistant-composer">
      <div class="assistant-context-chip"><span></span>{{ contextLabel }}</div>
      <div class="assistant-composer-box">
        <textarea ref="composer" v-model="assistant.input" rows="1" placeholder="向 Agent 交代任务或继续追问" @keydown="handleComposerKeydown"></textarea>
        <button v-if="assistant.canInterrupt || assistant.stopping" class="send-button stop" type="button" :title="assistant.stopping ? '正在中断' : '中断当前任务'" :disabled="assistant.stopping" @click="assistant.cancel"><LoaderCircle v-if="assistant.stopping" :size="14" class="assistant-spinner" /><Square v-else :size="14" /></button>
        <button v-else class="send-button" type="button" title="发送" :disabled="assistant.busy || assistant.stopping || !assistant.input.trim()" @click="send"><ArrowUp :size="18" /></button>
      </div>
      <p><span>{{ assistant.executionMode === 'AUTO' ? 'Auto 执行' : '审批执行' }}</span><i>·</i> Enter 发送 <i>·</i> Shift+Enter 换行</p>
    </footer>
  </aside>
</template>
