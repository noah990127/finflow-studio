import { defineStore } from 'pinia'
import { api, type AssistantEvent, type AssistantMessage, type ContextSnapshot, type Plan, type Run, type Selection } from '../api/client'
import { useProjectsStore } from './projects'

type TimelineItem = {
  id: string
  title: string
  detail: string
  tone: 'info' | 'success' | 'warning'
  time: string
  toolName?: string
  argumentSummary?: string
  resultSummary?: string
  error?: string
  status?: string
}

const eventTypes = [
  'assistant.request.received', 'assistant.context.started', 'assistant.planning.started',
  'assistant.plan.ready', 'assistant.confirmation.required', 'assistant.run.queued',
  'assistant.run.started', 'assistant.step.started', 'assistant.step.completed',
  'assistant.run.completed', 'assistant.run.failed', 'assistant.run.canceled',
  'assistant.rollback.completed',
  'agent.thinking_summary', 'agent.planning', 'agent.skill_loading', 'agent.tool_search',
  'agent.tool_call', 'agent.executing', 'agent.observation', 'agent.plan_updated',
  'agent.waiting_confirmation', 'agent.generating', 'agent.retrying', 'agent.completed',
  'agent.failed', 'agent.cancelled',
]
let eventSource: EventSource | null = null
let eventSessionId = ''

