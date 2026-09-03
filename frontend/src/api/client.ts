export type Project = { id: string; name: string; description: string; status: string; createdAt: string; updatedAt: string }
export type Selection = { type: string; resourceId: string; range: string[] }
export type DataConnection = { id: string; projectId: string; name: string; sourceType: string; jdbcUrl: string; username: string; secretRef: string; options: Record<string, string>; status: string; lastTestMessage: string; lastTestedAt?: string }
export type ConnectionPreview = { columns: string[]; rows: string[][]; rowCount: number; truncated: boolean; source: string }
export type DatabaseTable = { catalog: string; schema: string; name: string; description: string; tableType: string; previewQuery: string }
export type DatabaseSchema = { name: string; technicalName: string; tables: DatabaseTable[] }
export type DatabaseCatalog = { schemas: DatabaseSchema[]; tableCount: number; truncated: boolean }
export type ExtractJob = { id: string; name: string; connectionId: string; status: string; rowCount: number; byteCount: number; outputName: string; errorMessage: string; createdAt: string }
export type FileResource = { id: string; projectId: string; name: string; mediaType: string; status: string; currentVersion: number; sizeBytes: number; checksum: string; parseStatus: string; parseMessage: string; updatedAt: string }
export type KnowledgeRef = { id: string; resourceId: string; sourceName: string; text: string; location: Record<string, unknown>; score: number }
export type Deliverable = { id: string; name: string; format: string; currentVersion: number; status: string; sizeBytes: number; updatedAt: string }
export type PptSkill = { id: string; name: string; description: string; formats: string[]; theme: string; source: string }
export type SpreadsheetProfile = { file_name: string; format: string; has_macros: boolean; sheets: Array<{ name: string; rows: number; columns: number; formula_count: number; merged_range_count: number }>; warnings: string[] }
export type CsvPreview = { columns: string[]; rows: string[][]; rowOffset: number; nextCursor?: string; hasMore: boolean }
export type PreviewBlock = { type: 'heading' | 'text' | 'table'; text: string; rows: string[][] }
export type PreviewPage = { number: number; title: string; blocks: PreviewBlock[] }
export type DocumentPreview = { file_name: string; kind: 'presentation' | 'document' | 'text'; title: string; pages: PreviewPage[]; warnings: string[] }
export type PlanStep = { id: string; order: number; tool: string; mode: string; title: string; description: string; arguments: Record<string, unknown>; risk: 'READ_ONLY' | 'DRAFT_ONLY' | 'CREATE_VERSION' | 'DESTRUCTIVE_OR_EXTERNAL'; requiresConfirmation: boolean; status: string }
export type Plan = { id: string; sessionId: string; goal: string; summary: string; version: number; planHash: string; risk: string; status: string; affectedResources: string[]; steps: PlanStep[]; expiresAt: string }
export type ContextSnapshot = { id: string; projectId: string; page: string; selection?: Selection; allowedResourceIds: string[]; resourceVersions: Record<string, number>; contextHash: string; expiresAt: string }
export type Run = { id: string; sessionId: string; planId: string; status: string; currentStep: number; resultSummary: string; createdAt: string; startedAt?: string; finishedAt?: string; result: Record<string, unknown> }
export type MessageResponse = { sessionId: string; assistantMessage: string; context: ContextSnapshot; plan: Plan; run?: Run }
export type AssistantEvent = { eventId: string; eventSeq: number; sessionId: string; runId?: string; type: string; payload: Record<string, unknown>; createdAt: string }
export type WorkflowNodeType = 'FILE_INPUT' | 'LINK_INPUT' | 'DATASET_INPUT' | 'DATA_EXTRACT' | 'DATA_TRANSFORM' | 'SPREADSHEET_TRANSFORM' | 'REF_SEARCH' | 'AI_ANALYSIS' | 'REVIEW' | 'DELIVERABLE'
export type WorkflowNode = { id: string; type: WorkflowNodeType; name: string; x: number; y: number; config: Record<string, unknown> }
export type WorkflowEdge = { id: string; source: string; target: string }
export type WorkflowSchedule = { frequency: 'HOURLY' | 'DAILY' | 'WEEKLY' | 'MONTHLY'; time: string; dayOfWeek?: number; dayOfMonth?: number; timezone: string }
export type Workflow = { id: string; projectId: string; name: string; description: string; status: 'DRAFT' | 'READY'; currentVersion: number; nodes: WorkflowNode[]; edges: WorkflowEdge[]; executionMode: 'MANUAL' | 'SCHEDULED'; schedule?: WorkflowSchedule; nextRunAt?: string; createdAt: string; updatedAt: string }
export type WorkflowValidation = { valid: boolean; issues: Array<{ nodeId: string; message: string }>; executionOrder: string[] }
export type WorkflowNodeRun = { id: string; nodeId: string; nodeName: string; nodeType: WorkflowNodeType; stepOrder: number; status: string; input: Record<string, unknown>; output: Record<string, unknown>; errorMessage: string; startedAt?: string; finishedAt?: string }
export type WorkflowRun = { id: string; workflowId: string; projectId: string; workflowVersion: number; retryOfRunId?: string; triggerType: 'MANUAL' | 'SCHEDULED' | 'RETRY'; status: string; currentNodeId?: string; output: Record<string, unknown>; errorMessage: string; traceId: string; nodes: WorkflowNodeRun[]; createdAt: string; startedAt?: string; finishedAt?: string }
export type WorkflowProgressEvent = { sequence: number; runId: string; type: 'RUN_STATUS' | 'NODE_STARTED' | 'STEP_PROGRESS' | 'MODEL_STATUS' | 'MODEL_OUTPUT' | 'NODE_COMPLETED' | 'NODE_FAILED' | 'REVIEW_REQUIRED' | 'REVIEW_CONFIRMED' | 'RUN_COMPLETED'; nodeId: string; nodeName: string; status: string; progress: number; message: string; content: string; createdAt: string }
export type WorkflowSave = { name: string; description: string; nodes: WorkflowNode[]; edges: WorkflowEdge[]; executionMode?: 'MANUAL' | 'SCHEDULED'; schedule?: WorkflowSchedule; expectedVersion?: number }
export type WorkspaceRootKind = 'FILES' | 'DATABASES' | 'WEB_URLS' | 'APIS' | 'OUTPUTS'
export type WorkspaceFolder = { id: string; projectId: string; parentId?: string; rootKind: WorkspaceRootKind; name: string; sortOrder: number; createdAt: string; updatedAt: string }
export type WorkspaceResource = { id: string; projectId: string; resourceType: 'DATABASE_CONNECTION' | 'API_CONNECTION' | 'WEB_URL' | 'DATASET' | 'DATA_FILE' | 'OFFICE_FILE' | 'KNOWLEDGE_FILE' | 'DELIVERABLE'; group: 'DATA' | 'KNOWLEDGE' | 'OUTPUT'; name: string; mediaType: string; status: string; currentVersion: number; sizeBytes: number; inProjectWorkflow: boolean; folderId?: string; rootKind: WorkspaceRootKind; updatedAt: string; url?: string }
export type WebPreview = { title: string; url: string; siteName: string; summary: string; highlights: string[]; sections: Array<{ heading: string; paragraphs: string[] }>; previewMode: 'CURATED' | 'LIVE'; verifiedAt: string; fetchedAt: string }
export type WebEmbedStatus = { status: 'CHECKING' | 'ALLOWED' | 'BLOCKED' | 'UNKNOWN'; reason: string }
export type ProjectWorkspace = { project: Project; workflow: { id: string; name: string; status: string; currentVersion: number; updatedAt: string }; folders: WorkspaceFolder[]; resources: WorkspaceResource[] }
export type OfficeSession = { enabled: boolean; documentServerUrl: string; workingResourceId: string; message: string; config: Record<string, unknown> }
export type DataTransformSource = { sourceKind: 'FILE' | 'EXTRACT' | 'CONNECTION'; resourceId: string; alias: string; name: string; query?: string; sheetName?: string }
export type DataTransformScript = { script: string; summary: string; mode: string; assumptions: string[]; quality_rules: string[]; warnings: string[] }
export type DataTransformSample = { valid: boolean; sampleRowCount: number; columns: Array<{ name: string; dataType: string }>; rows: Array<Record<string, unknown>>; nullCounts: Record<string, number>; checks: string[]; warnings: string[] }
export type CitationLocation = Record<string, string | number | boolean | null>
export type CitationSource = { id: string; resource_id: string; version: number; source_name: string; text: string; location: CitationLocation; content_hash: string; formatted: string }
export type FinancialReportChart = { type: 'bar' | 'line' | 'pie'; title: string; categories: string[]; series: Array<{ name: string; values: number[] }>; source_ref: string }
export type FinancialReportSection = { heading: string; paragraphs: string[]; bullets: string[]; refs?: CitationSource[]; chart?: FinancialReportChart }
export type FinancialReportSpec = { schema_version: number; renderer: string; title: string; subtitle: string; theme: string; sections: FinancialReportSection[]; references: Array<CitationSource | string> }
export type SystemStatus = { java: Record<string, unknown>; pythonWorker: Record<string, unknown>; llm: Record<string, unknown>; reportEngine: Record<string, unknown> }

