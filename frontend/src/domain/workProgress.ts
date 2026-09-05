type Progress = { type?: string; message?: string; toolName?: string; status?: string; error?: string; phase?: string }
const technical = /(?:\b(?:[a-z]+[_.])+(?:search|read|run|create|extract|delete|edit|open|list|query|parse|connect)\b|\b(?:workflow_id|resource_id|node_id|JSON|SSE|HTTP|Traceback|Exception|Service Unavailable|Unprocessable|tool_call|provenance)\b|localhost:\d+|127\.0\.0\.1|\{\s*"|[a-f0-9]{8}-[a-f0-9-]{27,}|调用工具|工具调用|加载 Skill|Agent runtime)/i

export function businessError(message: string): string {
  if (/503|Service Unavailable|模型.*不可用/i.test(message)) return '分析服务暂时不可用，本次处理没有完成。请稍后重试。'
  if (/422|Unprocessable/i.test(message)) return '这一步收到的内容不符合要求，请检查连接的资料和填写的要求。'
  if (/404|资料不存在|文件不存在|没有产生变量/i.test(message)) return '没有找到这一步需要的内容，请检查资料是否还在、前一步是否已完成。'
  if (/403|无权限|权限不足/i.test(message)) return '当前没有权限处理这些内容，请检查访问权限。'
  if (/timeout|timed out|超时/i.test(message)) return '这一步等待时间过长，暂时没有拿到结果。可以稍后重试。'
  return businessText(message, '这一步没有完成，请展开详情查看原因。')
}

export function businessText(message: string | undefined, fallback = '正在处理当前工作'): string {
  const text = (message ?? '').replace(/\s+/g, ' ').trim()
  if (!text || technical.test(text) || /\b(?:knowledge|workflow|dataset|resource|workspace|deliverable|project|folder|file|data)\\?\.[a-z_]+\b/i.test(text)) return fallback
  return text.replace(/\bSkill\b/gi, '处理方法').replace(/\bAgent\b/g, '助手').replace(/Observation/gi, '处理结果')
}

function action(tool: string): string {
  const name = tool.replace(/\\/g, '').toLowerCase()
  const [domain, operation] = name.split('.')
  const subject = ({ project: '项目', folder: '目录', resource: '资料', file: '文件', knowledge: '资料', dataset: '数据', data: '数据', datasource: '数据连接', workflow: '工作流', deliverable: '成果', workspace: '工作区' } as Record<string, string>)[domain ?? '']
  const verb = ({ search: '查找', list: '查看', read: '阅读', open: '打开', create: '创建', add: '添加', upload: '导入', import: '导入', edit: '修改', rename: '重命名', move: '移动', delete: '删除', run: '运行', save_version: '保存', query: '查询', extract: '提取', extract_table: '提取表格', parse: '读取', transform: '整理', export: '导出', connect: '连接', navigate: '切换' } as Record<string, string>)[operation ?? '']
  if (name.includes('web') && name.includes('search')) return '搜索公开资料'
  if (name.includes('fetch')) return '读取网页内容'
  return subject && verb ? operation === 'extract_table' ? '从资料中提取表格' : `${verb}${subject}` : '处理当前工作'
}

export function workProgress(event: Progress): { title: string; detail: string } {
  const type = (event.type ?? '').toLowerCase()
  const failed = Boolean(event.error) || ['failed', 'error'].includes((event.status ?? '').toLowerCase()) || type.includes('failed') || /工具没有完成|执行失败/.test(event.message ?? '')
  if (failed) return { title: '这一步未完成', detail: businessError(event.error || event.message || '') }
  if (type.includes('cancel')) return { title: '已停止', detail: '后续工作没有继续进行。' }
  if (type.includes('confirmation') || type.includes('review_required')) return { title: '需要你确认', detail: businessText(event.message, '这一步涉及修改内容，确认后才能继续。') }
  if (type.includes('retry')) return { title: '正在尝试解决问题', detail: '上一种方式没有完成，正在调整处理方式。' }
  if (type.includes('thinking')) return {
    title: event.phase === 'understanding' ? '我的理解' : event.phase === 'assessment' ? '我的判断' : '正在梳理',
    detail: businessText(event.message, '正在结合你的要求和已有内容确定处理方式。'),
  }
  if (type.includes('skill')) return { title: '准备处理方法', detail: '正在选择适合这项任务的处理方法。' }
  if (type.includes('tool_search')) return { title: '寻找可用的处理方式', detail: '正在查找完成当前工作所需的功能。' }
  if (type.includes('planning') || type.includes('plan_updated')) return { title: type.includes('updated') ? '调整工作安排' : '安排工作步骤', detail: businessText(event.message, '正在安排资料整理、分析和成果生成的先后顺序。') }
  if (type.includes('observation') || type.includes('tool_completed')) return { title: '检查处理结果', detail: businessText(event.message, '这一步已返回结果，正在检查是否满足后续工作的需要。') }
  if (type.includes('completed') || ['succeeded', 'completed'].includes((event.status ?? '').toLowerCase())) return { title: '处理完成', detail: businessText(event.message, '这一步已完成。') }
  if (type.includes('tool_call') || type.includes('executing') || type.includes('tool_started')) return { title: `正在${action(event.toolName ?? '')}`, detail: businessText(event.message, '正在处理，请稍候。') }
  if (type.includes('generating')) return { title: '整理最终成果', detail: businessText(event.message, '正在将分析结果整理成所需的成果。') }
  return { title: '正在处理', detail: businessText(event.message) }
}
