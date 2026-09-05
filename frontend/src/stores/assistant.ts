import { defineStore } from 'pinia'
import { api, type AssistantEvent, type AssistantMessage, type AssistantSession, type ContextSnapshot, type Plan, type Run, type Selection } from '../api/client'
import { useProjectsStore } from './projects'
import { businessText, workProgress } from '../domain/workProgress'

type TimelineItem = {
  id: string
  title: string
  detail: string
  tone: 'info' | 'success' | 'warning'
  kind: 'thinking' | 'planning' | 'skill' | 'tool' | 'observation' | 'confirmation' | 'generation' | 'result'
  time: string
  eventType?: string
  toolName?: string
  argumentSummary?: string
  resultSummary?: string
  provenanceSummary?: string
  error?: string
  status?: string
  progress?: number
  technicalDetail?: string
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
    stopping: false,
    interrupted: false,
    interruptedAt: '',
    activeRequestId: '',
    interruptedRuns: [] as string[],
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
    eventConnection: 'idle' as 'idle' | 'connecting' | 'live' | 'reconnecting',
    streamLines: [] as string[],
    lastEventSequence: 0,
    workbenchAction: null as Record<string, unknown> | null,
    workbenchActionSequence: 0,
    executionMode: (localStorage.getItem('finflow.assistant.executionMode') === 'AUTO' ? 'AUTO' : 'APPROVAL') as 'AUTO' | 'APPROVAL',
    history: [] as AssistantMessage[],
    historySessionId: '',
    sessions: [] as AssistantSession[],
    sessionsProjectId: '',
  }),
  getters: {
    needsConfirmation: (state) =>
      !state.interrupted && state.plan?.status !== 'CANCELED' && (state.plan?.steps.some((step) => step.requiresConfirmation && step.status === 'PENDING') ?? false),
    canInterrupt: (state) => !state.interrupted && (state.busy || state.streaming
      || ['QUEUED', 'RUNNING', 'WAITING_CONFIRMATION'].includes(state.run?.status ?? '')
      || ['WAITING_CONFIRMATION', 'PLAN_READY'].includes(state.plan?.status ?? '')),
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
      if (this.busy || (this.run && ['QUEUED', 'RUNNING'].includes(this.run.status))) return
      this.executionMode = mode
      localStorage.setItem('finflow.assistant.executionMode', mode)
    },
    async ensureSession(projectId: string) {
      if (this.sessionsProjectId !== projectId) {
        this.sessions = await api.listAssistantSessions(projectId)
        this.sessionsProjectId = projectId
      }
      if (!this.sessionId || this.sessionProjectId !== projectId) {
        const storageKey = `finflow.assistant.session.${projectId}`
        const storedId = localStorage.getItem(storageKey) ?? ''
        let session = this.sessions.find(item => item.id === storedId) ?? this.sessions[0] ?? null
        if (!session) session = await api.createSession(projectId)
        if (!this.sessions.some(item => item.id === session.id)) this.sessions.unshift(session)
        await this.activateSession(session)
      }
      if (this.historySessionId !== this.sessionId) await this.loadHistory(true)
      this.connectEvents()
    },
    async createNewSession(projectId: string) {
      if (this.busy || (this.run && ['QUEUED', 'RUNNING'].includes(this.run.status))) return
      const stamp = new Date().toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
      const session = await api.createSession(projectId, `新对话 ${stamp}`)
      this.sessions.unshift(session)
      this.sessionsProjectId = projectId
      await this.activateSession(session)
    },
    async switchSession(sessionId: string) {
      if (sessionId === this.sessionId || this.busy || (this.run && ['QUEUED', 'RUNNING'].includes(this.run.status))) return
      const session = this.sessions.find(item => item.id === sessionId)
      if (session) await this.activateSession(session)
    },
    async activateSession(session: AssistantSession) {
      eventSource?.close()
      eventSource = null
      eventSessionId = ''
      this.sessionId = session.id
      this.sessionProjectId = session.projectId
      localStorage.setItem(`finflow.assistant.session.${session.projectId}`, session.id)
      this.currentRequest = ''
      this.assistantMessage = ''
      this.plan = null
      this.context = null
      this.run = null
      this.timeline = []
      this.progress = 0
      this.progressLabel = ''
      this.streaming = false
      this.error = ''
      if (!this.busy) {
        this.interrupted = false
        this.interruptedAt = ''
        this.activeRequestId = ''
      }
      this.lastEventSequence = 0
      this.history = []
      this.historySessionId = ''
      await this.loadHistory(!this.busy)
      this.connectEvents()
    },
    async loadHistory(restoreLatestRun = true) {
      if (!this.sessionId) return
      const [messages, events] = await Promise.all([
        api.listAssistantMessages(this.sessionId),
        restoreLatestRun ? api.listAssistantEvents(this.sessionId) : Promise.resolve([]),
      ])
      this.history = messages
      this.historySessionId = this.sessionId
      if (!restoreLatestRun || !events.length) return

      const lastRequestIndex = events.findLastIndex(event => event.type === 'assistant.request.received')
      const latestEvents = lastRequestIndex >= 0 ? events.slice(lastRequestIndex) : []
      const lastUserIndex = messages.findLastIndex(message => message.role === 'USER')
      if (!latestEvents.length || lastUserIndex < 0) return

      this.currentRequest = messages[lastUserIndex].content
      const latestAssistant = messages.slice(lastUserIndex + 1).filter(message => message.role === 'ASSISTANT').at(-1)
      this.assistantMessage = latestAssistant?.content ?? ''
      this.history = messages.slice(0, lastUserIndex)
      this.timeline = []
      this.progress = 0
      this.progressLabel = ''
      this.lastEventSequence = 0
      for (const event of latestEvents) this.handleEvent(event, true)

      const planId = latestEvents.map(event => event.payload?.planId).findLast(value => typeof value === 'string')
      const runId = latestEvents.map(event => event.runId).findLast(value => typeof value === 'string')
      if (typeof planId === 'string') {
        try { this.plan = await api.getAssistantPlan(planId) } catch { /* history still remains readable */ }
      }
      if (typeof runId === 'string') {
        try { this.run = await api.getRun(runId) } catch { /* history still remains readable */ }
      }
      this.streaming = Boolean(this.run && ['QUEUED', 'RUNNING'].includes(this.run.status))
      if (!this.assistantMessage && this.run?.resultSummary) this.assistantMessage = this.run.resultSummary
    },
    connectEvents() {
      if (!this.sessionId || (eventSource && eventSessionId === this.sessionId)) return
      eventSource?.close()
      eventSessionId = this.sessionId
      this.eventConnection = 'connecting'
      eventSource = new EventSource(`/api/assistant/sessions/${this.sessionId}/events`)
      eventSource.onopen = () => { this.eventConnection = 'live' }
      for (const type of eventTypes) {
        eventSource.addEventListener(type, (raw) => {
          try { this.handleEvent(JSON.parse((raw as MessageEvent).data) as AssistantEvent) } catch { /* polling remains as fallback */ }
        })
      }
      eventSource.onerror = () => {
        this.eventConnection = 'reconnecting'
        this.streaming = Boolean(this.run && ['QUEUED', 'RUNNING'].includes(this.run.status))
      }
    },
    async syncEvents() {
      if (!this.sessionId) return
      try {
        const events = await api.listAssistantEvents(this.sessionId, this.lastEventSequence)
        for (const event of events) this.handleEvent(event)
      } catch { /* SSE remains primary; run polling will try again */ }
    },
    publishWorkbenchAction(action: Record<string, unknown>) {
      this.workbenchAction = action
      this.workbenchActionSequence += 1
      window.dispatchEvent(new CustomEvent('finflow:assistant-action', { detail: action }))
    },
    handleEvent(event: AssistantEvent, replay = false) {
      if (event.sessionId !== this.sessionId || event.eventSeq <= this.lastEventSequence) return
      this.lastEventSequence = event.eventSeq
      if (event.runId && this.interruptedRuns.includes(event.runId)) return
      const payload = event.payload ?? {}
      if (event.type === 'assistant.request.received' && typeof payload.requestId === 'string' && (!this.activeRequestId || (replay && !this.busy))) this.activeRequestId = payload.requestId
      if (this.interrupted && event.type !== 'agent.cancelled') return
      const value = Number(payload.progress)
      if (Number.isFinite(value)) this.progress = Math.max(0, Math.min(100, value))
      const message = String(payload.message ?? payload.result ?? payload.summary ?? '').trim()
      const title = String(payload.title ?? this.eventTitle(event.type)).trim()
      const publicProgress = workProgress({ type: event.type, message, phase: String(payload.phase ?? ''), toolName: String(payload.toolName ?? payload.tool ?? ''), status: String(payload.status ?? ''), error: typeof payload.error === 'string' ? payload.error : undefined })
      if (message) {
        this.progressLabel = publicProgress.detail
        this.streamLines.push(publicProgress.detail)
        this.streamLines = this.streamLines.slice(-12)
      }
      if (event.type === 'assistant.request.received' || event.type === 'assistant.run.queued' || event.type === 'assistant.run.started' || event.type === 'assistant.step.started') this.streaming = true
      if (event.type === 'assistant.confirmation.required' || event.type === 'agent.waiting_confirmation') this.streaming = false
      if (event.type === 'agent.plan_updated' || event.type === 'assistant.confirmation.required' || event.type === 'assistant.run.completed') {
        const planId = typeof payload.planId === 'string' ? payload.planId : this.plan?.id
        if (planId) void this.refreshPlan(planId)
      }
      if (event.type === 'assistant.run.completed') {
        this.streaming = false
        this.progress = 100
        if (message) this.assistantMessage = message
        if (!replay) {
          this.publishWorkbenchAction({
            type: 'REFRESH_WORKSPACE',
            projectId: this.sessionProjectId,
            refreshWorkspace: true,
          })
        }
      }
      if (event.type === 'assistant.run.failed' || event.type === 'agent.failed') {
        this.streaming = false
        this.error = String(payload.error ?? payload.message ?? 'Agent 未能完成这次任务')
      }
      if (event.type === 'assistant.run.canceled' || event.type === 'agent.cancelled') {
        this.streaming = false
        this.interrupted = true
        this.interruptedAt = event.createdAt
      }
      const uiAction = payload.uiAction
      if (!replay && event.type === 'assistant.step.completed' && uiAction && typeof uiAction === 'object') {
        this.pushTimeline(`action-${event.eventSeq}`, '同步工作台', '正在把这一步的结果呈现在左侧工作区', 'info', event.createdAt)
        this.publishWorkbenchAction(uiAction as Record<string, unknown>)
      }
      const isPublicActivity = event.type.startsWith('agent.')
        || event.type === 'assistant.request.received'
        || event.type === 'assistant.context.started'
      if (isPublicActivity && title && message) this.pushTimeline(`event-${event.eventSeq}`, publicProgress.title, publicProgress.detail,
          payload.error || String(payload.status).toLowerCase() === 'failed' || event.type.includes('failed') || event.type.includes('confirmation') ? 'warning' : event.type.endsWith('completed') ? 'success' : 'info',
          event.createdAt, {
            technicalDetail: message,
            kind: this.eventKind(event.type),
            eventType: event.type,
            toolName: typeof payload.toolName === 'string' ? payload.toolName : typeof payload.tool === 'string' ? payload.tool : undefined,
            argumentSummary: typeof payload.argumentSummary === 'string' ? payload.argumentSummary : undefined,
            resultSummary: typeof payload.resultSummary === 'string' ? payload.resultSummary : typeof payload.result === 'string' ? payload.result : undefined,
            provenanceSummary: this.summarizeProvenance(payload.provenance),
            error: typeof payload.error === 'string' ? payload.error : undefined,
            status: typeof payload.status === 'string' ? payload.status : undefined,
            progress: Number.isFinite(value) ? value : undefined,
          })
    },
    eventKind(type: string): TimelineItem['kind'] {
      if (type.includes('thinking')) return 'thinking'
      if (type.includes('planning') || type.includes('plan_updated')) return 'planning'
      if (type.includes('skill')) return 'skill'
      if (type.includes('tool_search') || type.includes('tool_call') || type.includes('executing')) return 'tool'
      if (type.includes('observation')) return 'observation'
      if (type.includes('confirmation')) return 'confirmation'
      if (type.includes('generating')) return 'generation'
      if (type.includes('completed') || type.includes('failed') || type.includes('cancel')) return 'result'
      return 'thinking'
    },
    summarizeProvenance(value: unknown) {
      if (!value || typeof value !== 'object') return undefined
      const provenance = value as Record<string, unknown>
      const parts = [provenance.sourceName, provenance.url, provenance.resourceId,
        typeof provenance.toolCount === 'number' ? `${provenance.toolCount} 次工具调用` : undefined]
        .filter(item => typeof item === 'string' && item.trim()) as string[]
      return parts.length ? parts.join(' · ') : undefined
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
      this.timeline.push({ id, title: businessText(title, '处理当前工作'), detail: businessText(detail), tone, time, kind: 'thinking', technicalDetail: detail, ...extra })
      this.timeline.sort((left, right) => new Date(left.time).getTime() - new Date(right.time).getTime())
    },
    async refreshPlan(planId: string) {
      try {
        const plan = await api.getAssistantPlan(planId)
        if (this.plan?.id === planId) this.plan = plan
      } catch { /* the run poll remains authoritative if a refresh races an update */ }
    },
    async send(projectId: string, requestText = this.input) {
      const text = requestText.trim()
      if (!text || this.busy || this.stopping) return
      const requestId = crypto.randomUUID()
      this.activeRequestId = requestId
      this.interrupted = false
      this.interruptedAt = ''
      const hadCurrentRequest = Boolean(this.currentRequest)
      this.input = ''
      this.busy = true
      this.error = ''
      this.assistantMessage = ''
      this.plan = null
      this.run = null
      this.timeline = []
      this.progress = 2
      this.progressLabel = '正在接收你的需求'
      this.streaming = true
      this.streamLines = ['正在接收你的需求']
      try {
        await this.ensureSession(projectId)
        if (hadCurrentRequest) await this.loadHistory(false)
        this.currentRequest = text
        if (this.interrupted) return
        const response = await api.sendMessage(this.sessionId, text, this.pageContext, this.selection ?? undefined, this.executionMode, requestId)
        if (this.activeRequestId !== requestId) return
        this.assistantMessage = response.assistantMessage
        this.plan = response.plan
        this.context = response.context
        this.run = response.run ?? null
        await this.syncEvents()
        if (!response.plan || this.interrupted || this.stopping) {
          if (!response.plan) { this.interrupted = true; this.interruptedAt ||= new Date().toISOString() }
          this.streaming = false
          return
        }
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
        if (this.activeRequestId !== requestId || this.interrupted) return
        this.error = error instanceof Error ? error.message : '助手暂时没有完成请求'
        this.progressLabel = '本次任务未完成'
        this.progress = 100
        this.streaming = false
      } finally {
        if (this.activeRequestId === requestId) this.busy = false
      }
    },
    async confirm() {
      if (!this.plan || !this.context || this.busy) return
      const requestId = this.activeRequestId
      this.busy = true
      this.error = ''
      this.streaming = true
      this.progressLabel = '正在启动处理任务'
      try {
        const confirmed = await api.confirmPlan(this.plan, this.context)
        if (this.activeRequestId !== requestId || this.interrupted) return
        this.run = confirmed
        this.pushTimeline(`confirmed-${this.run.id}`, '已确认，开始处理', '原内容会保留', 'info')
        await this.watchRun()
      } catch (error) {
        if (this.activeRequestId !== requestId || this.interrupted) return
        this.error = error instanceof Error ? error.message : '计划没有执行'
        this.streaming = false
      } finally {
        if (this.activeRequestId === requestId) this.busy = false
      }
    },
    async watchRun() {
      if (!this.run) return
      const runId = this.run.id
      const requestId = this.activeRequestId
      for (let attempt = 0; attempt < 300; attempt += 1) {
        const updated = await api.getRun(runId)
        if (this.activeRequestId !== requestId || this.run?.id !== runId || this.interrupted) return
        this.run = updated
        await this.syncEvents()
        if (['SUCCEEDED', 'FAILED', 'CANCELED', 'ROLLED_BACK', 'WAITING_CONFIRMATION'].includes(this.run.status)) break
        await new Promise((resolve) => window.setTimeout(resolve, 1000))
      }
      await this.syncEvents()
      await this.refreshPlan(this.run.planId)
      if (this.run.status === 'WAITING_CONFIRMATION') {
        this.streaming = false
        this.progressLabel = this.run.resultSummary || '等待确认后继续执行'
        return
      }
      if (this.run.status === 'SUCCEEDED') {
        this.progress = 100
        this.progressLabel = this.run.resultSummary
        this.assistantMessage = this.run.resultSummary
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
      if (!this.canInterrupt || this.stopping) return
      this.stopping = true
      try {
        if (this.run) {
          this.run = await api.cancelAssistantRun(this.run.id)
          if (this.run.status !== 'CANCELED') return
        } else if (this.plan) {
          const result = await api.cancelAssistantPlan(this.plan.id)
          if (result.status !== 'CANCELED') return
          this.plan = await api.getAssistantPlan(this.plan.id)
        } else if (this.sessionId && this.activeRequestId) {
          await api.interruptAssistantRequest(this.sessionId, this.activeRequestId)
          if (this.run) this.run = await api.getRun(this.run.id)
          if (this.run && this.run.status !== 'CANCELED') return
        }
        this.interrupted = true
        this.interruptedAt ||= new Date().toISOString()
        if (this.run) this.interruptedRuns.push(this.run.id)
        this.streaming = false
        if (this.plan || this.run) this.busy = false
        this.error = ''
        this.progressLabel = this.run
          ? '已中断，后续步骤不会继续。已完成的修改会保留，正在提交的操作可能仍会完成。'
          : '已中断本次请求，未开始执行操作。可以继续发送新的要求。'
        this.pushTimeline(`canceled-${this.run?.id || this.activeRequestId || this.plan?.id}`, '已中断', this.progressLabel, 'warning')
        if (this.plan) await this.refreshPlan(this.plan.id)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '任务暂时无法停止'
      } finally {
        this.stopping = false
      }
    },
    async rollback() {
      if (!this.run || this.run.status !== 'SUCCEEDED') return
      this.run = await api.rollback(this.run.id)
      this.pushTimeline(`rollback-${this.run.id}`, '已撤销', '已恢复到执行前版本', 'success')
    },
  },
})