async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (!(options.body instanceof FormData) && options.body !== undefined) headers.set('Content-Type', 'application/json')
  const response = await fetch(url, { ...options, headers })
  if (!response.ok) {
    const raw = await response.text()
    const body = (() => { try { return JSON.parse(raw) as { message?: string; detail?: string } } catch { return {} } })()
    throw new Error(body.message ?? body.detail ?? `请求没有完成（HTTP ${response.status}）`)
  }
  return response.status === 204 ? (undefined as T) : response.json() as Promise<T>
}

export const api = {
  getSystemStatus: () => request<SystemStatus>('/api/system/status'),
  listProjects: () => request<Project[]>('/api/projects'),
  createProject: (name: string, description = '') => request<Project>('/api/projects', { method: 'POST', body: JSON.stringify({ name, description }) }),
  updateProject: (id: string, name: string, description = '') => request<Project>(`/api/projects/${id}`, { method: 'PUT', body: JSON.stringify({ name, description }) }),
  deleteProject: (id: string) => request<void>(`/api/projects/${id}`, { method: 'DELETE' }),
  listConnections: (projectId: string) => request<DataConnection[]>(`/api/projects/${projectId}/data-connections`),
  createConnection: (body: Record<string, unknown>) => request<DataConnection>('/api/data-connections', { method: 'POST', body: JSON.stringify(body) }),
  updateConnection: (id: string, body: Record<string, unknown>) => request<DataConnection>(`/api/data-connections/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  testConnection: (id: string) => request<{ success: boolean; databaseProduct: string; databaseVersion: string; latencyMs: number; message: string }>(`/api/data-connections/${id}/test`, { method: 'POST' }),
  previewConnection: (id: string, query: string, limit = 100) => request<ConnectionPreview>(`/api/data-connections/${id}/preview`, { method: 'POST', body: JSON.stringify({ query, limit }) }),
  getConnectionCatalog: (id: string) => request<DatabaseCatalog>(`/api/data-connections/${id}/catalog`),
  deleteConnection: (id: string) => request<void>(`/api/data-connections/${id}`, { method: 'DELETE' }),
  listExtracts: (projectId: string) => request<ExtractJob[]>(`/api/projects/${projectId}/extract-jobs`),
  createExtract: (body: Record<string, unknown>) => request<ExtractJob>('/api/extract-jobs', { method: 'POST', body: JSON.stringify(body) }),
  cancelExtract: (id: string) => request<ExtractJob>(`/api/extract-jobs/${id}/cancel`, { method: 'POST' }),
  deleteExtract: (id: string) => request<void>(`/api/extract-jobs/${id}`, { method: 'DELETE' }),
  previewExtract: (id: string, cursor?: string) => request<CsvPreview>(`/api/extract-jobs/${id}/preview?limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`),
  listFiles: (projectId: string) => request<FileResource[]>(`/api/projects/${projectId}/files`),
  deleteFile: (id: string) => request<void>(`/api/files/${id}`, { method: 'DELETE' }),
  uploadFile: (projectId: string, file: File, resourceId?: string) => { const body = new FormData(); body.append('file', file); if (resourceId) body.append('resourceId', resourceId); return request<FileResource>(`/api/projects/${projectId}/files`, { method: 'POST', body }) },
  searchRefs: (projectId: string, query: string) => request<KnowledgeRef[]>(`/api/projects/${projectId}/refs/search?query=${encodeURIComponent(query)}`),
  profileSpreadsheet: (id: string) => request<SpreadsheetProfile>(`/api/files/${id}/spreadsheet/profile`),
  transformSpreadsheet: (id: string, body: Record<string, unknown>) => request<FileResource>(`/api/files/${id}/spreadsheet/transform`, { method: 'POST', body: JSON.stringify(body) }),
  generateDataTransformScript: (projectId: string, requirements: string, inputs: DataTransformSource[]) => request<DataTransformScript>(`/api/projects/${projectId}/data-transforms/generate-script`, { method: 'POST', body: JSON.stringify({ requirements, inputs }) }),
  sampleDataTransform: (projectId: string, script: string, inputs: DataTransformSource[]) => request<DataTransformSample>(`/api/projects/${projectId}/data-transforms/sample`, { method: 'POST', body: JSON.stringify({ script, inputs }) }),
  previewFileCsv: (id: string, cursor?: string) => request<CsvPreview>(`/api/files/${id}/csv-preview?limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`),
  previewFile: (id: string) => request<DocumentPreview>(`/api/files/${id}/preview`),
  listDeliverables: (projectId: string) => request<Deliverable[]>(`/api/projects/${projectId}/deliverables`),
  deleteDeliverable: (id: string) => request<void>(`/api/deliverables/${id}`, { method: 'DELETE' }),
  createDeliverable: (body: Record<string, unknown>) => request<Deliverable>('/api/deliverables', { method: 'POST', body: JSON.stringify(body) }),
  listPptSkills: () => request<PptSkill[]>('/api/ppt-skills'),
  previewDeliverable: (id: string) => request<DocumentPreview>(`/api/deliverables/${id}/preview`),
  getFinancialReport: (id: string) => request<FinancialReportSpec>(`/api/deliverables/${id}/report-spec`),
  getDeliverableCitations: (id: string, version?: number) => request<CitationSource[]>(`/api/deliverables/${id}/citations${version ? `?version=${version}` : ''}`),
  createSession: (projectId: string) => request<{ id: string }>(`/api/projects/${projectId}/assistant/sessions`, { method: 'POST', body: JSON.stringify({ title: '项目工作助手' }) }),
  sendMessage: (sessionId: string, text: string, page = 'project-home', selection?: Selection) => request<MessageResponse>(`/api/assistant/sessions/${sessionId}/messages`, { method: 'POST', body: JSON.stringify({ text, page, route: window.location.pathname, selection, clientContextVersion: 1 }) }),
  confirmPlan: (plan: Plan, context: ContextSnapshot) => request<Run>(`/api/assistant/plans/${plan.id}/confirm`, { method: 'POST', body: JSON.stringify({ planVersion: plan.version, planHash: plan.planHash, idempotencyKey: crypto.randomUUID(), expectedResourceVersions: context.resourceVersions }) }),
  getRun: (runId: string) => request<Run>(`/api/assistant/runs/${runId}`),
  rollback: (runId: string) => request<Run>(`/api/assistant/runs/${runId}/rollback`, { method: 'POST' }),
  getProjectWorkspace: (projectId: string) => request<ProjectWorkspace>(`/api/projects/${projectId}/workspace`),
  getWebPreview: (projectId: string, resourceId: string, refresh = false) => request<WebPreview>(`/api/projects/${projectId}/workspace/web-preview/${resourceId}${refresh ? '?refresh=true' : ''}`),
  getWebEmbedStatus: (projectId: string, resourceId: string) => request<WebEmbedStatus>(`/api/projects/${projectId}/workspace/web-embed-status/${resourceId}?studioOrigin=${encodeURIComponent(window.location.origin)}`),
  createWorkspaceFolder: (projectId: string, body: { name: string; rootKind: WorkspaceRootKind; parentId?: string }) => request<WorkspaceFolder>(`/api/projects/${projectId}/workspace/folders`, { method: 'POST', body: JSON.stringify(body) }),
  updateWorkspaceFolder: (projectId: string, folderId: string, body: { name: string; rootKind: WorkspaceRootKind; parentId?: string }) => request<WorkspaceFolder>(`/api/projects/${projectId}/workspace/folders/${folderId}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteWorkspaceFolder: (projectId: string, folderId: string) => request<void>(`/api/projects/${projectId}/workspace/folders/${folderId}`, { method: 'DELETE' }),
  moveWorkspaceResource: (projectId: string, resource: WorkspaceResource, folderId?: string) => request<void>(`/api/projects/${projectId}/workspace/resources/${resource.resourceType}/${resource.id}/folder`, { method: 'PUT', body: JSON.stringify({ folderId: folderId || null }) }),
  getProjectWorkflow: (projectId: string) => request<Workflow>(`/api/projects/${projectId}/workflow`),
  createOfficeSession: (kind: 'files' | 'extract-jobs' | 'deliverables', resourceId: string, mode: 'view' | 'edit' = 'edit') => request<OfficeSession>(`/api/office/${kind}/${resourceId}/session?mode=${mode}`, { method: 'POST' }),
  saveProjectWorkflow: (projectId: string, body: WorkflowSave) => request<Workflow>(`/api/projects/${projectId}/workflow`, { method: 'PUT', body: JSON.stringify(body) }),
  validateProjectWorkflow: (projectId: string, body: WorkflowSave) => request<WorkflowValidation>(`/api/projects/${projectId}/workflow/validate`, { method: 'POST', body: JSON.stringify(body) }),
  listWorkflows: (projectId: string) => request<Workflow[]>(`/api/projects/${projectId}/workflows`),
  createWorkflow: (projectId: string, body: WorkflowSave) => request<Workflow>(`/api/projects/${projectId}/workflows`, { method: 'POST', body: JSON.stringify(body) }),
  updateWorkflow: (id: string, body: WorkflowSave) => request<Workflow>(`/api/workflows/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  validateWorkflow: (projectId: string, body: WorkflowSave) => request<WorkflowValidation>(`/api/projects/${projectId}/workflows/validate`, { method: 'POST', body: JSON.stringify(body) }),
  listWorkflowRuns: (id: string) => request<WorkflowRun[]>(`/api/workflows/${id}/runs`),
  startWorkflow: (id: string) => request<WorkflowRun>(`/api/workflows/${id}/runs`, { method: 'POST' }),
  getWorkflowRun: (id: string) => request<WorkflowRun>(`/api/workflow-runs/${id}`),
  cancelWorkflowRun: (id: string) => request<WorkflowRun>(`/api/workflow-runs/${id}/cancel`, { method: 'POST' }),
  retryWorkflowRun: (id: string) => request<WorkflowRun>(`/api/workflow-runs/${id}/retry`, { method: 'POST' }),
  confirmWorkflowReview: (id: string, comment: string, adjustedContent: string) => request<WorkflowRun>(`/api/workflow-runs/${id}/review/confirm`, { method: 'POST', body: JSON.stringify({ comment, adjustedContent }) }),
  rejectWorkflowReview: (id: string, comment: string) => request<WorkflowRun>(`/api/workflow-runs/${id}/review/reject`, { method: 'POST', body: JSON.stringify({ comment, adjustedContent: '' }) }),
}

export const downloadUrl = (kind: 'extract-jobs' | 'files' | 'deliverables', id: string) => `/api/${kind}/${id}/download`

export function downloadFile(kind: 'extract-jobs' | 'files' | 'deliverables', id: string, fallbackName: string) {
  const fileName = fallbackName.trim() || 'download'
  const anchor = document.createElement('a')
  anchor.href = downloadUrl(kind, id)
  anchor.download = fileName
  anchor.target = '_blank'
  anchor.rel = 'noopener'
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  return fileName
}
export const inlineContentUrl = (id: string) => `/api/files/${id}/content`
export const deliverableContentUrl = (id: string) => `/api/deliverables/${id}/content`
export const renderedOfficePreviewUrl = (kind: 'files' | 'deliverables', id: string, version: number) => `/api/${kind}/${id}/rendered-preview?version=${version}&renderer=pages`