export const useAssistantStore = defineStore('assistant', {
  state: () => ({
    open: false,
    sessionId: '',
    sessionProjectId: '',
    input: '',
    busy: false,
    error: '',
    assistantMessage: '',
    currentRequest: '',
    plan: null as Plan | null,
    context: null as ContextSnapshot | null,
    run: null as Run | null,
    selection: null as Selection | null,
    pageContext: 'project-home',
    contextTitle: '项目概览',
    timeline: [] as TimelineItem[],
    progress: 0,
    progressLabel: '',
    streaming: false,
    streamLines: [] as string[],
    lastEventSequence: 0,
    workbenchAction: null as Record<string, unknown> | null,
    workbenchActionSequence: 0,
    executionMode: (localStorage.getItem('finflow.assistant.executionMode') === 'AUTO' ? 'AUTO' : 'APPROVAL') as 'AUTO' | 'APPROVAL',
    history: [] as AssistantMessage[],
    historySessionId: '',
  }),
  getters: {
    needsConfirmation: (state) =>
      state.plan?.steps.some((step) => step.requiresConfirmation) ?? false,
  },
  actions: {
    openWithSuggestion(text: string, selection?: Selection) {
      this.open = true
      this.input = text
      this.selection = selection ?? null
    },
    setWorkbenchContext(page: string, title: string, selection?: Selection) {
      this.pageContext = page
      this.contextTitle = title
      this.selection = selection ?? null
    },
    setExecutionMode(mode: 'AUTO' | 'APPROVAL') {
      if (this.busy || this.streaming) return
      this.executionMode = mode
      localStorage.setItem('finflow.assistant.executionMode', mode)
    },
    async ensureSession(projectId: string) {
      if (!this.sessionId || this.sessionProjectId !== projectId) {
        const storageKey = `finflow.assistant.session.${projectId}`
        const storedId = localStorage.getItem(storageKey) ?? ''
        let session = null
        if (storedId) {
          try {
            const existing = await api.getAssistantSession(storedId)
            if (existing.projectId === projectId) session = existing
          } catch { localStorage.removeItem(storageKey) }
        }
        if (!session) session = await api.createSession(projectId)
        this.sessionId = session.id
        this.sessionProjectId = projectId
        localStorage.setItem(storageKey, session.id)
        this.lastEventSequence = 0
      }
      this.connectEvents()
      if (this.historySessionId !== this.sessionId) await this.loadHistory()
    },
    async loadHistory() {
      if (!this.sessionId) return
      this.history = await api.listAssistantMessages(this.sessionId)
      this.historySessionId = this.sessionId
    },
    connectEvents() {
      if (!this.sessionId || (eventSource && eventSessionId === this.sessionId)) return
      eventSource?.close()
      eventSessionId = this.sessionId
      eventSource = new EventSource(`/api/assistant/sessions/${this.sessionId}/events`)
      for (const type of eventTypes) {
        eventSource.addEventListener(type, (raw) => {
          try { this.handleEvent(JSON.parse((raw as MessageEvent).data) as AssistantEvent) } catch { /* polling remains as fallback */ }
        })
      }
      eventSource.onerror = () => { this.streaming = Boolean(this.run && ['QUEUED', 'RUNNING'].includes(this.run.status)) }
    },
    publishWorkbenchAction(action: Record<string, unknown>) {
      this.workbenchAction = action
      this.workbenchActionSequence += 1
      window.dispatchEvent(new CustomEvent('finflow:assistant-action', { detail: action }))
    },
    handleEvent(event: AssistantEvent) {
      if (event.sessionId !== this.sessionId || event.eventSeq <= this.lastEventSequence) return
      this.lastEventSequence = event.eventSeq
      const payload = event.payload ?? {}
      const value = Number(payload.progress)
      if (Number.isFinite(value)) this.progress = Math.max(0, Math.min(100, value))
      const message = String(payload.message ?? payload.result ?? payload.summary ?? '').trim()
      const title = String(payload.title ?? this.eventTitle(event.type)).trim()
      if (message) {
        this.progressLabel = message
        this.streamLines.push(message)
        this.streamLines = this.streamLines.slice(-12)
      }
      if (event.type === 'assistant.request.received' || event.type === 'assistant.run.queued' || event.type === 'assistant.run.started' || event.type === 'assistant.step.started') this.streaming = true
      if (event.type === 'assistant.confirmation.required') this.streaming = false
      if (event.type === 'assistant.run.completed') {
        this.streaming = false
        this.progress = 100
      }
      if (event.type === 'assistant.run.failed' || event.type === 'assistant.run.canceled') this.streaming = false
      const uiAction = payload.uiAction
      if (event.type === 'assistant.step.completed' && uiAction && typeof uiAction === 'object') {
        this.pushTimeline(`action-${event.eventSeq}`, '同步工作台', '正在把这一步的结果呈现在左侧工作区', 'info', event.createdAt)
        this.publishWorkbenchAction(uiAction as Record<string, unknown>)
      }
      if (title && message) this.pushTimeline(`event-${event.eventSeq}`, title, message,
        event.type.endsWith('completed') || event.type === 'agent.observation' || event.type === 'agent.completed' ? 'success' : event.type.includes('failed') || event.type.includes('confirmation') ? 'warning' : 'info',
        event.createdAt, {
          toolName: typeof payload.toolName === 'string' ? payload.toolName : typeof payload.tool === 'string' ? payload.tool : undefined,
          argumentSummary: typeof payload.argumentSummary === 'string' ? payload.argumentSummary : undefined,
          resultSummary: typeof payload.resultSummary === 'string' ? payload.resultSummary : typeof payload.result === 'string' ? payload.result : undefined,
          error: typeof payload.error === 'string' ? payload.error : undefined,
          status: typeof payload.status === 'string' ? payload.status : undefined,
        })
    },
    eventTitle(type: string) {
      if (type === 'agent.thinking_summary') return '正在思考'
      if (type === 'agent.planning') return '制定计划'
      if (type === 'agent.skill_loading') return '加载 Skill'
      if (type === 'agent.tool_search') return '搜索工具'
      if (type === 'agent.tool_call') return '调用工具'
      if (type === 'agent.executing') return '执行中'
      if (type === 'agent.observation') return '读取结果'
      if (type === 'agent.plan_updated') return '计划已调整'
      if (type === 'agent.waiting_confirmation') return '等待确认'
      if (type === 'agent.generating') return '整理结果'
      if (type === 'agent.retrying') return '重试'
      if (type === 'agent.completed') return '已完成'
      if (type === 'agent.failed') return '失败'
      if (type === 'agent.cancelled') return '已停止'
      if (type === 'assistant.request.received') return '理解需求'
      if (type === 'assistant.context.started') return '读取当前工作'
      if (type === 'assistant.planning.started') return '理解意图与选择能力'
      if (type === 'assistant.plan.ready') return '计划已准备好'
      if (type === 'assistant.confirmation.required') return '等待确认'
      if (type === 'assistant.run.queued') return '准备处理'
      if (type === 'assistant.run.started') return '开始处理'
      if (type === 'assistant.run.completed') return '处理完成'
      if (type === 'assistant.run.failed') return '处理未完成'
      if (type === 'assistant.run.canceled') return '处理已停止'
      if (type === 'assistant.rollback.completed') return '已撤销'
      return '当前步骤'
    },
    pushTimeline(id: string, title: string, detail: string, tone: TimelineItem['tone'], time = new Date().toISOString(), extra: Partial<TimelineItem> = {}) {
      if (this.timeline.some(item => item.id === id)) return
      const latest = this.timeline.at(-1)
      if (latest?.title === title && latest.detail === detail) return
      this.timeline.push({ id, title, detail, tone, time, ...extra })
    },
    async send(projectId: string) {
      const text = this.input.trim()
      if (!text || this.busy) return
      this.busy = true
      this.error = ''
      this.plan = null
      this.run = null
      this.timeline = []
      this.progress = 2
      this.progressLabel = '正在接收你的需求'
      this.streaming = true
      this.streamLines = ['正在接收你的需求']
      try {
        await this.ensureSession(projectId)
        if (this.currentRequest) await this.loadHistory()
        this.currentRequest = text
        const response = await api.sendMessage(this.sessionId, text, this.pageContext, this.selection ?? undefined, this.executionMode)
        this.assistantMessage = response.assistantMessage
        this.plan = response.plan
        this.context = response.context
        this.run = response.run ?? null
        this.input = ''
        if (!this.timeline.some(item => item.title === '计划已准备好')) this.pushTimeline(
          `plan-${response.plan.id}`, '计划已准备好',
          this.needsConfirmation ? '请检查会修改的内容' : '只读取和生成草稿，可直接进行',
          this.needsConfirmation ? 'warning' : 'info')
        if (this.needsConfirmation) {
          this.streaming = false
          this.progressLabel = '计划已准备好，等待你确认'
        }
        if (this.run) await this.watchRun()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '助手暂时没有完成请求'
        this.progressLabel = '本次任务未完成'
        this.progress = 100
        this.streaming = false
      } finally {
        this.busy = false
      }
    },
    async confirm() {
      if (!this.plan || !this.context || this.busy) return
      this.busy = true
      this.error = ''
      this.streaming = true
      this.progressLabel = '正在启动处理任务'
      try {
        this.run = await api.confirmPlan(this.plan, this.context)
        this.pushTimeline(`confirmed-${this.run.id}`, '已确认，开始处理', '原内容会保留', 'info')
        await this.watchRun()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '计划没有执行'
        this.streaming = false
      } finally {
        this.busy = false
      }
    },
    async watchRun() {
      if (!this.run) return
      for (let attempt = 0; attempt < 300; attempt += 1) {
        this.run = await api.getRun(this.run.id)
        if (['SUCCEEDED', 'FAILED', 'CANCELED', 'ROLLED_BACK'].includes(this.run.status)) break
        await new Promise((resolve) => window.setTimeout(resolve, 1000))
      }
      if (this.run.status === 'SUCCEEDED') {
        this.progress = 100
        this.progressLabel = this.run.resultSummary
        this.streaming = false
        this.pushTimeline(`completed-${this.run.id}`, '处理完成', this.run.resultSummary, 'success')
        const assistantResponse = typeof this.run.result?.assistantResponse === 'string' ? this.run.result.assistantResponse : ''
        if (assistantResponse) this.assistantMessage = assistantResponse
        const createdProjectId = typeof this.run.result?.createdProjectId === 'string' ? this.run.result.createdProjectId : ''
        if (createdProjectId) await useProjectsStore().refresh(createdProjectId)
        const uiAction = this.run.result?.uiAction
        if (uiAction && typeof uiAction === 'object') {
          this.pushTimeline(`action-final-${this.run.id}`, '同步工作台', '已将最终结果呈现在工作区', 'success')
          this.publishWorkbenchAction(uiAction as Record<string, unknown>)
        }
      } else if (['FAILED', 'CANCELED'].includes(this.run.status)) {
        this.streaming = false
        this.pushTimeline(`ended-${this.run.id}`, '处理未完成', this.run.resultSummary || '可以检查后重新执行', 'warning')
      }
    },
    async cancel() {
      if (!this.run || !['QUEUED', 'RUNNING'].includes(this.run.status)) return
      try {
        this.run = await api.cancelAssistantRun(this.run.id)
        this.streaming = false
        this.progressLabel = '任务已停止'
        this.pushTimeline(`canceled-${this.run.id}`, '任务已停止', '没有继续执行后续步骤', 'warning')
      } catch (error) {
        this.error = error instanceof Error ? error.message : '任务暂时无法停止'
      }
    },
    async rollback() {
      if (!this.run || this.run.status !== 'SUCCEEDED') return
      this.run = await api.rollback(this.run.id)
      this.pushTimeline(`rollback-${this.run.id}`, '已撤销', '已恢复到执行前版本', 'success')
    },
  },
})
