<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { addEdge, MarkerType, useVueFlow, VueFlow, type Connection, type Edge, type Node } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { Activity, BarChart3, BookOpen, Braces, CalendarClock, CheckCircle2, ChevronRight, CircleStop, CircleUserRound, Clock3, Code2, Combine, Database, File, FileOutput, FileSpreadsheet, FileText, FileUp, History, Network, Play, Plus, RotateCcw, Save, Search, Sparkles, Trash2, X } from 'lucide-vue-next'
import WorkflowNode from '../components/WorkflowNode.vue'
import { api, type DataConnection, type DataTransformSample, type DataTransformSource, type FileResource, type PptSkill, type Project, type Workflow, type WorkflowNodeType, type WorkflowProgressEvent, type WorkflowRun, type WorkflowSave, type WorkspaceResource } from '../api/client'
import { initialWorkflowNodeConfig, normalizeWorkflowNodeConfig, workflowNodeSpecForResource } from '../domain/workflowNodeConfig'

const props = defineProps<{ project: Project | null; resources: WorkspaceResource[]; workflowId?: string }>()
const emit = defineEmits<{ resourcesChanged: []; workflowChanged: [workflow: Workflow]; openDeliverable: [deliverableId: string]; openResource: [resourceId: string] }>()
const files = ref<FileResource[]>([]), connections = ref<DataConnection[]>([]), runs = ref<WorkflowRun[]>([])
const pptSkills = ref<PptSkill[]>([])
const activeId = ref(''), currentVersion = ref(0), name = ref(''), description = ref(''), nodes = ref<Node[]>([]), edges = ref<Edge[]>([]), selectedNodeId = ref(''), selectedEdgeId = ref('')
const executionMode = ref<'MANUAL' | 'SCHEDULED'>('MANUAL'), frequency = ref<'HOURLY' | 'DAILY' | 'WEEKLY' | 'MONTHLY'>('DAILY'), scheduleTime = ref('09:00'), dayOfWeek = ref(1), dayOfMonth = ref(1), timezone = ref('Asia/Shanghai'), nextRunAt = ref('')
const busy = ref(false), message = ref(''), validation = ref<{ valid: boolean; issues: Array<{ nodeId: string; message: string }> } | null>(null), currentRun = ref<WorkflowRun | null>(null)
const transformBusy = ref(false)
const reviewBusy = ref(false), reviewComment = ref(''), reviewContent = ref(''), reviewStateKey = ref('')
const panel = ref<'none' | 'add' | 'schedule' | 'progress' | 'history'>('none'), addSearch = ref(''), historyRunId = ref('')
const liveEvents = ref<WorkflowProgressEvent[]>([]), liveContent = ref(''), liveContentNode = ref(''), liveConnected = ref(false), liveRunId = ref('')
const { screenToFlowCoordinate, fitView } = useVueFlow()
let pollTimer: number | undefined
let eventSource: EventSource | undefined

