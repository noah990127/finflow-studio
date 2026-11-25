export function browserInteraction(kind?: string) {
  switch (kind) {
    case 'LOGIN': return { label: '我已登录，继续', action: 'resume' as const }
    case 'APPROVAL': return { label: '确认提交', action: 'approve' as const }
    case 'VERIFICATION': return { label: '结果正确，完成', action: 'resume' as const }
    default: return { label: '我已完成，继续', action: 'resume' as const }
  }
}
