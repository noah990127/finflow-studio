type Activity = { time: string; eventType?: string }
type Run = { createdAt?: string; startedAt?: string; finishedAt?: string; status: string }
const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELED', 'ROLLED_BACK'])
const timestamp = (value?: string) => value ? Date.parse(value) : NaN

export function assistantElapsed(now: number, run: Run | null, timeline: Activity[], interruptedAt = '', waiting = false) {
  const requestIndex = timeline.findLastIndex(item => item.eventType === 'assistant.request.received')
  const current = requestIndex < 0 ? timeline : timeline.slice(requestIndex)
  const requestStart = timestamp(current.find(item => item.eventType === 'assistant.request.received')?.time)
  const runStart = timestamp(run?.createdAt || run?.startedAt)
  // The current run is authoritative; local UI notices and previous turns are not a clock origin.
  const started = Number.isFinite(runStart) ? runStart : requestStart
  if (!Number.isFinite(started)) return '刚刚开始'
  const validEnd = (value?: string) => {
    const time = timestamp(value)
    return Number.isFinite(time) && time >= started ? time : undefined
  }
  const waitingEnd = waiting ? validEnd(current.findLast(item =>
    ['agent.waiting_confirmation', 'assistant.confirmation.required'].includes(item.eventType ?? ''))?.time) : undefined
  const eventEnd = !run || terminal.has(run.status) ? validEnd(current.findLast(item =>
    ['agent.completed', 'agent.failed', 'agent.cancelled'].includes(item.eventType ?? ''))?.time) : undefined
  const end = validEnd(interruptedAt)
    ?? (run && terminal.has(run.status) ? validEnd(run.finishedAt) : undefined)
    ?? waitingEnd ?? eventEnd
  const seconds = Math.max(0, Math.floor(((end ?? now) - started) / 1000))
  const prefix = waitingEnd !== undefined ? '等待前已执行' : end !== undefined ? '耗时' : '已处理'
  if (seconds < 60) return `${prefix} ${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  return seconds % 60 ? `${prefix} ${minutes} 分 ${seconds % 60} 秒` : `${prefix} ${minutes} 分钟`
}