const catalog: Array<{ type: WorkflowNodeType; label: string; detail: string; icon: typeof FileUp }> = [
  { type: 'DATA_EXTRACT', label: '提取数据', detail: '从数据库或接口取得数据', icon: Database },
  { type: 'DATA_TRANSFORM', label: '加工数据', detail: '关联、清洗、计算结构化数据', icon: Combine },
  { type: 'REF_SEARCH', label: '查找参考', detail: '从项目资料中查找内容', icon: BookOpen },
  { type: 'AGENT_TASK', label: '开放任务', detail: '让 Agent 自主组合资料、数据与工具', icon: Sparkles },
  { type: 'AI_ANALYSIS', label: '智能分析', detail: '按要求分析已连接资料', icon: Sparkles },
  { type: 'DELIVERABLE', label: '生成成果', detail: '生成演示、文档或图表', icon: FileOutput },
]
const removedNodeTypes = new Set<WorkflowNodeType>(['SPREADSHEET_TRANSFORM', 'REVIEW'])
const deliverableFormats = [
  { value: 'PPTX', label: 'PPT', detail: '演示文稿' },
  { value: 'HTML_SLIDES', label: '网页演示', detail: 'HTML + JS' },
  { value: 'DOCX', label: 'Word', detail: '正式文档' },
  { value: 'PDF', label: 'PDF', detail: '阅读报告' },
  { value: 'FINANCIAL_REPORT', label: '财务报告', detail: '交互分析' },
  { value: 'MERMAID', label: 'Mermaid', detail: '结构图' },
  { value: 'EXCALIDRAW', label: 'Excalidraw', detail: '手绘图' },
]
const selectedNode = computed(() => nodes.value.find(node => node.id === selectedNodeId.value))
const selectedEdge = computed(() => edges.value.find(edge => edge.id === selectedEdgeId.value))
const selectedEdgeSource = computed(() => nodes.value.find(node => node.id === selectedEdge.value?.source)?.data.label ?? '起点')
const selectedEdgeTarget = computed(() => nodes.value.find(node => node.id === selectedEdge.value?.target)?.data.label ?? '终点')
const latestRun = computed(() => currentRun.value ?? runs.value[0] ?? null)
const historyRun = computed(() => runs.value.find(run => run.id === historyRunId.value) ?? latestRun.value)
const issuesByNode = computed(() => new Set(validation.value?.issues.map(issue => issue.nodeId).filter(Boolean) ?? []))
const runByNode = computed(() => new Map(latestRun.value?.nodes.map(node => [node.nodeId, node.status]) ?? []))
const runActive = computed(() => !!latestRun.value && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'WAITING_REVIEW'].includes(latestRun.value.status))
const runExecuting = computed(() => !!latestRun.value && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(latestRun.value.status))
const reviewStep = computed(() => latestRun.value?.nodes.find(node => node.status === 'WAITING_REVIEW') ?? null)
const reviewItems = computed<Record<string, unknown>[]>(() => Array.isArray(reviewStep.value?.output.reviewItems) ? reviewStep.value!.output.reviewItems as Record<string, unknown>[] : [])
const reviewEditable = computed(() => reviewStep.value?.output.editable !== false)
const reviewTitle = computed(() => String(reviewStep.value?.output.reviewTitle ?? reviewStep.value?.nodeName ?? '复核中间结果'))
const reviewInstructions = computed(() => String(reviewStep.value?.output.instructions ?? '请核对中间结果后再继续。'))
const liveProgress = computed(() => liveEvents.value.at(-1)?.progress ?? (latestRun.value?.status === 'SUCCEEDED' ? 100 : 0))
const liveMessage = computed(() => [...liveEvents.value].reverse().find(event => event.message)?.message ?? statusText(latestRun.value?.status ?? 'QUEUED'))
const timelineEvents = computed(() => {
  const reversed = [...liveEvents.value].reverse().filter(event => event.type !== 'MODEL_OUTPUT')
  return reversed.filter((event, index) => event.type !== 'MODEL_STATUS' || index === reversed.findIndex(item => item.type === 'MODEL_STATUS' && item.nodeId === event.nodeId))
})
const filteredResources = computed(() => props.resources.filter(item => item.name.toLowerCase().includes(addSearch.value.trim().toLowerCase())))
const filteredCatalog = computed(() => catalog.filter(item => `${item.label}${item.detail}`.includes(addSearch.value.trim())))
const selectedIncoming = computed(() => {
  if (!selectedNode.value) return []
  const sourceIds = edges.value.filter(edge => edge.target === selectedNode.value?.id).map(edge => edge.source)
  return sourceIds.map(id => nodes.value.find(node => node.id === id)).filter((node): node is Node => !!node)
})
const selectedSampleReport = computed<DataTransformSample | null>(() => {
  const report = selectedNode.value?.data.config.sampleReport
  return report && typeof report === 'object' ? report as DataTransformSample : null
})
const selectedPresentationSkills = computed(() => {
  const format = String(selectedNode.value?.data.config.format ?? '').toLowerCase()
  return pptSkills.value.filter(skill => skill.formats.map(item => item.toLowerCase()).includes(format))
})
function makeNode(type: WorkflowNodeType, x: number, y: number, label?: string, config?: Record<string, unknown>): Node {
  const count = nodes.value.filter(node => node.data.nodeType === type).length + 1
  const base = catalog.find(item => item.type === type)?.label ?? ({ FILE_INPUT: '使用文件', LINK_INPUT: '使用链接', DATASET_INPUT: '使用数据' } as Record<string, string>)[type] ?? '步骤'
  return { id: `${type.toLowerCase()}_${crypto.randomUUID().slice(0, 8)}`, type: 'business', position: { x, y }, data: { label: label ?? `${base} ${count}`, nodeType: type, config: config ?? initialWorkflowNodeConfig(type) } }
}
function resourceNode(resource: WorkspaceResource, x: number, y: number): Node {
  const spec = workflowNodeSpecForResource(resource)
  return makeNode(spec.type, x, y, spec.label, spec.config)
}
function openWorkflow(item: Workflow) {
  activeId.value = item.id; currentVersion.value = item.currentVersion; name.value = item.name; description.value = item.description; validation.value = null; currentRun.value = null
  executionMode.value = item.executionMode ?? 'MANUAL'; frequency.value = item.schedule?.frequency ?? 'DAILY'; scheduleTime.value = item.schedule?.time ?? '09:00'; dayOfWeek.value = item.schedule?.dayOfWeek ?? 1; dayOfMonth.value = item.schedule?.dayOfMonth ?? 1; timezone.value = item.schedule?.timezone ?? 'Asia/Shanghai'; nextRunAt.value = item.nextRunAt ?? ''
  const visibleNodes = item.nodes.filter(node => !removedNodeTypes.has(node.type))
  const visibleNodeIds = new Set(visibleNodes.map(node => node.id))
  nodes.value = visibleNodes.map(node => {
    const config = normalizeWorkflowNodeConfig(node.type, node.config)
    return { id: node.id, type: 'business', position: { x: node.x, y: node.y }, data: { label: node.name, nodeType: node.type, config } }
  })
  edges.value = item.edges.filter(edge => visibleNodeIds.has(edge.source) && visibleNodeIds.has(edge.target)).map(decorateEdge); selectedNodeId.value = ''; loadRuns(item.id)
}
function payload(): WorkflowSave {
  const schedule = executionMode.value === 'SCHEDULED' ? { frequency: frequency.value, time: scheduleTime.value, dayOfWeek: dayOfWeek.value, dayOfMonth: dayOfMonth.value, timezone: timezone.value } : undefined
  return { name: name.value.trim() || '主工作流', description: description.value.trim(), executionMode: executionMode.value, schedule, nodes: nodes.value.map(node => ({ id: node.id, type: node.data.nodeType, name: node.data.label, x: node.position.x, y: node.position.y, config: node.data.config ?? {} })), edges: edges.value.map(edge => ({ id: edge.id, source: edge.source, target: edge.target })), expectedVersion: currentVersion.value || undefined }
}
async function load() { if (!props.project) return; panel.value = 'none'; message.value = ''; try { const [workflow, loadedFiles, loadedConnections, loadedPptSkills] = await Promise.all([props.workflowId ? api.getWorkflow(props.workflowId) : api.getProjectWorkflow(props.project.id), api.listFiles(props.project.id), api.listConnections(props.project.id), api.listPptSkills()]); files.value = loadedFiles; connections.value = loadedConnections; pptSkills.value = loadedPptSkills; openWorkflow(workflow); emit('workflowChanged', workflow) } catch (error) { message.value = error instanceof Error ? error.message : '工作流没有加载' } }
async function save(silent = false) { if (!props.project) return null; busy.value = true; try { const saved = activeId.value ? await api.updateWorkflow(activeId.value, payload()) : await api.createWorkflow(props.project.id, payload()); activeId.value = saved.id; currentVersion.value = saved.currentVersion; nextRunAt.value = saved.nextRunAt ?? ''; emit('workflowChanged', saved); if (!silent) message.value = saved.status === 'READY' ? `已保存第 ${saved.currentVersion} 版` : `草稿已保存为第 ${saved.currentVersion} 版，还有内容需要补充`; return saved } catch (error) { message.value = error instanceof Error ? error.message : '工作流没有保存'; return null } finally { busy.value = false } }
async function saveNodeSettings() { if (!selectedNode.value) return; const saved = await save(true); if (saved) message.value = `节点设置已保存到第 ${saved.currentVersion} 版` }
function selectDeliverableFormat(format: string) {
  if (!selectedNode.value) return
  selectedNode.value.data.config.format = format
  if (format === 'HTML_SLIDES') selectedNode.value.data.config.pptSkill = 'frontend-slides'
  else if (format === 'PPTX' && selectedNode.value.data.config.pptSkill === 'frontend-slides') selectedNode.value.data.config.pptSkill = 'guizang-huawei-style-c'
  else if (!['PPTX', 'HTML_SLIDES'].includes(format)) selectedNode.value.data.config.pptSkill = ''
}
function inputAlias(sourceId: string, index: number) {
  if (!selectedNode.value) return `data_${index + 1}`
  const aliases = selectedNode.value.data.config.inputAliases as Record<string, string> | undefined
  return aliases?.[sourceId] || `data_${index + 1}`
}
function setInputAlias(sourceId: string, value: string) {
  if (!selectedNode.value) return
  const aliases = { ...((selectedNode.value.data.config.inputAliases as Record<string, string> | undefined) ?? {}) }
  aliases[sourceId] = value
  selectedNode.value.data.config.inputAliases = aliases
}
function transformSources(): DataTransformSource[] {
  return selectedIncoming.value.map((source, index) => {
    const config = source.data.config as Record<string, unknown>
    const common = { alias: inputAlias(source.id, index), name: String(source.data.label) }
    if (source.data.nodeType === 'FILE_INPUT') return { ...common, sourceKind: 'FILE', resourceId: String(config.resourceId ?? ''), sheetName: '' }
    if (source.data.nodeType === 'DATASET_INPUT') return { ...common, sourceKind: 'EXTRACT', resourceId: String(config.extractJobId ?? '') }
    if (source.data.nodeType === 'DATA_EXTRACT') return { ...common, sourceKind: 'CONNECTION', resourceId: String(config.connectionId ?? ''), query: String(config.sql ?? '') }
    throw new Error(`“${source.data.label}”还不能作为脚本生成时的数据输入，请连接文件、已采集数据或提取数据步骤`)
  })
}
async function generateTransformScript() {
  if (!props.project || !selectedNode.value) return
  transformBusy.value = true
  try {
    const result = await api.generateDataTransformScript(props.project.id, String(selectedNode.value.data.config.requirements ?? ''), transformSources())
    selectedNode.value.data.config.script = result.script
    selectedNode.value.data.config.scriptSummary = result.summary
    selectedNode.value.data.config.scriptMode = result.mode
    selectedNode.value.data.config.assumptions = result.assumptions
    selectedNode.value.data.config.qualityRules = result.quality_rules
    selectedNode.value.data.config.sampleReport = null
    message.value = '加工脚本已生成，请核对后进行样本试跑'
  } catch (error) { message.value = error instanceof Error ? error.message : '加工脚本没有生成' }
  finally { transformBusy.value = false }
}
async function sampleTransformScript() {
  if (!props.project || !selectedNode.value) return
  transformBusy.value = true
  try {
    const sources = transformSources()
    if (sources.some(source => source.sourceKind === 'CONNECTION')) throw new Error('数据库或接口数据会在正式运行时先采集并自动试跑；如需现在试跑，请连接已经采集完成的 CSV')
    const result = await api.sampleDataTransform(props.project.id, String(selectedNode.value.data.config.script ?? ''), sources)
    selectedNode.value.data.config.sampleReport = result
    message.value = `样本试跑通过，共返回 ${result.sampleRowCount} 行`
  } catch (error) { message.value = error instanceof Error ? error.message : '样本试跑没有完成' }
  finally { transformBusy.value = false }
}
async function check() { if (!props.project) return null; try { validation.value = await api.validateProjectWorkflow(props.project.id, payload()); message.value = validation.value.valid ? '检查通过，所有步骤可以按顺序运行' : `发现 ${validation.value.issues.length} 处需要补充的内容`; return validation.value } catch (error) { message.value = error instanceof Error ? error.message : '检查没有完成'; return null } }
async function run() { const result = await check(); if (!result?.valid) return; const saved = await save(true); if (!saved) return; try { currentRun.value = await api.startWorkflow(saved.id); message.value = '工作流已经开始'; panel.value = 'progress'; connectProgress(currentRun.value.id, true); await loadRuns(); poll(currentRun.value.id) } catch (error) { message.value = error instanceof Error ? error.message : '工作流没有开始' } }
async function loadRuns(id = activeId.value) { if (!id) return; try { runs.value = await api.listWorkflowRuns(id); if (!historyRunId.value) historyRunId.value = runs.value[0]?.id ?? ''; const newest = runs.value[0]; if (!currentRun.value && newest && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'WAITING_REVIEW'].includes(newest.status)) { currentRun.value = newest; syncReviewState(); if (newest.status !== 'WAITING_REVIEW') { connectProgress(newest.id, true); poll(newest.id) } } } catch { runs.value = [] } }
async function poll(id: string) { window.clearTimeout(pollTimer); try { currentRun.value = await api.getWorkflowRun(id); syncReviewState(); if (['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(currentRun.value.status)) pollTimer = window.setTimeout(() => poll(id), 800); else if (currentRun.value.status === 'WAITING_REVIEW') { panel.value = 'progress'; eventSource?.close(); liveConnected.value = false; message.value = '工作流已暂停，等待你复核中间结果' } else { await loadRuns(); if (currentRun.value.status === 'SUCCEEDED') emit('resourcesChanged'); message.value = currentRun.value.status === 'SUCCEEDED' ? '工作流运行完成，成果已加入项目' : currentRun.value.errorMessage || '工作流已结束' } } catch { pollTimer = window.setTimeout(() => poll(id), 1500) } }
async function cancelRun() { if (!latestRun.value) return; currentRun.value = await api.cancelWorkflowRun(latestRun.value.id); poll(currentRun.value.id) }
async function retryRun(run = historyRun.value) { if (!run) return; currentRun.value = await api.retryWorkflowRun(run.id); panel.value = 'progress'; connectProgress(currentRun.value.id, true); poll(currentRun.value.id) }
async function solidifyRun(run = historyRun.value) {
  if (!run) return
  try {
    const patch = await api.getSolidificationPatch(run.id)
    if (!patch.operations.length) { message.value = patch.summary; return }
    if (!window.confirm(`${patch.summary}。确认加入当前工作流的新版本？`)) return
    const saved = await api.applyWorkflowPatch(run.workflowId, patch)
    openWorkflow(saved); emit('workflowChanged', saved); message.value = `已整理到第 ${saved.currentVersion} 版`
  } catch (error) { message.value = error instanceof Error ? error.message : '本次运行没有整理成功' }
}
function connectProgress(runId: string, reset = false) {
  eventSource?.close()
  if (reset || liveRunId.value !== runId) { liveEvents.value = []; liveContent.value = ''; liveContentNode.value = '' }
  liveRunId.value = runId; liveConnected.value = false
  eventSource = new EventSource(`/api/workflow-runs/${runId}/events`)
  eventSource.onopen = () => { liveConnected.value = true }
  eventSource.addEventListener('progress', raw => {
    const event = JSON.parse((raw as MessageEvent).data) as WorkflowProgressEvent
    if (liveEvents.value.some(item => item.sequence === event.sequence)) return
    liveEvents.value.push(event)
    if (liveEvents.value.length > 400) liveEvents.value.splice(0, liveEvents.value.length - 400)
    if (event.type === 'MODEL_OUTPUT') { if (liveContentNode.value !== event.nodeId) { liveContent.value = ''; liveContentNode.value = event.nodeId }; liveContent.value += event.content }
    if (event.type === 'REVIEW_REQUIRED') { eventSource?.close(); liveConnected.value = false; poll(runId) }
    if (event.type === 'RUN_COMPLETED') { liveConnected.value = false; eventSource?.close(); poll(runId) }
  })
  eventSource.onerror = () => { liveConnected.value = false }
}
function decorateEdge(edge: Pick<Edge, 'id' | 'source' | 'target'> & Partial<Edge>): Edge {
  return {
    ...edge,
    type: 'smoothstep',
    markerEnd: { type: MarkerType.ArrowClosed, color: '#668bb4', width: 18, height: 18 },
    style: { ...((edge.style as Record<string, unknown> | undefined) ?? {}), stroke: '#668bb4', strokeWidth: 2 },
  } as Edge
}
function connect(connection: Connection) { if (connection.source && connection.target) edges.value = addEdge(decorateEdge({ ...connection, id: `e_${connection.source}_${connection.target}_${Date.now()}` } as Edge), edges.value) }
function focusEdge(id: string) {
  selectedEdgeId.value = id; selectedNodeId.value = ''; panel.value = 'none'
  edges.value = edges.value.map(edge => ({ ...edge, selected: edge.id === id, class: edge.id === id ? 'trace-active' : 'trace-muted', markerEnd: { type: MarkerType.ArrowClosed, color: edge.id === id ? '#1473d2' : '#9fb1c5', width: edge.id === id ? 22 : 16, height: edge.id === id ? 22 : 16 } }))
  const active = edges.value.find(edge => edge.id === id)
  nodes.value.forEach(node => { node.data.flowRole = node.id === active?.source ? 'source' : node.id === active?.target ? 'target' : '' })
}
function clearFlowFocus() {
  selectedEdgeId.value = ''
  edges.value = edges.value.map(edge => decorateEdge({ ...edge, selected: false, class: '' }))
  nodes.value.forEach(node => { node.data.flowRole = '' })
}
function selectNode(id: string) { clearFlowFocus(); selectedNodeId.value = id; panel.value = 'none' }
function autoLayout() {
  if (!nodes.value.length) return
  const byId = new Map(nodes.value.map(node => [node.id, node]))
  const incoming = new Map(nodes.value.map(node => [node.id, 0]))
  const outgoing = new Map(nodes.value.map(node => [node.id, [] as string[]]))
  const parents = new Map(nodes.value.map(node => [node.id, [] as string[]]))
  edges.value.forEach(edge => {
    if (!byId.has(edge.source) || !byId.has(edge.target)) return
    outgoing.get(edge.source)?.push(edge.target)
    parents.get(edge.target)?.push(edge.source)
    incoming.set(edge.target, (incoming.get(edge.target) ?? 0) + 1)
  })
  const level = new Map(nodes.value.map(node => [node.id, 0]))
  const queue = nodes.value.filter(node => incoming.get(node.id) === 0).sort((a, b) => a.position.y - b.position.y)
  const visited = new Set<string>()
  while (queue.length) {
    const node = queue.shift()!
    if (visited.has(node.id)) continue
    visited.add(node.id)
    for (const target of outgoing.get(node.id) ?? []) {
      level.set(target, Math.max(level.get(target) ?? 0, (level.get(node.id) ?? 0) + 1))
      incoming.set(target, (incoming.get(target) ?? 1) - 1)
      if (incoming.get(target) === 0) queue.push(byId.get(target)!)
    }
  }
  nodes.value.filter(node => !visited.has(node.id)).forEach(node => {
    const knownParentLevels = (parents.get(node.id) ?? []).map(id => level.get(id) ?? 0)
    level.set(node.id, knownParentLevels.length ? Math.max(...knownParentLevels) + 1 : 0)
  })
  const columns = new Map<number, Node[]>()
  nodes.value.forEach(node => {
    const column = level.get(node.id) ?? 0
    columns.set(column, [...(columns.get(column) ?? []), node])
  })
  const xGap = 285
  const yGap = 128
  for (const [column, columnNodes] of [...columns.entries()].sort(([left], [right]) => left - right)) {
    columnNodes.sort((a, b) => a.position.y - b.position.y)
    const startY = Math.max(48, 330 - ((columnNodes.length - 1) * yGap) / 2)
    columnNodes.forEach((node, index) => { node.position = { x: 70 + column * xGap, y: startY + index * yGap } })
  }
  edges.value = edges.value.map(decorateEdge)
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
  validation.value = null
  message.value = '已按数据流方向自动排布，保存后生效'
  window.requestAnimationFrame(() => fitView({ padding: 0.18, duration: 500 }))
}
function defaultPoint() { return { x: 120 + (nodes.value.length % 4) * 45, y: 100 + (nodes.value.length % 5) * 45 } }
function add(type: WorkflowNodeType, position = defaultPoint()) { const node = makeNode(type, position.x, position.y); nodes.value.push(node); selectedNodeId.value = node.id; panel.value = 'none'; validation.value = null }
function addResource(resource: WorkspaceResource, position = defaultPoint()) { const node = resourceNode(resource, position.x, position.y); nodes.value.push(node); selectedNodeId.value = node.id; panel.value = 'none'; validation.value = null }
function addLink(url: string, position: { x: number; y: number }) { try { const title = new URL(url).hostname; const node = makeNode('LINK_INPUT', position.x, position.y, title, { url, title }); nodes.value.push(node); selectedNodeId.value = node.id } catch { message.value = '这个链接无法加入工作流' } }
function onDragStart(event: DragEvent, kind: 'step' | 'resource', value: WorkflowNodeType | WorkspaceResource) { if (!event.dataTransfer) return; event.dataTransfer.effectAllowed = 'copy'; event.dataTransfer.setData(kind === 'step' ? 'application/x-finflow-step' : 'application/x-finflow-resource', JSON.stringify(value)) }
function onCanvasDrop(event: DragEvent) { const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY }); const resource = event.dataTransfer?.getData('application/x-finflow-resource'); const step = event.dataTransfer?.getData('application/x-finflow-step'); const uri = event.dataTransfer?.getData('text/uri-list')?.split('\n').find(line => /^https?:\/\//.test(line)); if (resource) addResource(JSON.parse(resource), position); else if (step) add(JSON.parse(step), position); else if (uri) addLink(uri, position) }
function removeSelected() { if (!selectedNode.value) return; const id = selectedNode.value.id; nodes.value = nodes.value.filter(node => node.id !== id); edges.value = edges.value.filter(edge => edge.source !== id && edge.target !== id); selectedNodeId.value = ''; validation.value = null }
function removeSelectedEdge() { if (!selectedEdgeId.value) return; edges.value = edges.value.filter(edge => edge.id !== selectedEdgeId.value); clearFlowFocus(); validation.value = null }
function handleDeleteKey(event: KeyboardEvent) { const target = event.target as HTMLElement | null; if (!['Delete', 'Backspace'].includes(event.key) || target?.matches('input, textarea, select, [contenteditable="true"]')) return; if (selectedEdgeId.value) { event.preventDefault(); removeSelectedEdge() } }
function resourceIcon(item: WorkspaceResource) { if (item.resourceType === 'DATABASE_CONNECTION') return Database; if (item.resourceType === 'API_CONNECTION') return Braces; if (['DATASET', 'DATA_FILE'].includes(item.resourceType)) return FileSpreadsheet; if (item.resourceType === 'DELIVERABLE') return FileOutput; return item.resourceType === 'OFFICE_FILE' ? File : FileText }
function statusText(status: string) { return ({ QUEUED: '等待开始', PENDING: '等待', RUNNING: '进行中', WAITING_REVIEW: '等待复核', SUCCEEDED: '完成', FAILED: '失败', REJECTED: '复核未通过', CANCELED: '已停止', CANCEL_REQUESTED: '正在停止', REUSED: '沿用上次结果' } as Record<string, string>)[status] ?? status }
function syncReviewState() {
  const step = currentRun.value?.nodes.find(node => node.status === 'WAITING_REVIEW')
  const key = step ? `${currentRun.value?.id}-${step.nodeId}` : ''
  if (!step || key === reviewStateKey.value) return
  reviewStateKey.value = key; reviewComment.value = ''; reviewContent.value = String(step.output.reviewContent ?? '')
}
async function confirmReview() {
  if (!currentRun.value || !reviewStep.value) return
  if (reviewStep.value.output.requireComment && !reviewComment.value.trim()) { message.value = '请先填写复核说明'; return }
  reviewBusy.value = true
  try { currentRun.value = await api.confirmWorkflowReview(currentRun.value.id, reviewComment.value, reviewContent.value); message.value = '复核已确认，正在继续执行'; connectProgress(currentRun.value.id); poll(currentRun.value.id) }
  catch (error) { message.value = error instanceof Error ? error.message : '复核结果没有提交' }
  finally { reviewBusy.value = false }
}
async function rejectReview() {
  if (!currentRun.value) return
  if (!reviewComment.value.trim()) { message.value = '请说明需要调整的内容'; return }
  if (!window.confirm('本次运行会结束，你可修改上游步骤后重新执行。确定退回吗？')) return
  reviewBusy.value = true
  try { currentRun.value = await api.rejectWorkflowReview(currentRun.value.id, reviewComment.value); await loadRuns(); message.value = '已退回本次运行，可根据意见修改后重新执行' }
  catch (error) { message.value = error instanceof Error ? error.message : '退回操作没有完成' }
  finally { reviewBusy.value = false }
}
function openReviewItem(item: Record<string, unknown>) {
  const deliverableId = String(item.deliverableId ?? '')
  if (deliverableId) emit('openDeliverable', deliverableId)
  else { const id = String(item.fileId ?? item.extractJobId ?? ''); if (id) emit('openResource', id) }
}
function triggerText(trigger?: string) { return ({ MANUAL: '手工执行', SCHEDULED: '定时执行', RETRY: '重新执行' } as Record<string, string>)[trigger ?? 'MANUAL'] }
function analysisMode(step: WorkflowRun['nodes'][number]) { const mode = String(step.output?.analysisMode ?? ''); return ['codex', 'codex-cli'].includes(mode) ? ' · Codex 生成' : mode === 'local-extractive' ? ' · 本地分析' : mode ? ` · ${mode}` : '' }
function formatTime(value?: string) { return value ? new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(value)) : '尚未执行' }
function formatEventTime(value: string) { return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(value)) }
function requirementPlaceholder(format: unknown) {
  const value = String(format).toUpperCase()
  if (['MERMAID', 'EXCALIDRAW'].includes(value)) return '例如：生成从左到右的业务流程图，主链路不超过 8 个节点，标出关键判断和异常分支。'
  if (value === 'FINANCIAL_REPORT') return '例如：按业务板块展示收入、毛利和费用的本期、预算及同比差异，突出异常项目。'
  return '例如：先给结论，对比本期与预算，突出风险和下一步行动，语言简洁。'
}
watch([issuesByNode, runByNode], () => nodes.value.forEach(node => { node.data.issue = issuesByNode.value.has(node.id); node.data.status = runByNode.value.get(node.id) ?? '' }), { immediate: true })
watch(() => [props.project?.id, props.workflowId], () => { activeId.value = ''; eventSource?.close(); liveRunId.value = ''; liveEvents.value = []; liveContent.value = ''; liveContentNode.value = ''; load() }); onMounted(() => { load(); window.addEventListener('keydown', handleDeleteKey) }); onBeforeUnmount(() => { window.clearTimeout(pollTimer); eventSource?.close(); window.removeEventListener('keydown', handleDeleteKey) })
</script>

<template>
  <main class="workflow-workspace" :class="{ 'has-notice': !!message }">
    <header class="workflow-header">
      <div class="workflow-heading"><p class="eyebrow">工作流 · 第 {{ currentVersion }} 版</p><input v-model="name" class="workflow-title-input" aria-label="工作流名称"><input v-model="description" class="workflow-description-input" aria-label="工作流说明"></div>
      <div class="workflow-actions">
        <button class="secondary-button" type="button" title="添加内容" aria-label="添加内容" @click="panel = 'add'"><Plus :size="16"/><span>添加内容</span></button>
        <button class="secondary-button" type="button" title="自动排布" aria-label="自动排布" @click="autoLayout"><Network :size="16"/><span>自动排布</span></button>
        <button class="secondary-button" type="button" :title="executionMode === 'SCHEDULED' ? '定时执行' : '手工执行'" :aria-label="executionMode === 'SCHEDULED' ? '定时执行' : '手工执行'" @click="panel = 'schedule'"><CalendarClock :size="16"/><span>{{ executionMode === 'SCHEDULED' ? '定时执行' : '手工执行' }}</span></button>
        <button v-if="latestRun || liveEvents.length" class="secondary-button" type="button" title="运行进展" aria-label="运行进展" @click="panel = 'progress'"><Activity :size="16"/><span>运行进展</span></button>
        <button class="secondary-button" type="button" title="执行历史" aria-label="执行历史" @click="panel = 'history'; loadRuns()"><History :size="16"/><span>执行历史</span></button>
        <button class="secondary-button" type="button" title="保存" aria-label="保存" :disabled="busy" @click="save()"><Save :size="16"/><span>保存</span></button>
        <button class="secondary-button icon-command" type="button" title="检查工作流" aria-label="检查工作流" @click="check"><CheckCircle2 :size="17"/></button>
        <button class="primary-button" type="button" title="立即运行" aria-label="立即运行" :disabled="busy || runActive" @click="run"><Play :size="16"/><span>立即运行</span></button>
      </div>
    </header>
    <p v-if="message" class="workflow-notice">{{ message }}</p>
    <section class="workflow-canvas" aria-label="工作流编排画布" @dragover.prevent @drop.prevent="onCanvasDrop">
      <VueFlow v-model:nodes="nodes" v-model:edges="edges" :min-zoom="0.35" :max-zoom="1.6" fit-view-on-init @connect="connect" @node-click="selectNode($event.node.id)" @edge-click="focusEdge($event.edge.id)" @pane-click="selectedNodeId = ''; clearFlowFocus()">
        <Background :gap="20" color="#dce6f2"/><Controls position="bottom-left"/><MiniMap position="bottom-right" pannable zoomable/><template #node-business="slotProps"><WorkflowNode v-bind="slotProps" /></template>
      </VueFlow>
      <div v-if="selectedEdgeId && panel === 'none'" class="edge-trace-banner"><small>当前数据流</small><strong>{{ selectedEdgeSource }} <i>→</i> {{ selectedEdgeTarget }}</strong></div>
      <div class="canvas-drop-hint">从左侧拖入文件、数据、输出件或链接</div><button v-if="selectedEdgeId && panel === 'none'" class="selected-edge-toolbar" type="button" @click="removeSelectedEdge"><Trash2 :size="15"/>删除连线</button><section v-if="panel === 'add'" class="canvas-add-panel"><header><strong>添加到工作流</strong><button class="icon-button" type="button" title="关闭" @click="panel = 'none'"><X :size="16"/></button></header><label><Search :size="14"/><input v-model="addSearch" placeholder="查找项目内容或处理步骤"></label><div class="unified-add-list"><p>项目内容</p><button v-for="item in filteredResources" :key="item.id" draggable="true" type="button" @dragstart="onDragStart($event, 'resource', item)" @click="addResource(item)"><component :is="resourceIcon(item)" :size="16"/><span><strong>{{ item.name }}</strong><small>{{ item.group === 'DATA' ? '数据' : item.group === 'OUTPUT' ? '输出件' : '资料' }}</small></span><Plus :size="14"/></button><p>处理步骤</p><button v-for="item in filteredCatalog" :key="item.type" draggable="true" type="button" @dragstart="onDragStart($event, 'step', item.type)" @click="add(item.type)"><component :is="item.icon" :size="16"/><span><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></span><Plus :size="14"/></button></div></section>
      <aside v-if="selectedNode && panel === 'none'" class="canvas-drawer node-drawer"><header><div><span>步骤设置</span><input v-model="selectedNode.data.label" aria-label="步骤名称"></div><button class="icon-button danger" type="button" title="删除步骤" @click="removeSelected"><Trash2 :size="16"/></button></header><div class="form-stack compact-fields">
        <template v-if="selectedNode.data.nodeType === 'FILE_INPUT'"><label><span>项目文件</span><select v-model="selectedNode.data.config.resourceId"><option value="">请选择文件</option><option v-for="file in files" :key="file.id" :value="file.id">{{ file.name }} · 第 {{ file.currentVersion }} 版</option></select></label></template>
        <template v-else-if="selectedNode.data.nodeType === 'LINK_INPUT'"><label><span>链接地址</span><input v-model="selectedNode.data.config.url" type="url" placeholder="https://"></label><label><span>显示名称</span><input v-model="selectedNode.data.config.title"></label></template>
        <template v-else-if="selectedNode.data.nodeType === 'DATASET_INPUT'"><p class="field-help">使用项目中已经采集完成的数据文件。</p></template>
        <template v-else-if="selectedNode.data.nodeType === 'DATA_EXTRACT'"><label><span>数据连接</span><select v-model="selectedNode.data.config.connectionId"><option value="">请选择连接</option><option v-for="item in connections" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><label><span>查询内容</span><textarea v-model="selectedNode.data.config.sql" rows="6"></textarea></label><label><span>输出文件名</span><input v-model="selectedNode.data.config.outputName"></label></template>
        <template v-else-if="selectedNode.data.nodeType === 'DATA_TRANSFORM'">
          <section class="transform-inputs">
            <div class="field-section-title"><span>已连接的数据</span><small>{{ selectedIncoming.length }} 份</small></div>
            <p v-if="!selectedIncoming.length" class="field-help">请在画布中把 CSV、Excel、已采集数据或提取数据步骤连接到这里。</p>
            <label v-for="(source, index) in selectedIncoming" :key="source.id" class="transform-input-row">
              <span>{{ source.data.label }}</span>
              <input :value="inputAlias(source.id, index)" placeholder="数据别名" @input="setInputAlias(source.id, ($event.target as HTMLInputElement).value)">
            </label>
          </section>
          <label><span>加工要求</span><textarea v-model="selectedNode.data.config.requirements" rows="6" placeholder="例如：按客户编号关联两份数据，保留全部销售记录，汇总本月金额并标记缺失的客户信息。"></textarea></label>
          <div class="transform-actions"><button class="secondary-button" type="button" :disabled="transformBusy || !selectedIncoming.length" @click="generateTransformScript"><Sparkles :size="14"/>{{ transformBusy ? '正在处理' : '生成加工脚本' }}</button><button class="secondary-button" type="button" :disabled="transformBusy || !selectedNode.data.config.script" @click="sampleTransformScript"><Play :size="14"/>样本试跑</button></div>
          <p v-if="selectedNode.data.config.scriptSummary" class="transform-summary">{{ selectedNode.data.config.scriptSummary }}<small>{{ selectedNode.data.config.scriptMode }}</small></p>
          <label class="script-editor"><span><Code2 :size="13"/>可查看、可修改的加工脚本</span><textarea v-model="selectedNode.data.config.script" rows="12" spellcheck="false" placeholder="先填写加工要求，再生成脚本"></textarea></label>
          <section v-if="selectedSampleReport" class="sample-report"><div><CheckCircle2 :size="15"/><strong>样本试跑通过</strong><small>{{ selectedSampleReport.sampleRowCount }} 行 · {{ selectedSampleReport.columns.length }} 列</small></div><p v-for="item in selectedSampleReport.checks" :key="item">{{ item }}</p></section>
          <label><span>输出文件名</span><input v-model="selectedNode.data.config.outputName" placeholder="数据加工结果.csv"></label>
        </template>
        <template v-else-if="selectedNode.data.nodeType === 'REF_SEARCH'"><label><span>查找内容</span><textarea v-model="selectedNode.data.config.query" rows="4"></textarea></label><label><span>最多使用</span><input v-model.number="selectedNode.data.config.limit" type="number" min="1" max="50"></label></template>
        <template v-else-if="selectedNode.data.nodeType === 'AI_ANALYSIS'"><label><span>分析要求</span><textarea v-model="selectedNode.data.config.prompt" rows="9" placeholder="直接描述希望大模型如何分析已连接的资料"></textarea></label></template>
        <template v-else-if="selectedNode.data.nodeType === 'AGENT_TASK'">
          <label><span>希望 Agent 完成什么</span><textarea v-model="selectedNode.data.config.instruction" rows="8" placeholder="例如：研究目标市场变化，结合项目资料找出关键风险，并给出有来源的结论。"></textarea></label>
          <label><span>联网范围</span><select v-model="selectedNode.data.config.externalResearch"><option value="OFF">只使用项目内容</option><option value="PUBLIC_READ">可搜索公开网页</option><option value="DOMAIN_ALLOWLIST">只访问指定网站</option><option value="CONNECTED_SOURCES">只使用已连接来源</option></select></label>
          <label v-if="selectedNode.data.config.externalResearch === 'DOMAIN_ALLOWLIST'"><span>允许的网站</span><input :value="Array.isArray(selectedNode.data.config.domainAllowlist) ? selectedNode.data.config.domainAllowlist.join(', ') : ''" placeholder="例如：sec.gov, nvidia.com" @input="selectedNode.data.config.domainAllowlist = ($event.target as HTMLInputElement).value.split(',').map(item => item.trim()).filter(Boolean)"></label>
          <div class="agent-budget-grid"><label><span>最多调用次数</span><input v-model.number="selectedNode.data.config.maxToolCalls" type="number" min="1" max="80"></label><label><span>最长运行时间（秒）</span><input v-model.number="selectedNode.data.config.timeoutSeconds" type="number" min="30" max="900"></label></div>
          <p class="field-help">任务步骤不需要预先写死。采用的网页会自动保存到项目资料，并进入来源链路。</p>
        </template>
        <template v-else-if="selectedNode.data.nodeType === 'DELIVERABLE'">
          <fieldset class="deliverable-format"><legend>成果类型</legend><button v-for="item in deliverableFormats" :key="item.value" type="button" :class="{ active: selectedNode.data.config.format === item.value }" @click="selectDeliverableFormat(item.value)"><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></button></fieldset>
          <section v-if="selectedNode.data.config.format === 'FINANCIAL_REPORT'" class="financial-report-card"><BarChart3 :size="21"/><div><strong>内置财务报告</strong><p>运行后可直接在当前工作区查看和下载。</p></div></section>
          <section v-if="selectedNode.data.config.format === 'HTML_SLIDES'" class="web-slides-notice"><FileOutput :size="21"/><div><strong>网页演示</strong><p>生成可在浏览器播放、翻页和全屏展示的 HTML 文件。</p></div></section>
          <fieldset v-if="['PPTX','HTML_SLIDES'].includes(String(selectedNode.data.config.format))" class="ppt-skill-picker"><legend>演示 Skill</legend><button v-if="selectedNode.data.config.format === 'PPTX'" type="button" :class="{ active: !selectedNode.data.config.pptSkill }" @click="selectedNode.data.config.pptSkill = ''"><span class="skill-swatch default"></span><span><strong>FinBTP Studio 简约蓝白</strong><small>通用 PowerPoint 汇报</small></span></button><button v-for="skill in selectedPresentationSkills" :key="skill.id" type="button" :class="{ active: selectedNode.data.config.pptSkill === skill.id }" @click="selectedNode.data.config.pptSkill = skill.id"><span class="skill-swatch" :class="skill.id === 'frontend-slides' ? 'web' : 'huawei'"></span><span><strong>{{ skill.name }}</strong><small>{{ skill.description }}</small></span></button></fieldset>
          <section v-if="['PPTX','HTML_SLIDES','DOCX','PDF','FINANCIAL_REPORT'].includes(String(selectedNode.data.config.format))" class="citation-settings">
            <label class="toggle-field"><input v-model="selectedNode.data.config.includeCitations" type="checkbox"><span>标注信息来源</span></label>
            <label v-if="selectedNode.data.config.includeCitations"><span>引用格式</span><select v-model="selectedNode.data.config.citationStyle"><option value="IEEE">IEEE</option><option value="APA_7">APA 第 7 版</option><option value="GB_T_7714">GB/T 7714-2015</option></select></label>
            <p v-if="selectedNode.data.config.includeCitations">正文使用规范短标记，完整条目集中放在参考文献部分。</p><p v-else>成果中不显示来源编号、页脚来源或参考文献。</p>
          </section>
          <label v-if="selectedNode.data.config.format === 'MERMAID'" class="toggle-field"><input v-model="selectedNode.data.config.handDrawn" type="checkbox"><span>生成可编辑的 Excalidraw 手绘版</span></label>
          <label><span>生成要求</span><textarea v-model="selectedNode.data.config.generationPrompt" rows="8" :placeholder="requirementPlaceholder(selectedNode.data.config.format)"></textarea></label>
        </template>
      </div><p v-for="issue in validation?.issues.filter(item => item.nodeId === selectedNode?.id)" :key="issue.message" class="inspector-error">{{ issue.message }}</p><footer><button class="primary-button" type="button" :disabled="busy" @click="saveNodeSettings"><Save :size="15"/>{{ busy ? '正在保存' : '保存节点设置' }}</button></footer></aside>
      <aside v-if="panel === 'schedule'" class="canvas-drawer schedule-drawer"><header><div><span>执行设置</span><strong>选择工作流如何启动</strong></div><button class="icon-button" type="button" title="关闭" @click="panel = 'none'"><X :size="17"/></button></header><div class="execution-mode"><button type="button" :class="{ active: executionMode === 'MANUAL' }" @click="executionMode = 'MANUAL'"><Play :size="17"/><span><strong>手工执行</strong><small>需要时点击立即运行</small></span></button><button type="button" :class="{ active: executionMode === 'SCHEDULED' }" @click="executionMode = 'SCHEDULED'"><Clock3 :size="17"/><span><strong>定时执行</strong><small>按设定时间自动运行</small></span></button></div><div v-if="executionMode === 'SCHEDULED'" class="form-stack schedule-fields"><label><span>执行频率</span><select v-model="frequency"><option value="HOURLY">每小时</option><option value="DAILY">每天</option><option value="WEEKLY">每周</option><option value="MONTHLY">每月</option></select></label><label v-if="frequency === 'WEEKLY'"><span>星期</span><select v-model.number="dayOfWeek"><option v-for="(day, i) in ['一','二','三','四','五','六','日']" :key="day" :value="i + 1">星期{{ day }}</option></select></label><label v-if="frequency === 'MONTHLY'"><span>每月日期</span><input v-model.number="dayOfMonth" type="number" min="1" max="31"></label><label><span>{{ frequency === 'HOURLY' ? '每小时的分钟' : '执行时间' }}</span><input v-model="scheduleTime" type="time"></label><label><span>时区</span><select v-model="timezone"><option value="Asia/Shanghai">中国标准时间</option><option value="UTC">协调世界时</option><option value="Asia/Hong_Kong">香港时间</option><option value="Asia/Singapore">新加坡时间</option></select></label><p v-if="nextRunAt" class="next-run"><CalendarClock :size="15"/>下次执行：{{ formatTime(nextRunAt) }}</p></div><footer><button class="primary-button" type="button" @click="save(); panel = 'none'">保存执行设置</button></footer></aside>
      <aside v-if="panel === 'progress'" class="canvas-drawer progress-drawer">
        <header><div><span>运行进展</span><strong>{{ liveMessage }}</strong></div><button class="icon-button" type="button" title="关闭" @click="panel = 'none'"><X :size="17"/></button></header>
        <section class="progress-overview"><div><span>{{ statusText(latestRun?.status ?? 'QUEUED') }}</span><strong>{{ liveProgress }}%</strong></div><div class="progress-track"><i :style="{ width: `${liveProgress}%` }"></i></div><p><span class="live-indicator" :class="{ connected: liveConnected }"></span>{{ liveConnected ? '正在实时接收' : reviewStep ? '已保存中间结果，等待你处理' : runExecuting ? '正在重新连接' : '本次运行已结束' }}<button v-if="runActive" class="secondary-button" type="button" @click="cancelRun"><CircleStop :size="14"/>停止</button></p></section>
        <section v-if="reviewStep" class="review-runtime">
          <header><CircleUserRound :size="18"/><div><strong>{{ reviewTitle }}</strong><p>{{ reviewInstructions }}</p></div></header>
          <div v-if="reviewItems.length" class="review-artifacts"><button v-for="item in reviewItems" :key="String(item.nodeId)" type="button" :disabled="!item.fileId && !item.extractJobId && !item.deliverableId" @click="openReviewItem(item)"><FileSpreadsheet v-if="item.fileId || item.extractJobId" :size="16"/><FileOutput v-else-if="item.deliverableId" :size="16"/><FileText v-else :size="16"/><span><strong>{{ item.name || item.fileName || '上游结果' }}</strong><small>{{ item.rowCount ? `${Number(item.rowCount).toLocaleString()} 行 · ` : '' }}{{ item.fileId || item.extractJobId || item.deliverableId ? '点击查看或编辑' : '文本分析结果' }}</small></span><ChevronRight :size="14"/></button></div>
          <label v-if="reviewContent || reviewEditable"><span>{{ reviewEditable ? '可直接调整的分析结果' : '待复核内容' }}</span><textarea v-model="reviewContent" :readonly="!reviewEditable" rows="10" placeholder="上游步骤没有可编辑的文本内容"></textarea></label>
          <label><span>复核说明<span v-if="reviewStep.output.requireComment">（必填）</span></span><textarea v-model="reviewComment" rows="3" placeholder="记录核对结果、调整原因或退回意见"></textarea></label>
          <footer><button class="secondary-button danger" type="button" :disabled="reviewBusy" @click="rejectReview"><RotateCcw :size="14"/>退回调整</button><button class="primary-button" type="button" :disabled="reviewBusy" @click="confirmReview"><CheckCircle2 :size="15"/>{{ reviewBusy ? '正在提交' : '确认并继续' }}</button></footer>
        </section>
        <section v-if="liveContent && !reviewStep" class="model-stream"><header><Sparkles :size="15"/><strong>{{ liveContentNode ? `${liveEvents.findLast(item => item.nodeId === liveContentNode)?.nodeName || '大模型'}生成内容` : '大模型生成内容' }}</strong><span>实时输出</span></header><pre>{{ liveContent }}</pre></section>
        <ol v-if="timelineEvents.length" class="progress-events"><li v-for="event in timelineEvents" :key="event.sequence" :data-status="event.status"><span></span><div><strong>{{ event.nodeName || '主工作流' }}</strong><p>{{ event.message }}</p><small>{{ formatEventTime(event.createdAt) }}</small></div></li></ol>
        <div v-else class="drawer-empty"><Activity :size="26"/><strong>准备显示运行进展</strong><p>节点执行、数据处理和大模型生成状态会实时显示在这里。</p></div>
      </aside>
      <aside v-if="panel === 'history'" class="canvas-drawer history-drawer"><header><div><span>执行历史</span><strong>{{ runs.length ? `共 ${runs.length} 次` : '还没有运行记录' }}</strong></div><button class="icon-button" type="button" title="关闭" @click="panel = 'none'"><X :size="17"/></button></header><div v-if="runs.length" class="history-list"><button v-for="item in runs" :key="item.id" type="button" :class="{ active: historyRun?.id === item.id }" @click="historyRunId = item.id"><span class="status-dot" :data-status="item.status"></span><span><strong>{{ statusText(item.status) }} · 第 {{ item.workflowVersion }} 版</strong><small>{{ triggerText(item.triggerType) }} · {{ formatTime(item.createdAt) }}</small></span><ChevronRight :size="15"/></button></div><section v-if="historyRun" class="history-detail"><div class="history-summary"><span class="status" :data-status="historyRun.status">{{ statusText(historyRun.status) }}</span><span>{{ triggerText(historyRun.triggerType) }}</span><span>{{ formatTime(historyRun.startedAt ?? historyRun.createdAt) }}</span><button v-if="historyRun.status === 'SUCCEEDED'" class="secondary-button" type="button" @click="solidifyRun(historyRun)"><Network :size="14"/>整理为固定步骤</button><button v-if="['FAILED','CANCELED'].includes(historyRun.status)" class="secondary-button" type="button" @click="retryRun(historyRun)"><RotateCcw :size="14"/>重新执行</button><button v-if="runActive && historyRun.id === latestRun?.id" class="secondary-button" type="button" @click="cancelRun"><CircleStop :size="14"/>停止</button></div><p v-if="historyRun.errorMessage" class="inspector-error">{{ historyRun.errorMessage }}</p><ol class="run-steps"><li v-for="step in historyRun.nodes" :key="step.id" :data-status="step.status"><span></span><div><strong>{{ step.nodeName }}</strong><small>{{ statusText(step.status) }}{{ analysisMode(step) }}</small><button v-if="['DELIVERABLE','OUTPUT'].includes(step.nodeType) && step.output?.deliverableId" class="run-output-link" type="button" @click="emit('openDeliverable', String(step.output.deliverableId))">在工作区打开</button><details v-if="step.activities?.length" class="activity-list"><summary>{{ step.activities.length }} 条处理记录</summary><div v-for="activity in step.activities" :key="activity.id"><strong>{{ activity.title }}</strong><small>{{ activity.capability || activity.type }} · {{ statusText(activity.status) }}</small></div></details><p v-if="step.errorMessage">{{ step.errorMessage }}</p></div></li></ol><section v-if="historyRun.lineage?.length" class="lineage-summary"><strong>来源与产出链路</strong><p>{{ historyRun.lineage.length }} 条实际关系已记录，可从输出件反查本次使用的资料和数据版本。</p></section></section><div v-else class="drawer-empty"><History :size="26"/><strong>还没有执行历史</strong><p>运行后，每次执行、内部处理和来源链路都会保留在这里。</p></div></aside>
    </section>
  </main>
</template>
