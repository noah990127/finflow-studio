<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { AlertCircle, ArrowUp, Bot, Check, CheckCircle2, ChevronDown, ChevronRight, Circle, LoaderCircle, PanelRightClose, RotateCcw, ShieldCheck, Sparkles, Square, Wrench, Zap } from 'lucide-vue-next'
import { useAssistantStore } from '../stores/assistant'
import type { Project } from '../api/client'

const props = defineProps<{ project: Project | null }>()
const emit = defineEmits<{ workbenchAction: [action: Record<string, unknown>] }>()
const assistant = useAssistantStore()
const conversation = ref<HTMLElement | null>(null)
const showSteps = ref(true)
const showEvents = ref(true)

const contextLabel = computed(() => assistant.selection?.range.join('、') || assistant.contextTitle || '项目概览')
const running = computed(() => ['QUEUED', 'RUNNING'].includes(assistant.run?.status ?? '') || assistant.streaming)
const taskState = computed(() => {
  if (assistant.error || assistant.run?.status === 'FAILED') return { code: 'failed', label: '未完成', detail: assistant.error || assistant.run?.resultSummary || '执行遇到问题' }
  if (assistant.run?.status === 'CANCELED') return { code: 'canceled', label: '已停止', detail: '后续步骤没有继续执行' }
  if (assistant.run?.status === 'ROLLED_BACK') return { code: 'completed', label: '已撤销', detail: '已恢复到执行前状态' }
  if (assistant.run?.status === 'SUCCEEDED') return { code: 'completed', label: '已完成', detail: assistant.run.resultSummary || '任务已经完成' }
  if (assistant.needsConfirmation && !assistant.run) return { code: 'waiting', label: '等待确认', detail: '确认后会开始修改工作台' }
  if (assistant.run?.status === 'QUEUED') return { code: 'running', label: '准备执行', detail: assistant.progressLabel }
  if (assistant.streaming || assistant.busy) return { code: 'running', label: assistant.plan ? '正在执行' : '正在思考', detail: assistant.progressLabel }
  if (assistant.plan) return { code: 'ready', label: '计划就绪', detail: assistant.plan.summary }
  return { code: 'idle', label: '就绪', detail: '' }
})
const completedSteps = computed(() => {
  if (!assistant.plan) return 0
  if (['SUCCEEDED', 'ROLLED_BACK'].includes(assistant.run?.status ?? '')) return assistant.plan.steps.length
  const current = assistant.run?.currentStep ?? 0
  return Math.max(0, current - (running.value || assistant.run?.status === 'FAILED' ? 1 : 0))
})
function stepState(order: number) {
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
function clock(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
async function scrollToLatest() {
  await nextTick()
  if (conversation.value) conversation.value.scrollTop = conversation.value.scrollHeight
}
watch(() => [assistant.timeline.length, assistant.assistantMessage, assistant.currentRequest], scrollToLatest)
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
</script>

<template>
  <button v-if="!assistant.open" class="assistant-launcher" type="button" title="打开 AI 助手" @click="assistant.open = true"><Sparkles :size="19" /></button>

  <aside v-else class="assistant-drawer assistant-codex-panel" aria-label="AI 助手">
    <header class="assistant-header">
      <div>
        <div class="assistant-title"><Bot :size="18" /> AI 助手</div>
        <p><span></span>{{ props.project?.name ?? '个人工作台' }} · {{ contextLabel }}</p>
      </div>
      <div class="assistant-header-actions">
        <div class="assistant-mode-switch" role="group" aria-label="Agent 执行模式">
          <button type="button" :class="{ active: assistant.executionMode === 'AUTO' }" :disabled="running || assistant.busy" title="自动执行 LLM 选择的工具" @click="assistant.setExecutionMode('AUTO')"><Zap :size="12" />Auto</button>
          <button type="button" :class="{ active: assistant.executionMode === 'APPROVAL' }" :disabled="running || assistant.busy" title="修改前需要确认" @click="assistant.setExecutionMode('APPROVAL')"><ShieldCheck :size="12" />审批</button>
        </div>
        <button class="icon-button" type="button" title="收起助手" @click="assistant.open = false"><PanelRightClose :size="18" /></button>
      </div>
    </header>

    <div ref="conversation" class="assistant-body assistant-conversation">
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
          <div class="assistant-turn-label"><span><Sparkles :size="13" /></span>Agent</div>
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

          <section v-if="assistant.plan" class="assistant-tool-group">
            <button type="button" @click="showSteps = !showSteps">
              <span><Wrench :size="14" /><strong>工作过程</strong><small>{{ showSteps ? '从上到下执行' : `${completedSteps}/${assistant.plan.steps.length} 已完成` }}</small></span>
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

          <div v-if="assistant.needsConfirmation && !assistant.run" class="assistant-confirm-inline">
            <ShieldCheck :size="17" /><p><strong>需要你的确认</strong><span>这些步骤会更新工作台，原内容会保留。</span></p>
            <button class="primary-button" type="button" :disabled="assistant.busy" @click="assistant.confirm">确认执行</button>
          </div>

          <p v-if="assistant.assistantMessage && assistant.run?.status === 'SUCCEEDED'" class="assistant-final-answer">{{ assistant.assistantMessage }}</p>
          <p v-if="assistant.error" class="assistant-error">{{ assistant.error }}</p>

          <details v-if="assistant.timeline.length" class="assistant-event-details assistant-activity-stream" :open="showEvents" @toggle="showEvents = ($event.target as HTMLDetailsElement).open">
            <summary>Agent 活动 · {{ assistant.timeline.length }} 条</summary>
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
