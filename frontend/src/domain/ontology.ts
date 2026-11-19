import type { Entry, TacitState } from './tacit'

export type OntologyBundle = {
  ontology_turtle?: string
  model: Entry
  manifest: Entry
  counts: Record<string, number>
  digest: string
  quality?: { publication_ready: boolean; reasons: string[]; scenario_validation: string; business_outcome: string }
  database_name: string
}
export type OntologyTarget = { configured: boolean; target_id?: string; target_label?: string; reason?: string }
export type OntologyPublication = {
  id: string
  actor_id: string
  status: string
  model_digest: string
  target_id: string
  source_revision: number
  created_at: string
  updated_at: string
  attempt: number
  message?: string
  receipt?: Entry | null
}
export type PublicationPage = { items: OntologyPublication[]; target: OntologyTarget; next_before: string | null }
export type PublicationCommand = { expected_revision: number; model_digest: string; target_id: string; request_id: string }

export function ontologyBundle(value: unknown): OntologyBundle | null {
  if (!value || typeof value !== 'object') return null
  const bundle = value as Partial<OntologyBundle>
  return typeof bundle.digest === 'string' && bundle.database_name && bundle.model && bundle.manifest && bundle.counts
    ? bundle as OntologyBundle : null
}

export function buildBlockers(state: TacitState): string[] {
  const blockers: string[] = []
  if (state.spec.completion !== 'complete' || state.issues.length) blockers.push('完成业务台账并处理完整性问题')
  if (!state.spec.scope?.goal || !Array.isArray(state.spec.scope?.in_scope) || !state.spec.scope.in_scope.length)
    blockers.push('确认业务目标和建模范围')
  if (state.spec.gaps?.some(gap => gap.blocking !== false && gap.status !== 'resolved')) blockers.push('解决阻断缺口')
  if (state.artifacts.flows?.status !== 'approved') blockers.push('审核当前业务流程')
  if (state.artifacts.candidates?.status !== 'approved') blockers.push('审核当前本体候选')
  return blockers
}

export function publishableDigest(status: string | undefined, bundle: OntologyBundle | null, blockers: string[]) {
  return status === 'ready' && !blockers.length && bundle?.quality?.publication_ready === true ? bundle.digest : undefined
}

export function publicationAction(row: OntologyPublication, target: OntologyTarget | null, readyDigest?: string) {
  if (!target?.configured || !target.target_id || row.target_id !== target.target_id) return null
  if (row.status === 'UNCERTAIN' || row.status === 'PUBLISHING') return 'verify'
  if (row.model_digest === readyDigest && (row.status === 'ABSENT' || row.status === 'FAILED')) return 'retry'
  return null
}

export const publicationLabels: Record<string, string> = {
  VERIFIED: '目标模型回读已核验', FAILED: '发布失败', ABSENT: '目标未找到模型',
  UNCERTAIN: '目标状态待核验', PUBLISHING: '发布处理中',
}
