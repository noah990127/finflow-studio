import type { AgentWork } from '../api/client'

export function internalResourceIds(recorded: string[] = [], works: AgentWork[] = []): Set<string> {
  const internal = new Set(recorded)
  const visible = new Set<string>()
  for (const work of works) for (const stage of work.stages) {
    for (const input of stage.inputs) visible.add(input.resourceId)
    for (const output of stage.outputs) {
      if (output.role === 'INTERNAL') internal.add(output.resourceId)
      else visible.add(output.resourceId)
    }
  }
  for (const id of visible) internal.delete(id)
  return internal
}
