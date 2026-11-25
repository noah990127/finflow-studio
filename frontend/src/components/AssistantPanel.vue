<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  Activity, AlertCircle, ArrowDown, ArrowUp, Bot, BrainCircuit, Check, CheckCircle2,
  ChevronDown, ChevronRight, Circle, Clock3, FileOutput, History, ListChecks, LoaderCircle,
  MessageSquarePlus, PanelRightClose, Radio, RotateCcw, Search, ShieldCheck,
  Sparkles, Square, Zap,
} from 'lucide-vue-next'
import { useAssistantStore } from '../stores/assistant'
import { api, type AssistantMessage, type Project } from '../api/client'
import { businessError, businessText } from '../domain/workProgress'
import AssistantModelSettings from './AssistantModelSettings.vue'
import BrowserAuthorizations from './BrowserAuthorizations.vue'
import BrowserTaskHandoff from './BrowserTaskHandoff.vue'
import BrowserAgentTasks from './BrowserAgentTasks.vue'
import AssistantRichText from './AssistantRichText.vue'
import { createComposerKeyboard } from '../domain/composerKeyboard'
import { assistantElapsed } from '../domain/assistantElapsed'

const props = defineProps<{ project: Project | null }>()
const assistant = useAssistantStore()
const conversation = ref<HTMLElement | null>(null)
const composer = ref<HTMLTextAreaElement | null>(null)
const showEvents = ref(false)
const hasBrowserAgentTasks = ref(false)
const browserAgentActive = ref(false)
const browserTasks = ref<Awaited<ReturnType<typeof api.browserAgentTasks>>>([])
watch(() => assistant.sessionId, () => { browserTasks.value = [] })
const stoppingAll = ref(false)
async function stopCurrentTask() {
  if (stoppingAll.value) return
  const session = assistant.sessionId
  stoppingAll.value = true
  try {
    await assistant.cancel()
    if (session) {
      const tasks = await api.browserAgentTasks(session)
      for (const task of tasks.filter(t => ['submitted', 'working', 'input-required', 'auth-required'].includes(t.status.state))) {
        await api.browserAgentControl(task.id, 'cancel', task.metadata?.promptId)
      }
    }
    if (session === assistant.sessionId) browserAgentActive.value = false
  } catch (e) { assistant.error = e instanceof Error ? e.message : '任务暂时无法停止' }
  finally { stoppingAll.value = false }
}
const historyExpanded = ref(false)
const followingLatest = ref(true)
const unseenActivityCount = ref(0)
const now = ref(Date.now())
let clockTimer: number | undefined
let conversationObserver: MutationObserver | undefined

type HistoryTurn = { id: string; user?: AssistantMessage; assistant: AssistantMessage[] }

function historicalBrowserTasks(turn: HistoryTurn) {
  const traces = new Set(turn.assistant.map(message => message.traceId))
  return browserTasks.value.filter(task =>
    ['completed', 'failed', 'canceled', 'rejected'].includes(task.status.state)
    && task.conversationTraceIds?.some(trace => traces.has(trace)))
}

const contextLabel = computed(() => assistant.selection?.range.join('、') || assistant.contextTitle || '项目概览')
const running = computed(() => !assistant.interrupted && (['QUEUED', 'RUNNING'].includes(assistant.run?.status ?? '') || assistant.streaming))
const waitingConfirmation = computed(() => assistant.needsConfirmation && (!assistant.run || assistant.run.status === 'WAITING_CONFIRMATION'))
const pendingApprovalSteps = computed(() => assistant.plan?.steps.filter(step => step.requiresConfirmation && step.status === 'PENDING') ?? [])
const rememberBrowserAuthorization = ref(true)
const hasBrowserAuthorization = computed(() => pendingApprovalSteps.value.some(step => ['browser.open', 'browser.commit', 'browser.delegate'].includes(step.tool)))
const hasDomainAuthorization = computed(() => pendingApprovalSteps.value.some(step => step.tool === 'browser.delegate'))
watch(() => assistant.plan?.planHash, () => { rememberBrowserAuthorization.value = true })
const controlsLocked = computed(() => assistant.busy || waitingConfirmation.value
  || ['QUEUED', 'RUNNING'].includes(assistant.run?.status ?? ''))
