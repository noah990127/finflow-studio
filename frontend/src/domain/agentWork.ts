import type { AgentWork, ArtifactRef, ArtifactReview, WorkspaceResource, WorkStage } from '../api/client'

export function primaryArtifacts(refs: ArtifactRef[]): ArtifactRef[] {
  return refs.filter(ref => !ref.role || ref.role === 'PRIMARY')
}

export function supportingArtifacts(refs: ArtifactRef[]): ArtifactRef[] {
  return refs.filter(ref => ref.role === 'SUPPORTING')
}

export function workStages(work: AgentWork): WorkStage[] {
  if (work.stages.length) return work.stages
  // Legacy tasks have no business plan. Never fabricate completed stages.
  return [{ id: 'legacy', title: '处理工作', status: work.status, summary: work.resultSummary, inputs: [], outputs: [] }]
}

export function workStatus(work: AgentWork): string {
  if (['FAILED', 'CANCELED', 'ROLLED_BACK', 'WAITING_CONFIRMATION'].includes(work.status)) return work.status
  if (work.stages.some(s => s.status === 'WAITING_INPUT')) return 'WAITING_INPUT'
  if (work.stages.some(s => s.status === 'STALE')) return 'STALE'
  if (work.stages.some(s => s.status === 'FAILED')) return 'FAILED'
  if (work.status === 'SUCCEEDED' && work.stages.some(s => s.status !== 'SUCCEEDED')) return 'INCOMPLETE'
  return work.status
}

export function resolveArtifact(ref: ArtifactRef, resources: WorkspaceResource[]) {
  const resource = resources.find(r => r.id === ref.resourceId)
  return { resource, stale: Boolean(resource && resource.currentVersion !== ref.version), missing: !resource }
}

export function isReviewed(ref: ArtifactRef, reviews: ArtifactReview[], resources: WorkspaceResource[]) {
  const { resource, stale } = resolveArtifact(ref, resources)
  return Boolean(resource && !stale && reviews.some(r => r.resourceId === ref.resourceId && r.version === ref.version))
}

export function feedbackContext(work: AgentWork, stage: WorkStage, refs: ArtifactRef[], text: string) {
  return `关于工作「${work.goal}」的「${stage.title}」：${text}\n工作编号：${work.id}\n产物版本：${refs.map(r => `${r.resourceId}@${r.version}`).join('、') || '文字结果'}\n请检查受影响的后续产物，保留旧版本并更新。`
}

export const workStatusLabels: Record<string, string> = {
  QUEUED: '等待处理', PENDING: '尚未开始', RUNNING: '进行中', SUCCEEDED: '执行完成',
  WAITING_INPUT: '需要你的输入', WAITING_CONFIRMATION: '等待授权', FAILED: '未完成',
  CANCELED: '已停止', ROLLED_BACK: '已撤回', STALE: '需要更新', INCOMPLETE: '仍有未完成工序',
  CANCEL_REQUESTED: '正在中止',
}

export function preferredStage(work: AgentWork, reviews: ArtifactReview[], resources: WorkspaceResource[]) {
  const stages = workStages(work)
  return stages.find(s => ['WAITING_INPUT', 'WAITING_CONFIRMATION', 'FAILED', 'RUNNING'].includes(s.status))
    ?? stages.find(s => s.outputs.some(r => !isReviewed(r, reviews, resources)))
    ?? stages.at(-1)
}

export function preferredExecutionStage(work: AgentWork) {
  const stages = workStages(work)
  return stages.find(s => ['WAITING_INPUT', 'WAITING_CONFIRMATION', 'FAILED', 'RUNNING', 'STALE'].includes(s.status))
    ?? stages.at(-1)
}

export function stageReviewLabel(stage: WorkStage, reviews: ArtifactReview[], resources: WorkspaceResource[]) {
  if (stage.status !== 'SUCCEEDED') return workStatusLabels[stage.status] ?? stage.status
  if (!stage.outputs.length) return '执行完成'
  return stage.outputs.every(r => isReviewed(r, reviews, resources)) ? '已检查' : '待检查'
}
