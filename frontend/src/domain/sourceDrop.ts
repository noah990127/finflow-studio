const INTERNAL_DRAG_TYPES = new Set([
  'application/x-finflow-resource',
  'application/x-finflow-step',
])

export function sourceDropAccepts(types: readonly string[]) {
  if (types.some(type => INTERNAL_DRAG_TYPES.has(type))) return false
  return types.some(type => type === 'Files' || type === 'text/uri-list' || type === 'text/plain')
}
