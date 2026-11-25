export function procedureLabel(state?: string): string {
  switch (state) {
    case 'PUBLISHED': return '工序已固化'
    case 'READY': return '实现已保存 · 待关联工序'
    case 'REPLAY_SUCCEEDED': return '固定工序执行成功'
    case 'NEEDS_REVIEW': return '尚未固化 · 需整理实现'
    default: return '尚未生成固定工序'
  }
}
