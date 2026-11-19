import type { AgentWork, FixedWorkState } from '../api/client'

export function withFixedExecution(work: AgentWork, state: FixedWorkState | null): AgentWork {
  const run = state?.runs[0]
  if (!run || new Date(run.createdAt).getTime() < new Date(work.createdAt).getTime()) return work
  return { ...work, status: run.status, resultSummary: run.error,
    stages: run.businessStages ?? run.stages.map(s => ({ id: s.id, title: s.title, status: s.status, summary: s.error,
      inputs: s.inputs, outputs: s.output ? [s.output] : [] })) }
}