const displayedExecutionMode = computed(() => running.value || waitingConfirmation.value
  ? assistant.activeExecutionMode || assistant.executionMode
  : assistant.executionMode)
const taskState = computed(() => {
  if (assistant.stopping) return { code: 'running', label: '正在中断', detail: '正在停止后续处理' }
  if (assistant.interrupted) return { code: 'canceled', label: '已中断', detail: assistant.progressLabel || '已停止本次处理，可以继续发送新的要求' }
  if (assistant.error || assistant.run?.status === 'FAILED') return { code: 'failed', label: '未完成', detail: businessError(assistant.error || assistant.run?.resultSummary || '执行遇到问题') }
  if (assistant.run?.status === 'CANCELED') return { code: 'canceled', label: '已停止', detail: '后续步骤没有继续执行' }
  if (assistant.run?.status === 'ROLLED_BACK') return { code: 'completed', label: '已撤销', detail: '已恢复到执行前状态' }
  if (assistant.run?.status === 'SUCCEEDED' && assistant.run.result?.workStatus === 'REVIEW_ONLY') return { code: 'completed', label: '已核对资料', detail: '本轮未执行新的操作' }
  if (assistant.run?.status === 'SUCCEEDED' && assistant.run.result?.workStatus === 'DELEGATED') return { code: 'waiting', label: '网页操作', detail: '执行状态与需要你处理的事项显示在输入框上方' }
  if (assistant.run?.status === 'SUCCEEDED' && assistant.run.result?.workStatus === 'WAITING_INPUT') return { code: 'waiting', label: '需要你的输入', detail: assistant.run.resultSummary }
  if (assistant.run?.status === 'SUCCEEDED') return { code: 'completed', label: '本轮已完成', detail: '' }
  if (waitingConfirmation.value) return { code: 'waiting', label: '等待确认', detail: assistant.run?.resultSummary || '确认后会继续修改工作台' }
  if (assistant.run?.status === 'QUEUED') return { code: 'running', label: '准备执行', detail: assistant.progressLabel }
  if (assistant.streaming || assistant.busy) return { code: 'running', label: assistant.plan ? '正在执行' : '正在分析', detail: assistant.progressLabel }
  if (assistant.plan) return { code: 'ready', label: '计划就绪', detail: assistant.plan.summary }
  return { code: 'idle', label: '就绪', detail: '' }
})
const latestActivity = computed(() => assistant.timeline.at(-1))
const liveReply = computed(() => assistant.timeline.findLast(item => item.eventType === 'agent.message')?.detail || '')
const showJumpToLatest = computed(() => !followingLatest.value && (running.value || unseenActivityCount.value > 0))
const activeStep = computed(() => assistant.plan?.steps.find(step => stepState(step.order) === 'running'))
const streamHealthLabel = computed(() => assistant.eventConnection === 'live' ? '实时' : assistant.eventConnection === 'reconnecting' ? '正在重连' : '连接中')
const elapsedLabel = computed(() => {
  return assistantElapsed(now.value, assistant.run, assistant.timeline, assistant.interruptedAt, waitingConfirmation.value)
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
  if (!props.project || controlsLocked.value || assistant.stopping) return
  const text = assistant.input.trim()
  if (!text) return
  void assistant.send(props.project.id, text)
}
async function continueBrowserTask(conversationId: string, browserSession: string) {
  if (!props.project || assistant.sessionId !== conversationId || controlsLocked.value || assistant.stopping) return
  const draft = assistant.input
  const pending = assistant.send(props.project.id,
    `我已完成网站中的人工操作，并通过平台按钮交还了浏览器控制权。请使用原浏览器会话 ${browserSession}，先观察并核验当前页面，再继续本对话的原任务；不要重新登录或重新打开网站，不要重复提交已执行的操作，对外提交仍按原授权规则确认。`)
  assistant.input = draft
  await pending
}
function switchSession(event: Event) { void assistant.switchSession((event.target as HTMLSelectElement).value) }
function clock(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
const composerKeyboard = createComposerKeyboard(send)
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
watch(() => assistant.open, open => { if (open) void scrollToLatest(true) })
watch(() => assistant.timeline.at(-1)?.id, (id, previous) => {
  if (id && previous && !followingLatest.value) unseenActivityCount.value += 1
})
watch(() => [assistant.timeline.at(-1)?.id, assistant.timeline.at(-1)?.detail, assistant.assistantMessage,
  assistant.history.length, assistant.run?.status, assistant.run?.currentStep,
  assistant.plan?.steps.length], () => scrollToLatest(), { flush: 'post' })
watch(() => assistant.currentRequest, (request, previous) => {
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
watch(() => props.project?.id, projectId => {
  if (projectId) void assistant.ensureSession(projectId)
}, { immediate: true })
onMounted(() => {
  clockTimer = window.setInterval(() => { now.value = Date.now() }, 1000)
  if (conversation.value) {
    conversationObserver = new MutationObserver(() => {
      if ((running.value || waitingConfirmation.value || hasBrowserAgentTasks.value) && followingLatest.value) void scrollToLatest()
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
        <div class="assistant-header-actions">
          <div class="assistant-mode-switch" role="group" aria-label="Agent 执行模式">
            <button type="button" :class="{ active: displayedExecutionMode === 'AUTO' }" :disabled="controlsLocked" title="浏览器在本次任务范围内自动操作，包括发送和提交；登录等必要操作仍会提醒" @click="assistant.setExecutionMode('AUTO')"><Zap :size="13" />自动执行</button>
            <button type="button" :class="{ active: displayedExecutionMode === 'APPROVAL' }" :disabled="controlsLocked" title="修改前确认；浏览器自动准备，在对外提交前确认内容" @click="assistant.setExecutionMode('APPROVAL')"><ShieldCheck :size="13" />逐步确认</button>
          </div>
          <button class="icon-button assistant-collapse-button" type="button" title="收起助手" @click="assistant.open = false"><PanelRightClose :size="18" /></button>
        </div>
      </div>
      <div class="assistant-control-row">
        <label class="assistant-session-switcher">
          <History :size="14" />
          <select :value="assistant.sessionId" :disabled="controlsLocked" aria-label="历史对话" @change="switchSession">
            <option v-for="session in assistant.sessions" :key="session.id" :value="session.id">{{ session.title }} · {{ clock(session.updatedAt) }}</option>
          </select>
          <button type="button" title="新建对话（当前项目作为初始上下文）" aria-label="新建对话" :disabled="controlsLocked || !props.project" @click="props.project && assistant.createNewSession(props.project.id)"><MessageSquarePlus :size="15" /></button>
        </label>
        <AssistantModelSettings :session-id="assistant.sessionId" :disabled="controlsLocked || waitingConfirmation || assistant.stopping" />
        <BrowserAuthorizations />
      </div>
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

      <button v-if="historyTurns.length > 2" class="text-button" @click="historyExpanded = !historyExpanded">{{ historyExpanded ? '收起较早对话' : `查看较早的 ${historyTurns.length - 2} 轮对话` }}</button>
      <section v-for="turn in (historyExpanded ? historyTurns : historyTurns.slice(-2))" :key="turn.id" class="assistant-history-group">
        <article v-if="turn.user" class="assistant-history-turn" data-role="user">
          <div class="assistant-turn-label">你 <time>{{ clock(turn.user.createdAt) }}</time></div>
          <p>{{ turn.user.content }}</p>
        </article>
        <article v-if="lastAssistant(turn)" class="assistant-history-turn" data-role="assistant">
          <div class="assistant-agent-avatar"><Sparkles :size="13" /></div>
          <div class="assistant-turn-label">Agent <time>{{ clock(lastAssistant(turn)!.createdAt) }}</time></div>
          <details v-if="earlierAssistant(turn).length" class="assistant-history-plan">
            <summary>查看当时计划</summary>
            <AssistantRichText v-for="message in earlierAssistant(turn)" :key="message.id" :content="message.content" />
          </details>
          <AssistantRichText :content="lastAssistant(turn)!.content" />
          <BrowserAgentTasks :session-id="assistant.sessionId || ''" :items="historicalBrowserTasks(turn)" />
        </article>
      </section>

        <article v-if="assistant.currentRequest" class="assistant-user-turn">
          <div class="assistant-turn-label">你</div>
          <p>{{ assistant.currentRequest }}</p>
        </article>

        <article v-show="assistant.currentRequest || hasBrowserAgentTasks || waitingConfirmation" class="assistant-agent-turn" :data-state="taskState.code">
          <div class="assistant-agent-avatar"><Sparkles :size="13" /></div>
          <div class="assistant-turn-label">
            Agent
            <span v-if="assistant.run?.result?.workStatus !== 'DELEGATED'" class="assistant-stream-health" :data-state="assistant.eventConnection"><Radio :size="10" />{{ streamHealthLabel }}</span>
            <time v-if="assistant.run?.result?.workStatus !== 'DELEGATED'" class="assistant-elapsed"><Clock3 :size="12" />{{ elapsedLabel }}</time>
          </div>

          <div v-if="!hasBrowserAgentTasks || assistant.run?.result?.workStatus !== 'DELEGATED'" class="assistant-run-state" aria-live="polite">
            <LoaderCircle v-if="taskState.code === 'running'" :size="16" class="assistant-spinner" />
            <CheckCircle2 v-else-if="taskState.code === 'completed'" :size="16" />
            <AlertCircle v-else-if="taskState.code === 'failed' || taskState.code === 'canceled'" :size="16" />
            <Circle v-else :size="14" />
            <strong>{{ taskState.label }}</strong>
            <span v-if="taskState.detail && !liveReply">{{ businessText(taskState.detail) }}</span>
          </div>

          <div v-if="running && liveReply" class="assistant-live-reply" aria-live="polite" aria-atomic="false">
            <AssistantRichText :content="liveReply" />
            <span class="assistant-live-cursor" aria-hidden="true"></span>
          </div>

          <section v-if="assistant.timeline.length || running || waitingConfirmation" class="assistant-work-process" aria-label="工作过程">
            <button type="button" class="assistant-process-toggle" :aria-expanded="showEvents" aria-controls="assistant-process-entries" @click="toggleActivities">
              <span><Activity :size="14" />工作过程 <small>{{ assistant.timeline.length }} 条进展</small></span>
              <span v-if="!showEvents && running" class="assistant-process-current" :title="latestActivity?.title || taskState.label">{{ latestActivity?.title || taskState.label }}</span>
              <ChevronDown v-if="showEvents" :size="15" /><ChevronRight v-else :size="15" />
            </button>
            <div v-show="showEvents" id="assistant-process-entries" class="assistant-process-entries">
              <template v-for="item in assistant.timeline" :key="item.id">
                <article v-if="item" class="assistant-activity-item" :data-tone="item.tone">
                  <span class="assistant-activity-icon"><component :is="activityIcon(item.kind)" :size="13" /></span>
                  <div>
                    <header><strong>{{ item.title }}</strong><time>{{ clock(item.time) }} · {{ activityStatus(item) }}</time></header>
                    <p>{{ item.detail }}</p>
                    <small v-if="item.resultSummary && item.resultSummary !== item.detail">结果：{{ businessText(item.resultSummary, '已收到这一步的处理结果') }}</small>
                    <small v-if="item.provenanceSummary">来源：{{ businessText(item.provenanceSummary, '已记录所用资料') }}</small>
                    <details v-if="item.toolName || item.technicalDetail !== item.detail" class="assistant-technical-detail"><summary>技术详情</summary><code>{{ item.toolName }}<template v-if="item.argumentSummary"> · {{ item.argumentSummary }}</template></code><pre>{{ item.technicalDetail }}</pre></details>
                    <details v-if="item.error" class="assistant-error-detail"><summary>错误详情</summary><pre>{{ item.error }}</pre></details>
                  </div>
                </article>
              </template>
              <p v-if="!assistant.timeline.length && (running || waitingConfirmation)">{{ businessText(activeStep?.description || taskState.detail) }}</p>
            </div>
          </section>

          <AssistantRichText v-if="assistant.assistantMessage && assistant.run?.status === 'SUCCEEDED' && assistant.run.result?.workStatus !== 'DELEGATED'" class="assistant-final-answer" :content="assistant.assistantMessage" />
          <div v-if="assistant.error" class="assistant-error"><AlertCircle :size="15" /><span>{{ businessError(assistant.error) }}</span><details><summary>问题详情</summary><pre>{{ assistant.error }}</pre></details></div>


          <div v-if="assistant.run?.status === 'SUCCEEDED' && assistant.run.result?.createdProjectId" class="result-actions">
            <button class="secondary-button" type="button" @click="assistant.rollback"><RotateCcw :size="15" />撤销本次操作</button>
          </div>
      <BrowserAgentTasks :session-id="assistant.sessionId || ''" :step-ids="assistant.plan?.steps.map(step => step.id) || []" @updated="browserTasks = $event" @present="hasBrowserAgentTasks = $event" @active="browserAgentActive = $event" />
      <BrowserTaskHandoff v-if="!hasBrowserAgentTasks" :session-id="assistant.sessionId || ''" :locked="controlsLocked || assistant.stopping" :waiting="taskState.code === 'waiting'" @continue="continueBrowserTask" />
      <div v-if="waitingConfirmation" class="assistant-confirm-inline" role="region" aria-label="待确认操作">
        <ShieldCheck :size="18" /><p><strong>需要你的确认</strong><span>{{ businessText(taskState.detail, '确认后执行待授权操作') }}</span></p>
        <button class="approval-action" type="button" :disabled="assistant.busy" @click="assistant.confirm(hasBrowserAuthorization && rememberBrowserAuthorization)">{{ assistant.busy ? '确认中…' : hasDomainAuthorization ? '允许访问' : '允许执行' }}</button>
        <p v-if="assistant.error" class="assistant-confirm-error">{{ businessError(assistant.error) }}</p>
        <details v-if="pendingApprovalSteps.length" class="assistant-approval-scope" open>
          <summary>本次确认范围</summary>
          <span v-if="hasDomainAuthorization">网站授权保存到当前账号，30天内复用，可随时撤销</span>
          <label v-else-if="hasBrowserAuthorization"><input v-model="rememberBrowserAuthorization" type="checkbox" /> 记住相同目标与范围，30天内有效；修改内容须重新确认</label>
          <pre v-for="step in pendingApprovalSteps" :key="step.id">{{ step.description }}</pre>
        </details>
      </div>
        </article>
    </div>

    <button v-if="showJumpToLatest" class="assistant-jump-latest" type="button" title="恢复自动跟随" @click="jumpToLatest">
      <ArrowDown :size="15" />{{ unseenActivityCount ? `${unseenActivityCount} 条新进展` : '回到最新' }}
    </button>

    <footer class="assistant-composer">
      <div class="assistant-context-chip"><span></span>{{ contextLabel }}</div>
      <div class="assistant-composer-box">
        <textarea ref="composer" v-model="assistant.input" rows="1" placeholder="向 Agent 交代任务或继续追问" @keydown="composerKeyboard.keydown" @compositionstart="composerKeyboard.compositionStart" @compositionend="composerKeyboard.compositionEnd" @blur="composerKeyboard.blur"></textarea>
        <button v-if="assistant.canInterrupt || browserAgentActive || assistant.stopping || stoppingAll" class="send-button stop" type="button" :title="assistant.stopping || stoppingAll ? '正在中断' : '中断当前任务'" :disabled="assistant.stopping || stoppingAll" @click="stopCurrentTask"><LoaderCircle v-if="assistant.stopping || stoppingAll" :size="14" class="assistant-spinner" /><Square v-else :size="14" /></button>
        <button v-else class="send-button" type="button" title="发送" :disabled="controlsLocked || assistant.stopping || !assistant.input.trim()" @click="send"><ArrowUp :size="18" /></button>
      </div>
      <p><span>{{ displayedExecutionMode === 'AUTO' ? '自动执行' : '逐步确认' }}</span><i>·</i> Enter 发送 <i>·</i> Shift+Enter 换行</p>
    </footer>
  </aside>
</template>
