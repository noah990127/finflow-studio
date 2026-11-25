type BrowserEvent = { payload: Record<string, unknown> }

export function browserStates(events: BrowserEvent[]): Map<string, string> {
  const states = new Map<string, string>()
  for (const event of events) {
    const output = event.payload.output
    if (!output || typeof output !== 'object') continue
    const value = output as Record<string, unknown>
    const nested = value.browserResult
    const result = nested && typeof nested === 'object' ? nested as Record<string, unknown> : value
    if (typeof result.session === 'string') states.set(result.session, String(result.status ?? ''))
  }
  return states
}
