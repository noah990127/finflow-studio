import type { WorkflowNodeType, WorkspaceResource } from '../api/client'

export type WorkflowNodeSpec = {
  type: WorkflowNodeType
  label: string
  config: Record<string, unknown>
}

export function initialWorkflowNodeConfig(type: WorkflowNodeType): Record<string, unknown> {
  switch (type) {
    case 'LINK_INPUT': return { url: '', title: '' }
    case 'DATASET_INPUT': return { extractJobId: '' }
    case 'DATA_EXTRACT': return { connectionId: '', sql: 'select * from your_table', outputName: 'data.csv', fetchSize: 5000 }
    case 'DATA_TRANSFORM': return { requirements: '根据已连接的数据完成清洗、关联和计算，保留可核对的关键字段。', script: '', outputName: '数据加工结果.csv', inputAliases: {}, sheetNames: {}, scriptSummary: '', scriptMode: '', assumptions: [], qualityRules: [], sampleReport: null }
    case 'REF_SEARCH': return { query: '', limit: 10 }
    case 'AI_ANALYSIS': return { prompt: '结合已连接的资料进行分析，给出有证据支持的结论。' }
    case 'AGENT_TASK': return { instruction: '结合已连接的内容完成任务，区分事实、计算、推断和不确定项。', externalResearch: 'OFF', domainAllowlist: [], skills: [], maxToolCalls: 40, timeoutSeconds: 600, maxPoints: 10 }
    case 'DELIVERABLE': return { generationPrompt: '根据已连接的内容生成一份可以直接使用的成果。' }
    default: return { resourceId: '' }
  }
}

export function normalizeWorkflowNodeConfig(
  type: WorkflowNodeType,
  value: Record<string, unknown> | null | undefined,
): Record<string, unknown> {
  const config = structuredClone(value ?? {})
  if (type === 'AI_ANALYSIS') delete config.maxPoints
  if (type !== 'DELIVERABLE') return config

  const normalized: Record<string, unknown> = {
    generationPrompt: String(config.generationPrompt ?? ''),
  }
  if (typeof config.outputResourceId === 'string' && config.outputResourceId.trim()) {
    normalized.outputResourceId = config.outputResourceId
  }
  return normalized
}

export function workflowNodeSpecForResource(resource: WorkspaceResource): WorkflowNodeSpec {
  if (['DATABASE_CONNECTION', 'API_CONNECTION'].includes(resource.resourceType)) {
    return {
      type: 'DATA_EXTRACT',
      label: resource.name,
      config: {
        connectionId: resource.id,
        sql: resource.resourceType === 'API_CONNECTION' ? 'GET /' : 'select * from your_table',
        outputName: `${resource.name}.csv`,
        fetchSize: 5000,
      },
    }
  }
  if (resource.resourceType === 'DELIVERABLE') {
    return {
      type: 'DELIVERABLE',
      label: resource.name,
      config: {
        outputResourceId: resource.id,
        generationPrompt: `根据已连接的内容更新“${resource.name}”，保持适合当前内容的成果形式。`,
      },
    }
  }
  if (resource.resourceType === 'DATASET') {
    return { type: 'DATASET_INPUT', label: resource.name, config: { extractJobId: resource.id } }
  }
  return { type: 'FILE_INPUT', label: resource.name, config: { resourceId: resource.id } }
}
