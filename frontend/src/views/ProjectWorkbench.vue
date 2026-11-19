<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Check, ListChecks } from 'lucide-vue-next'
import { Braces, ChevronDown, ChevronRight, ClipboardPaste, Database, Eye, EyeOff, File, FileOutput, Files, FileSpreadsheet, FileText, FolderOpen, Globe2, LayoutGrid, LogOut, MoreHorizontal, Pencil, Plus, Search, Server, Trash2, Upload, Workflow as WorkflowIcon, X } from 'lucide-vue-next'
import AssistantPanel from '../components/AssistantPanel.vue'
import AgentWorkOverview from '../components/AgentWorkOverview.vue'
import { workStatus, workStatusLabels } from '../domain/agentWork'
import { internalResourceIds } from '../domain/resourceVisibility'
import type { AgentWork } from '../api/client'
import ResourceWorkbench from '../components/ResourceWorkbench.vue'
import { api, type CitationSource, type PasteImportPreview, type Project, type ProjectWorkspace, type WorkflowNode, type WorkspaceFolder, type WorkspaceResource, type WorkspaceRootKind } from '../api/client'
import { buildDatabaseJdbcUrl, defaultDatabasePort, parseDatabaseConnection, type DatabaseSourceType } from '../domain/databaseConnection'
import { MAX_UPLOAD_FILES, formatUploadSize, MAX_UPLOAD_FILE_BYTES, MAX_UPLOAD_SELECTION_BYTES, uploadSelectionError } from '../domain/fileUpload'
import { sourceDropAccepts } from '../domain/sourceDrop'
import DataView from './DataView.vue'
import { useProjectsStore } from '../stores/projects'
import { useAssistantStore } from '../stores/assistant'
import { ElMessageBox } from 'element-plus'

type Tab = { id: string; title: string; kind: 'home' | 'data' | 'resource'; resource?: WorkspaceResource }
type UiRoot = 'DATA' | 'KNOWLEDGE' | 'OUTPUT'
type SourceFilter = 'ALL' | 'FILE' | 'DATABASE' | 'API' | 'WEB' | 'OUTPUT'
type PasteFormat = 'AUTO' | 'CSV' | 'TSV' | 'MARKDOWN_TABLE' | 'JSON' | 'TEXT'
const props = defineProps<{ project: Project | null; loading: boolean; error: string; account: string }>()
const emit = defineEmits<{ logout: [] }>()
const projectStore = useProjectsStore()
const works = ref<AgentWork[]>([]), selectedWorkId = ref('')
const workSearch = ref(''), focusMode = ref(false), navigationOpen = ref(false)
const bulkMode = ref(false), selectedResources = ref<string[]>([]), deletingResources = ref(false)
const bulkMoving = ref(false), movingBusy = ref(false)
async function confirmAction(message: string) {
  try { await ElMessageBox.confirm(message, '确认操作', { confirmButtonText: '确认执行', cancelButtonText: '取消', type: 'warning', distinguishCancelAndClose: true }); return true } catch { return false }
}
function chooseResource(resource: WorkspaceResource) {
  if (!bulkMode.value) return openResource(resource)
  selectedResources.value = selectedResources.value.includes(resource.id) ? selectedResources.value.filter(id => id !== resource.id) : [...selectedResources.value, resource.id]
}
async function deleteSelectedResources() {
  if (deletingResources.value) return
  const selected = (workspace.value?.resources ?? []).filter(r => selectedResources.value.includes(r.id))
  if (!selected.length) return
  const references = works.value.filter(w => w.stages.some(s => [...s.inputs, ...s.outputs].some(r => selectedResources.value.includes(r.resourceId))))
  if (!await confirmAction(`删除以下 ${selected.length} 项及其历史版本？此操作不可撤销。\n${selected.map(r => `${r.name}（第 ${r.currentVersion} 版）`).join('\n')}\n受影响工作：${references.map(w => w.goal).join('、') || '未发现已登记引用'}\n逐项执行，失败项会保留并列出。`)) return
  deletingResources.value = true
  const failures: string[] = [], removed: string[] = []
  try {
    await loadWorkspace()
    for (const resource of selected) {
      const current = workspace.value?.resources.find(r => r.id === resource.id)
      if (!current || current.currentVersion !== resource.currentVersion) { failures.push(`${resource.name}：版本或状态已变化，请重新选择`); continue }
      try { await removeResource(resource); removed.push(resource.id) }
      catch (e) { failures.push(`${resource.name}：${e instanceof Error ? e.message : '删除失败'}`) }
    }
    selectedResources.value = selectedResources.value.filter(id => !removed.includes(id))
    await loadWorkspace()
    notice.value = `已删除 ${removed.length} 项。${failures.length ? `未删除 ${failures.length} 项：${failures.join('；')}` : '全部完成。'}`
  } finally { deletingResources.value = false }
}
const visibleWorks = computed(() => works.value.filter(work => `${work.title ?? ''} ${work.goal}`.toLowerCase().includes(workSearch.value.toLowerCase())))
async function selectWork(id: string) {
  if (!props.project) return
  const projectId = props.project.id
  if (assistant.canInterrupt && assistant.sessionId !== id) { notice.value = '请先完成或停止当前助手任务，再切换工作'; return }
  try {
    await assistant.ensureSession(props.project.id)
    await assistant.switchSession(id)
    if (props.project?.id !== projectId) return
    if (assistant.sessionId !== id) throw new Error('工作对话未能打开，请重试')
    selectedWorkId.value = id; activeTabId.value = 'home'
    assistant.setWorkbenchContext('work', works.value.find(w => w.sessionId === id)?.goal ?? '当前工作')
  } catch (e) { notice.value = e instanceof Error ? e.message : '工作未能打开' }
}
async function startAgentWork() {
  if (!props.project || assistant.canInterrupt) return
  selectedWorkId.value = '__new__'
  assistant.open = false
  activeTabId.value = 'home'
}
const assistant = useAssistantStore()
const SIDEBAR_WIDTH_KEY = 'finflow:resource-sidebar-width'
const sidebarWidth = ref(288), resizingSidebar = ref(false)
let sidebarOrigin = 0
const workspace = ref<ProjectWorkspace | null>(null), search = ref(''), notice = ref(''), loadingWorkspace = ref(false)
const sourceFilter = ref<SourceFilter>('ALL'), sourceDropActive = ref(false)
const showInternalFiles = ref(false)
const internalFiles = computed(() => internalResourceIds(workspace.value?.internalResourceIds, works.value))
watch(() => props.project?.id, () => { showInternalFiles.value = false })
watch(showInternalFiles, () => { selectedResources.value = [] })
const projectManagerOpen = ref(false), projectFormOpen = ref(false), editingProjectId = ref(''), projectName = ref(''), projectDescription = ref(''), savingProject = ref(false)
const projectStats = ref<Record<string, { resources: number }>>({})
const folderFormOpen = ref(false), folderEditingId = ref(''), folderName = ref(''), folderRoot = ref<WorkspaceRootKind>('FILES'), folderParentId = ref<string | undefined>()
const movingResource = ref<WorkspaceResource | null>(null), targetFolderId = ref('')
const expandedRoots = ref<Record<UiRoot, boolean>>({ DATA: true, KNOWLEDGE: true, OUTPUT: true })
const expandedBuckets = ref<Record<string, boolean>>({ connections: true, urls: true, uploaded: false, extracts: false, knowledge: true, outputs: true })
const tabs = ref<Tab[]>([{ id: 'home', title: '我的工作', kind: 'home' }]), activeTabId = ref('home')
const uploadInput = ref<HTMLInputElement | null>(null)
const addPanelOpen = ref(false), addPanelMode = ref<'choose' | 'connection' | 'url' | 'paste'>('choose'), addTargetRoot = ref<UiRoot>('DATA'), addTargetFolderId = ref<string | undefined>(), addTargetBucket = ref('')
const uploadAccept = ref(''), savingContent = ref(false)
const uploadingFiles = ref(false)
const connectionForm = reactive({ name: '', sourceType: 'POSTGRESQL', connectionText: '', jdbcUrl: 'jdbc:postgresql://localhost:5432/database', host: 'localhost', port: '5432', database: 'database', parameters: '', username: '', password: '', format: 'json' })
const connectionRecognition = ref('')
const connectionError = ref('')
const connectionPasswordVisible = ref(false)
const urlForm = reactive({ name: '', url: 'https://' })
const pasteForm = reactive<{ name: string; sourceAgent: string; sourceQuestion: string; format: PasteFormat; content: string }>({ name: '', sourceAgent: '', sourceQuestion: '', format: 'AUTO', content: '' })
const pastePreview = ref<PasteImportPreview | null>(null), pasteError = ref('')
const groups = [
  { id: 'DATA', label: '数据', icon: Database },
  { id: 'KNOWLEDGE', label: '资料', icon: FileText },
  { id: 'OUTPUT', label: '输出件', icon: FileOutput },
] as const
const treeRoots: Array<{ id: UiRoot; label: string; icon: unknown; storageRoot: WorkspaceRootKind }> = [
  { id: 'DATA', label: '数据', icon: Database, storageRoot: 'FILES' },
  { id: 'KNOWLEDGE', label: '资料', icon: FileText, storageRoot: 'FILES' },
  { id: 'OUTPUT', label: '输出件', icon: FileOutput, storageRoot: 'OUTPUTS' },
]
const activeTab = computed(() => tabs.value.find(tab => tab.id === activeTabId.value) ?? tabs.value[0])

const openWebTabs = computed(() => tabs.value.filter(tab => tab.kind === 'resource' && tab.resource?.resourceType === 'WEB_URL'))
const filteredResources = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase()
  return (workspace.value?.resources ?? []).filter(item => !keyword || `${item.name} ${resourceLocator(item)} ${shortResourceId(item.id)}`.toLocaleLowerCase().includes(keyword))
})
const sourceFilters: Array<{ id: SourceFilter; label: string }> = [
  { id: 'ALL', label: '全部' },
  { id: 'FILE', label: '文件' },
  { id: 'DATABASE', label: '数据库' },
  { id: 'API', label: 'API' },
  { id: 'WEB', label: '网页' },
  { id: 'OUTPUT', label: '成果' },
]
const sourceResources = computed(() => filteredResources.value
  .filter(item => showInternalFiles.value || !internalFiles.value.has(item.id))
  .filter(item => sourceFilter.value === 'ALL'
    || (sourceFilter.value === 'FILE' && ['DATA_FILE', 'DATASET', 'OFFICE_FILE', 'KNOWLEDGE_FILE'].includes(item.resourceType))
    || (sourceFilter.value === 'DATABASE' && item.resourceType === 'DATABASE_CONNECTION')
    || (sourceFilter.value === 'API' && item.resourceType === 'API_CONNECTION')
    || (sourceFilter.value === 'WEB' && item.resourceType === 'WEB_URL')
    || (sourceFilter.value === 'OUTPUT' && (item.resourceType === 'DELIVERABLE' || works.value.some(w => w.stages.some(s => s.outputs.some(o => o.resourceId === item.id))))))
  .sort((left, right) => new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime()))

function sidebarMaximum() {
  const assistantWidth = assistant.open && window.innerWidth > 980 ? (window.innerWidth > 1240 ? 430 : 400) : 0
  const contextWidth = !assistant.open && window.innerWidth > 1180 ? 230 : 0
  return Math.max(240, Math.min(440, window.innerWidth - assistantWidth - contextWidth - 500))
}
function clampSidebarWidth(value: number) { return Math.round(Math.min(sidebarMaximum(), Math.max(220, value))) }
function rememberSidebarWidth() {
  try { window.localStorage.setItem(SIDEBAR_WIDTH_KEY, String(sidebarWidth.value)) } catch { /* Browser storage may be unavailable. */ }
}
function resizeSidebar(value: number, remember = true) {
  sidebarWidth.value = clampSidebarWidth(value)
  if (remember) rememberSidebarWidth()
}
function startSidebarResize(event: PointerEvent) {
  if (window.innerWidth <= 780) return
  sidebarOrigin = (event.currentTarget as HTMLElement).parentElement?.getBoundingClientRect().left ?? 0
  resizingSidebar.value = true
  document.body.classList.add('resizing-workbench-sidebar')
  window.addEventListener('pointermove', moveSidebarResize)
  window.addEventListener('pointerup', stopSidebarResize, { once: true })
  event.preventDefault()
}
function moveSidebarResize(event: PointerEvent) { if (resizingSidebar.value) resizeSidebar(event.clientX - sidebarOrigin, false) }
function stopSidebarResize() {
  if (!resizingSidebar.value) return
  resizingSidebar.value = false
  document.body.classList.remove('resizing-workbench-sidebar')
  window.removeEventListener('pointermove', moveSidebarResize)
  rememberSidebarWidth()
}
function resetSidebarWidth() { resizeSidebar(288) }
function restoreSidebarWidth() {
  let saved = 288
  try { saved = Number(window.localStorage.getItem(SIDEBAR_WIDTH_KEY)) || 288 } catch { /* Use the default width. */ }
  resizeSidebar(saved, false)
}

let workspaceLoadSequence = 0
async function loadWorkspace(expectedProjectId = props.project?.id) {
  if (!expectedProjectId || props.project?.id !== expectedProjectId) return
  const projectId = expectedProjectId
  const sequence = ++workspaceLoadSequence
  loadingWorkspace.value = true
  try {
    const result = await api.getProjectWorkspace(projectId)
    const refreshedWorks = await api.listWorks(projectId)
    if (sequence !== workspaceLoadSequence || projectId !== props.project?.id
        || result.project.id !== projectId) return
    workspace.value = result
    works.value = refreshedWorks
    tabs.value.forEach(tab => {
      if (tab.resource) {
        tab.resource = workspace.value?.resources.find(item => item.id === tab.resource?.id) ?? tab.resource
        tab.title = resourceTabTitle(tab.resource)
      }
    })
  }
  catch (reason) {
    if (sequence === workspaceLoadSequence && projectId === props.project?.id) {
      notice.value = reason instanceof Error ? reason.message : '项目内容没有加载'
    }
  }
  finally { if (sequence === workspaceLoadSequence) loadingWorkspace.value = false }
}
function resources(group: string) { return filteredResources.value.filter(item => item.group === group) }
function rootResources(root: UiRoot) { return filteredResources.value.filter(item => item.group === root) }
function rootLooseResources(root: UiRoot) { return rootResources(root).filter(item => !item.folderId) }
function rootBuckets(root: UiRoot) {
  const loose = rootLooseResources(root)
  if (root === 'DATA') return [
    { id: 'connections', label: '数据库与数据服务', items: loose.filter(item => ['DATABASE_CONNECTION', 'API_CONNECTION'].includes(item.resourceType)) },
    { id: 'uploaded', label: '文件数据', items: loose.filter(item => item.resourceType === 'DATA_FILE') },
    { id: 'extracts', label: '数据采集结果', items: loose.filter(item => item.resourceType === 'DATASET') },
  ]
  if (root === 'KNOWLEDGE') return [
    { id: 'urls', label: '网站地址', items: loose.filter(item => item.resourceType === 'WEB_URL') },
    { id: 'knowledge', label: '文件资料', items: loose.filter(item => item.resourceType !== 'WEB_URL') },
  ]
  if (root === 'OUTPUT' && loose.length > 0) return [{ id: 'outputs', label: '全部成果', items: loose }]
  return []
}
function foldersForRoot(root: UiRoot) {
  const resources = rootResources(root)
  const folders = workspace.value?.folders ?? []
  const visible = new Set(resources.map(item => item.folderId).filter(Boolean))
  let changed = true
  while (changed) { changed = false; folders.forEach(folder => { if (visible.has(folder.id) && folder.parentId && !visible.has(folder.parentId)) { visible.add(folder.parentId); changed = true } }) }
  return folders.filter(folder => visible.has(folder.id) || (root === 'DATA' && folder.rootKind !== 'OUTPUTS' && !folder.parentId && !folders.some(child => child.parentId === folder.id) && !filteredResources.value.some(resource => resource.folderId === folder.id)) || (root === 'OUTPUT' && folder.rootKind === 'OUTPUTS'))
}
function rootFolders(root: UiRoot) { return foldersForRoot(root).filter(item => !item.parentId) }
function rootCount(root: UiRoot) { return rootResources(root).length }
function directRootResources(_root: UiRoot) { return [] }
function iconFor(item: WorkspaceResource) {
  if (item.resourceType === 'DATA_FILE' || item.resourceType === 'DATASET') return FileSpreadsheet
  if (item.resourceType === 'API_CONNECTION') return Braces
  if (item.resourceType === 'DATABASE_CONNECTION') return Database
  if (item.resourceType === 'WEB_URL') return Globe2
  if (item.resourceType === 'DELIVERABLE') return FileOutput
  return item.resourceType === 'OFFICE_FILE' ? File : FileText
}
function resourceStatus(item: WorkspaceResource) {
  if (item.resourceType === 'WEB_URL') return item.status === 'VERIFIED' ? '已验证并保存快照' : '尚未验证内容'
  const labels: Record<string, string> = { READY: '可用', COMPLETED: '已完成', SUCCEEDED: '已完成', VERIFIED: '已验证', FAILED: '处理失败', PROCESSING: '处理中', NOT_TESTED: '尚未检查', DRAFT: '待完善' }
  return labels[item.status] ?? item.status
}
function sourceTypeLabel(item: WorkspaceResource) {
  const labels: Record<WorkspaceResource['resourceType'], string> = {
    DATABASE_CONNECTION: '数据库', API_CONNECTION: 'API', WEB_URL: '网页', DATASET: '采集数据', DATA_FILE: '数据文件',
    OFFICE_FILE: '办公文件', KNOWLEDGE_FILE: '资料文件', DELIVERABLE: '成果',
  }
  return labels[item.resourceType]
}
function shortResourceId(id: string) { return id.replace(/[^a-zA-Z0-9]/g, '').slice(-6).toUpperCase() }
function duplicateResourceName(item: WorkspaceResource) { return (workspace.value?.resources ?? []).filter(resource => resource.name.trim().toLocaleLowerCase() === item.name.trim().toLocaleLowerCase()).length > 1 }
function resourceRootLabel(item: WorkspaceResource) { return item.group === 'DATA' ? '数据' : item.group === 'OUTPUT' ? '输出件' : '资料' }
function resourcePath(item: WorkspaceResource) {
  if (!item.folderId) return resourceRootLabel(item)
  const folder = workspace.value?.folders.find(candidate => candidate.id === item.folderId)
  return folder ? `${resourceRootLabel(item)} / ${folderPath(folder)}` : resourceRootLabel(item)
}
function resourceLocator(item: WorkspaceResource) {
  const updated = new Date(item.updatedAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  return `${resourcePath(item)} · 第 ${item.currentVersion} 版 · ${updated}${duplicateResourceName(item) ? ` · 编号 ${shortResourceId(item.id)}` : ''}`
}
function resourceTabTitle(item: WorkspaceResource) { return duplicateResourceName(item) ? `${item.name} · ${shortResourceId(item.id)}` : item.name }
function openSourceAdd() { openAdd('DATA') }
function startSourceUpload() {
  uploadAccept.value = '.csv,.tsv,.xls,.xlsx,.xlsm,.parquet,.pdf,.doc,.docx,.ppt,.pptx,.txt,.md,.png,.jpg,.jpeg'
  void nextTick(() => uploadInput.value?.click())
}
function openTab(tab: Tab) {
  const existing = tabs.value.find(item => item.id === tab.id)
  if (!existing) tabs.value.push(tab)
  else if (tab.resource) existing.resource = tab.resource
  activeTabId.value = tab.id
}
function openResource(resource: WorkspaceResource) {
  openTab({ id: `resource-${resource.id}`, title: resourceTabTitle(resource), kind: 'resource', resource })
}
function openCitationSource(citation: CitationSource) {
  const resource = workspace.value?.resources.find(item => item.id === citation.resource_id)
  if (!resource) { notice.value = '来源文件已更新或不在当前项目中'; return }
  openResource(resource)
  const labels: Record<string, string> = { page: '页码', slide: '幻灯片', paragraph: '段落', table: '表格', sheet: '工作表', rows: '行', row: '行', column: '字段', url: '网址', start_seconds: '开始秒数' }
  const location = Object.entries(citation.location || {}).filter(([key, value]) => key !== 'type' && value !== null && value !== '').map(([key, value]) => `${labels[key] || key} ${value}`).join(' · ')
  notice.value = `已打开引用来源${location ? ` · ${location}` : ''}`
}
async function openGeneratedDeliverable(deliverableId: string) {
  let resource: WorkspaceResource | undefined
  for (let attempt = 0; attempt < 4 && !resource; attempt += 1) {
    await loadWorkspace()
    resource = workspace.value?.resources.find(item => item.id === deliverableId)
    if (!resource && attempt < 3) await new Promise(resolve => window.setTimeout(resolve, 250 * (attempt + 1)))
  }
  if (resource) {
    expandedRoots.value.OUTPUT = true
    expandedBuckets.value.outputs = true
    openResource(resource)
    notice.value = `已生成并打开“${resource.name}”`
  } else notice.value = '输出件已经生成，但项目目录暂时没有同步完成'
}
async function openWorkflowResource(resourceId: string) {
  await loadWorkspace()
  const resource = workspace.value?.resources.find(item => item.id === resourceId)
  if (resource) openResource(resource)
  else notice.value = '中间结果已保存，但项目目录暂时没有同步完成'
}
function openData() { openTab({ id: 'data', title: '数据采集', kind: 'data' }) }
function dragResource(event: DragEvent, resource: WorkspaceResource) { sourceDropActive.value = false; if (!event.dataTransfer) return; event.dataTransfer.effectAllowed = 'copy'; event.dataTransfer.setData('application/x-finflow-resource', JSON.stringify(resource)) }
function storageRoot(root: WorkspaceRootKind | UiRoot): WorkspaceRootKind { return root === 'DATA' || root === 'KNOWLEDGE' ? 'FILES' : root === 'OUTPUT' ? 'OUTPUTS' : root }
function startFolder(root: WorkspaceRootKind | UiRoot, parentId?: string) { folderEditingId.value = ''; folderName.value = ''; folderRoot.value = storageRoot(root); folderParentId.value = parentId; folderFormOpen.value = true }
function addSubfolder(parent: WorkspaceFolder) { startFolder(parent.rootKind, parent.id) }
function openAdd(root: UiRoot, folderId?: string, bucketId = '') {
  if (root === 'OUTPUT') { startFolder(root, folderId); return }
  addTargetRoot.value = root; addTargetFolderId.value = folderId; addTargetBucket.value = bucketId
  addPanelMode.value = 'choose'; addPanelOpen.value = true
}
function chooseAdd(kind: 'upload-data' | 'upload-knowledge' | 'database' | 'api' | 'url' | 'paste' | 'folder') {
  if (kind === 'folder') { addPanelOpen.value = false; startFolder(addTargetRoot.value, addTargetFolderId.value); return }
  if (kind.startsWith('upload')) {
    uploadAccept.value = kind === 'upload-data' ? '.csv,.tsv,.xls,.xlsx,.xlsm,.parquet' : '.pdf,.doc,.docx,.ppt,.pptx,.txt,.md,.png,.jpg,.jpeg'
    addPanelOpen.value = false
    void nextTick(() => uploadInput.value?.click())
    return
  }
  if (kind === 'url') { urlForm.name = ''; urlForm.url = 'https://'; addPanelMode.value = 'url'; return }
  if (kind === 'paste') {
    Object.assign(pasteForm, { name: '', sourceAgent: '', sourceQuestion: '', format: 'AUTO', content: '' })
    pastePreview.value = null; pasteError.value = ''; addPanelMode.value = 'paste'; return
  }
  connectionForm.name = ''; connectionForm.connectionText = ''; connectionForm.host = 'localhost'; connectionForm.port = '5432'; connectionForm.database = 'database'; connectionForm.parameters = ''; connectionForm.username = ''; connectionForm.password = ''; connectionForm.format = 'json'; connectionRecognition.value = ''; connectionError.value = ''; connectionPasswordVisible.value = false
  connectionForm.sourceType = kind === 'api' ? 'HTTP_API' : 'POSTGRESQL'
  connectionForm.jdbcUrl = kind === 'api' ? 'https://' : 'jdbc:postgresql://localhost:5432/database'
  addPanelMode.value = 'connection'
}
function pasteRequest(confirmed = false) {
  return { name: pasteForm.name.trim(), sourceAgent: pasteForm.sourceAgent.trim(), sourceQuestion: pasteForm.sourceQuestion.trim(), format: pasteForm.format, content: pasteForm.content, confirmed }
}
async function previewPastedContent() {
  if (!props.project) return
  pasteError.value = ''
  if (!pasteForm.name.trim() || !pasteForm.content.trim()) { pasteError.value = '请填写来源名称并粘贴内容'; return }
  savingContent.value = true
  try { pastePreview.value = await api.previewPasteImport(props.project.id, pasteRequest()) }
  catch (reason) { pastePreview.value = null; pasteError.value = reason instanceof Error ? reason.message : '内容没有识别成功' }
  finally { savingContent.value = false }
}
async function savePastedContent() {
  if (!props.project || !pastePreview.value) return
  savingContent.value = true; pasteError.value = ''
  try {
    const imported = await api.importPastedContent(props.project.id, pasteRequest(true))
    addPanelOpen.value = false; await loadWorkspace()
    if (addTargetFolderId.value) {
      const resource = workspace.value?.resources.find(item => item.id === imported.resource.id)
      if (resource) { await api.moveWorkspaceResource(props.project.id, resource, addTargetFolderId.value); await loadWorkspace() }
    }
    const resource = workspace.value?.resources.find(item => item.id === imported.resource.id)
    notice.value = pastePreview.value.structured ? '外部结果已核对并加入数据' : '外部结果已作为资料加入项目'
    if (resource) openResource(resource)
  } catch (reason) { pasteError.value = reason instanceof Error ? reason.message : '内容没有保存' }
  finally { savingContent.value = false }
}
function connectionTypeChanged() {
  if (connectionForm.sourceType === 'DUCKDB') { connectionForm.host = ''; connectionForm.port = ''; connectionForm.database = '/path/to/data.duckdb' }
  else { connectionForm.host ||= 'localhost'; connectionForm.port = defaultDatabasePort(connectionForm.sourceType as DatabaseSourceType); if (connectionForm.database.startsWith('/')) connectionForm.database = 'database' }
  connectionRecognition.value = ''
}
function recognizeConnectionAddress(raw = connectionForm.connectionText) {
  if (connectionForm.sourceType === 'HTTP_API') return false
  const parsed = parseDatabaseConnection(raw, connectionForm.sourceType as DatabaseSourceType)
  if (!parsed) { connectionRecognition.value = '没有识别出完整连接信息，请检查连接串或手动填写'; return false }
  connectionForm.sourceType = parsed.sourceType
  connectionForm.jdbcUrl = parsed.jdbcUrl
  connectionForm.host = parsed.host
  connectionForm.port = parsed.port
  connectionForm.database = parsed.database
  connectionForm.parameters = parsed.parameters
  if (parsed.username) connectionForm.username = parsed.username
  if (parsed.password) connectionForm.password = parsed.password
  if (!connectionForm.name.trim() && parsed.database) connectionForm.name = parsed.database
  connectionRecognition.value = `已识别 ${parsed.sourceType}，请确认下方各项信息`
  return true
}
function pasteConnectionAddress(event: ClipboardEvent) {
  const value = event.clipboardData?.getData('text') ?? ''
  if (!value) return
  connectionForm.connectionText = value
  recognizeConnectionAddress(value)
  event.preventDefault()
}
async function saveConnection() {
  const jdbcUrl = connectionForm.sourceType === 'HTTP_API' ? connectionForm.jdbcUrl.trim() : buildDatabaseJdbcUrl({ sourceType: connectionForm.sourceType as DatabaseSourceType, host: connectionForm.host, port: connectionForm.port, database: connectionForm.database, parameters: connectionForm.parameters })
  connectionError.value = ''
  if (!props.project) { connectionError.value = '当前项目尚未就绪，请重新选择项目'; return }
  if (!connectionForm.name.trim()) { connectionError.value = '请填写连接名称'; return }
  if (connectionForm.sourceType === 'HTTP_API' && !/^https?:\/\//i.test(jdbcUrl)) { connectionError.value = '请填写完整的 HTTP 或 HTTPS 接口地址'; return }
  if (connectionForm.sourceType !== 'HTTP_API' && connectionForm.sourceType !== 'DUCKDB' && !connectionForm.host.trim()) { connectionError.value = '请确认主机地址'; return }
  if (connectionForm.sourceType !== 'HTTP_API' && !connectionForm.database.trim()) { connectionError.value = connectionForm.sourceType === 'DUCKDB' ? '请填写 DuckDB 文件路径' : '请确认数据库名'; return }
  savingContent.value = true
  let createdId = ''
  try {
    const created = await api.createConnection({ projectId: props.project.id, name: connectionForm.name.trim(), sourceType: connectionForm.sourceType, jdbcUrl, username: connectionForm.sourceType === 'HTTP_API' ? '' : connectionForm.username.trim(), secretRef: connectionForm.password, options: connectionForm.sourceType === 'HTTP_API' ? { format: connectionForm.format, method: 'GET' } : {} })
    createdId = created.id
    const checked = await api.testConnection(created.id)
    if (!checked.success) throw new Error(checked.message || '无法连接到数据源')
    addPanelOpen.value = false; await loadWorkspace(); notice.value = `${connectionForm.sourceType === 'HTTP_API' ? '数据服务' : '数据库'}连接成功（${checked.latencyMs} ms）`
    const resource = workspace.value?.resources.find(item => item.id === created.id); if (resource) openResource(resource)
  } catch (reason) {
    if (createdId) { try { await api.deleteConnection(createdId) } catch { /* Keep the original connection error visible. */ } }
    connectionError.value = `连接失败：${reason instanceof Error ? reason.message : '请检查地址、账号和网络后重试'}`
  }
  finally { savingContent.value = false }
}
async function saveUrl() {
  if (!props.project || !urlForm.name.trim()) { notice.value = '请填写网站名称'; return }
  try { const parsed = new URL(urlForm.url); if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error() } catch { notice.value = '请输入有效的 http 或 https 网站地址'; return }
  savingContent.value = true
  try {
    const workflow = await api.getProjectWorkflow(props.project.id)
    const id = `link_${crypto.randomUUID().slice(0, 12)}`
    const node: WorkflowNode = { id, type: 'LINK_INPUT', name: urlForm.name.trim(), x: 80 + (workflow.nodes.length % 3) * 260, y: 80 + Math.floor(workflow.nodes.length / 3) * 150, config: { title: urlForm.name.trim(), url: urlForm.url.trim() } }
    await api.saveProjectWorkflow(props.project.id, { name: workflow.name, description: workflow.description, nodes: [...workflow.nodes, node], edges: workflow.edges, executionMode: workflow.executionMode, schedule: workflow.schedule, expectedVersion: workflow.currentVersion })
    addPanelOpen.value = false; await loadWorkspace()
    const resource = workspace.value?.resources.find(item => item.id === id)
    if (resource && addTargetFolderId.value) { await api.moveWorkspaceResource(props.project.id, resource, addTargetFolderId.value); await loadWorkspace() }
    notice.value = '网站地址已加入资料'; if (resource) openResource(workspace.value?.resources.find(item => item.id === id) ?? resource)
  } catch (reason) { notice.value = reason instanceof Error ? reason.message : '网站地址没有保存' }
  finally { savingContent.value = false }
}
function renameFolder(folder: WorkspaceFolder) { folderEditingId.value = folder.id; folderName.value = folder.name; folderRoot.value = folder.rootKind; folderParentId.value = folder.parentId; folderFormOpen.value = true }
async function saveFolder() {
  if (!props.project || !folderName.value.trim()) { notice.value = '请填写目录名称'; return }
  try {
    const body = { name: folderName.value.trim(), rootKind: folderRoot.value, parentId: folderParentId.value }
    if (folderEditingId.value) await api.updateWorkspaceFolder(props.project.id, folderEditingId.value, body)
    else await api.createWorkspaceFolder(props.project.id, body)
    folderFormOpen.value = false; notice.value = folderEditingId.value ? '目录已更新' : '目录已创建'; await loadWorkspace()
  } catch (reason) { notice.value = reason instanceof Error ? reason.message : '目录没有保存' }
}
async function deleteFolder(folder: WorkspaceFolder) {
  if (!props.project || !await confirmAction(`删除目录“${folder.name}”？仅空目录可以删除。`)) return
  try { await api.deleteWorkspaceFolder(props.project.id, folder.id); notice.value = '目录已删除'; await loadWorkspace() }
  catch (reason) { notice.value = reason instanceof Error ? reason.message : '目录没有删除' }
}
function startMoveResource(resource: WorkspaceResource) { movingResource.value = resource; targetFolderId.value = resource.folderId ?? '' }
function folderPath(folder: WorkspaceFolder) {
  const names = [folder.name]; let parentId = folder.parentId
  while (parentId) { const parent = workspace.value?.folders.find(item => item.id === parentId); if (!parent) break; names.unshift(parent.name); parentId = parent.parentId }
  return names.join(' / ')
}
const moveFolderOptions = computed(() => (workspace.value?.folders ?? []).filter(item => item.rootKind === movingResource.value?.rootKind).map(item => ({ id: item.id, label: folderPath(item) })))
async function moveResource() {
  if (!props.project || !movingResource.value || movingBusy.value) return
  const projectId = props.project.id
  const selected = bulkMoving.value ? (workspace.value?.resources ?? []).filter(r => selectedResources.value.includes(r.id)) : [movingResource.value]
  movingBusy.value = true
  const failures: string[] = []
  try {
    for (const item of selected) {
      try { await api.moveWorkspaceResource(projectId, item, targetFolderId.value || undefined) }
      catch (reason) { failures.push(`${item.name}：${reason instanceof Error ? reason.message : '移动失败'}`) }
    }
    movingResource.value = null; bulkMoving.value = false
    notice.value = `已移动 ${selected.length - failures.length} 项${failures.length ? `；失败：${failures.join('；')}` : ''}`; await loadWorkspace()
  } finally { movingBusy.value = false }
}
async function openProjectManager() {
  projectManagerOpen.value = true
  const results = await Promise.all(projectStore.projects.map(async project => { try { const item = await api.getProjectWorkspace(project.id); return [project.id, { resources: item.resources.length }] as const } catch { return [project.id, { resources: 0 }] as const } }))
  projectStats.value = Object.fromEntries(results)
}
function newProject() { editingProjectId.value = ''; projectName.value = ''; projectDescription.value = ''; projectFormOpen.value = true }
function editProject(project: Project) { editingProjectId.value = project.id; projectName.value = project.name; projectDescription.value = project.description; projectFormOpen.value = true }
async function deleteProject(project: Project) {
  if (!await confirmAction(`删除项目“${project.name}”？项目将从个人空间移除。`)) return
  try { await projectStore.delete(project.id); projectManagerOpen.value = false; tabs.value = [{ id: 'home', title: '我的工作', kind: 'home' }]; activeTabId.value = 'home' }
  catch (reason) { notice.value = reason instanceof Error ? reason.message : '项目没有删除' }
}
async function saveProject() {
  if (!projectName.value.trim()) { notice.value = '请填写项目名称'; return }
  savingProject.value = true
  try { if (editingProjectId.value) await projectStore.update(editingProjectId.value, projectName.value, projectDescription.value); else await projectStore.create(projectName.value, projectDescription.value); projectFormOpen.value = false; projectManagerOpen.value = false; tabs.value = [{ id: 'home', title: '我的工作', kind: 'home' }]; activeTabId.value = 'home' }
  catch (reason) { notice.value = reason instanceof Error ? reason.message : '项目没有保存' } finally { savingProject.value = false }
}
function selectProject(project: Project) { projectStore.select(project); projectManagerOpen.value = false; tabs.value = [{ id: 'home', title: '我的工作', kind: 'home' }]; activeTabId.value = 'home' }
function closeTab(tab: Tab) {
  if (tab.id === 'home') return
  const index = tabs.value.findIndex(item => item.id === tab.id)
  tabs.value.splice(index, 1)
  if (activeTabId.value === tab.id) activeTabId.value = tabs.value[Math.max(0, index - 1)]?.id ?? 'home'
}
async function uploadFiles(files: File[]) {
  if (!props.project || files.length === 0) return
  if (uploadingFiles.value) { notice.value = '上一批文件还在上传，请稍候'; return }
  const validationError = uploadSelectionError(files)
  if (validationError) { notice.value = validationError; if (uploadInput.value) uploadInput.value.value = ''; return }
  uploadingFiles.value = true
  const uploaded = []
  try {
    for (const [index, file] of files.entries()) {
      notice.value = `正在上传 ${index + 1}/${files.length}：${file.name}`
      uploaded.push(await api.uploadFile(props.project.id, file))
    }
    await loadWorkspace()
    if (addTargetFolderId.value) {
      for (const file of uploaded) { const resource = workspace.value?.resources.find(item => item.id === file.id); if (resource) await api.moveWorkspaceResource(props.project.id, resource, addTargetFolderId.value) }
      await loadWorkspace()
    }
    notice.value = files.length === 1 ? '文件已加入项目' : `${files.length} 个文件已加入项目`
    const resource = workspace.value?.resources.find(item => item.id === uploaded[0]?.id); if (resource && uploaded.length === 1) openResource(resource)
  }
  catch (reason) {
    if (uploaded.length) await loadWorkspace()
    const detail = reason instanceof Error ? reason.message : '文件没有上传'
    notice.value = uploaded.length ? `已上传 ${uploaded.length}/${files.length} 个文件，后续上传停止：${detail}` : detail
  }
  finally { uploadingFiles.value = false; if (uploadInput.value) uploadInput.value.value = '' }
}
async function upload(event: Event) {
  await uploadFiles(Array.from((event.target as HTMLInputElement).files ?? []))
}
function updateSourceDropState(event: DragEvent) {
  sourceDropActive.value = sourceDropAccepts(Array.from(event.dataTransfer?.types ?? []))
}
function leaveSourceDropArea(event: DragEvent) {
  const container = event.currentTarget as Node | null
  const next = event.relatedTarget
  if (!container || !(next instanceof Node) || !container.contains(next)) sourceDropActive.value = false
}
function resetSourceDropState() { sourceDropActive.value = false }
async function dropSource(event: DragEvent) {
  resetSourceDropState()
  const files = Array.from(event.dataTransfer?.files ?? [])
  if (files.length) { await uploadFiles(files); return }
  const value = event.dataTransfer?.getData('text/plain')?.trim() ?? ''
  if (!/^https?:\/\//i.test(value)) return
  try { urlForm.name = new URL(value).hostname.replace(/^www\./, '') } catch { urlForm.name = '网站资料' }
  urlForm.url = value
  addTargetRoot.value = 'KNOWLEDGE'
  addTargetFolderId.value = undefined
  addTargetBucket.value = ''
  addPanelMode.value = 'url'
  addPanelOpen.value = true
}
async function useAsInput(resource: WorkspaceResource) {
  if (!props.project || props.project.id !== resource.projectId) return
  const work = selectedWorkId.value === '__new__' ? undefined : works.value.find(w => w.sessionId === selectedWorkId.value) ?? works.value[0]
  if (work) { await selectWork(work.sessionId); if (assistant.sessionId !== work.sessionId) return }
  else { if (assistant.canInterrupt) { notice.value = '请等待当前任务结束，再添加新工作输入'; return }; await assistant.createNewSession(props.project.id) }
  if (props.project?.id !== resource.projectId) return
  assistant.openWithSuggestion(`请将“${resource.name}”第 ${resource.currentVersion} 版加入${work ? `工作「${work.goal}」（${work.id}）` : '新工作'}的输入。先核对内容和版本，登记输入后再继续。`, {
    type: resource.resourceType, resourceId: resource.id, range: [resource.name],
  })
}
async function deleteResource(resource: WorkspaceResource) {
  const references = works.value.filter(w => w.stages.some(s => [...s.inputs, ...s.outputs].some(r => r.resourceId === resource.id)))
  if (!await confirmAction(`删除“${resource.name}”第 ${resource.currentVersion} 版及全部历史版本？不可撤销。\n受影响工作：${references.map(w => w.goal).join('、') || '未发现已登记引用'}`)) return
  try {
    await removeResource(resource)
    notice.value = '内容已删除'
    await loadWorkspace()
  } catch (reason) { notice.value = reason instanceof Error ? reason.message : '内容没有删除' }
}
async function removeResource(resource: WorkspaceResource) {
    if (resource.resourceType === 'WEB_URL') { const workflow = await api.getProjectWorkflow(resource.projectId); await api.saveProjectWorkflow(resource.projectId, { name: workflow.name, description: workflow.description, nodes: workflow.nodes.filter(node => node.id !== resource.id), edges: workflow.edges.filter(edge => edge.source !== resource.id && edge.target !== resource.id), executionMode: workflow.executionMode, schedule: workflow.schedule, expectedVersion: workflow.currentVersion }) }
    else if (resource.resourceType === 'DATABASE_CONNECTION' || resource.resourceType === 'API_CONNECTION') await api.deleteConnection(resource.id)
    else if (resource.resourceType === 'DATASET') await api.deleteExtract(resource.id)
    else if (resource.resourceType === 'DELIVERABLE') await api.deleteDeliverable(resource.id)
    else await api.deleteFile(resource.id)
    tabs.value = tabs.value.filter(tab => tab.resource?.id !== resource.id)
    if (!tabs.value.some(tab => tab.id === activeTabId.value)) activeTabId.value = 'home'
}
type AssistantWorkbenchAction = { type?: string; projectId?: string; resourceId?: string; workflowId?: string; workflowName?: string; goal?: string; refreshWorkspace?: boolean }
let assistantActionSequence = 0
async function applyAssistantAction(detail: AssistantWorkbenchAction) {
  if (!detail?.type) return
  const sequence = ++assistantActionSequence
  if (detail.type === 'REFRESH_WORKSPACE' && detail.projectId && detail.projectId !== props.project?.id) return
  if (detail.type === 'OPEN_PROJECT' && detail.projectId) {
    if (projectStore.current?.id !== detail.projectId) await projectStore.refresh(detail.projectId)
    await nextTick()
    if (sequence !== assistantActionSequence || props.project?.id !== detail.projectId) return
    if (detail.refreshWorkspace) await loadWorkspace(detail.projectId)
    notice.value = 'Agent 已创建并打开新项目'
    return
  }
  if (detail.projectId && detail.projectId !== props.project?.id) {
    await projectStore.refresh(detail.projectId)
    await nextTick()
    if (sequence !== assistantActionSequence || props.project?.id !== detail.projectId) return
  }
  const targetProjectId = detail.projectId || props.project?.id
  if (!targetProjectId || targetProjectId !== props.project?.id) return
  if (detail.refreshWorkspace) await loadWorkspace(targetProjectId)
  if (sequence !== assistantActionSequence || workspace.value?.project.id !== targetProjectId) return
  if (detail.type === 'OPEN_WORKFLOW') {
    const namedWorkflow = workspace.value?.workflows?.find(item =>
      (!detail.workflowName ? false : item.name.includes(detail.workflowName)) ||
      (!detail.goal ? false : detail.goal.includes(item.name)))
    const workflowId = detail.workflowId || namedWorkflow?.id
    if (!workflowId || !workspace.value?.workflows.some(item => item.id === workflowId)) return
    notice.value = '历史执行记录已更新，可在工作的执行详情查看'
  }
  else if (detail.type === 'OPEN_DATA') openData()
  else if (detail.type === 'OPEN_HOME') activeTabId.value = 'home'
  else if (detail.type === 'OPEN_RESOURCE' && detail.resourceId) {
    const resource = workspace.value?.resources.find(item => item.id === detail.resourceId)
    if (resource) openResource(resource)
  }
}
function handleAssistantAction(event: Event) {
  void applyAssistantAction((event as CustomEvent<AssistantWorkbenchAction>).detail)
}
onMounted(() => {
  restoreSidebarWidth()
  assistant.open = true
  window.addEventListener('resize', restoreSidebarWidth)
  window.addEventListener('dragend', resetSourceDropState, true)
  window.addEventListener('drop', resetSourceDropState, true)
  window.addEventListener('finflow:assistant-action', handleAssistantAction)
})
onBeforeUnmount(() => {
  stopSidebarResize()
  window.removeEventListener('resize', restoreSidebarWidth)
  window.removeEventListener('dragend', resetSourceDropState, true)
  window.removeEventListener('drop', resetSourceDropState, true)
  window.removeEventListener('finflow:assistant-action', handleAssistantAction)
})
watch(activeTab, tab => {
  if (!tab) return
  const page = tab.kind === 'data' ? 'data-collection' : tab.kind === 'resource' ? 'resource' : 'project-home'
  const selection = tab.resource ? { type: tab.resource.resourceType, resourceId: tab.resource.id, range: [tab.resource.name] } : undefined
  assistant.setWorkbenchContext(page, tab.title, selection)
}, { immediate: true })
watch(() => props.project?.id, (id, previousId) => {
  if (id !== previousId) {
    workspaceLoadSequence += 1
    workspace.value = null
    works.value = []; selectedWorkId.value = ''
    loadingWorkspace.value = Boolean(id)
    tabs.value = [{ id: 'home', title: '我的工作', kind: 'home' }]
    activeTabId.value = 'home'
  }
  void loadWorkspace(id)
}, { immediate: true })
watch(() => [props.loading, props.project?.id] as const, ([loading, projectId]) => {
  if (!loading && !projectId) projectManagerOpen.value = true
}, { immediate: true })
</script>

<template>
  <div class="project-shell" :class="{ 'assistant-open': assistant.open, 'sidebar-resizing': resizingSidebar, 'focus-mode': focusMode, 'navigation-open': navigationOpen }" :style="{ '--resource-sidebar-width': `${sidebarWidth}px` }">
    <aside class="resource-sidebar">
      <div class="workbench-brand"><span>F</span><div><strong>FinBTP Studio</strong><small>{{ account }}</small></div><button class="workbench-logout" type="button" title="退出登录" @click="emit('logout')"><LogOut :size="16"/></button></div>
      <button class="project-switcher" type="button" @click="openProjectManager"><div><small>当前项目</small><strong>{{ project?.name ?? '正在打开' }}</strong></div><ChevronDown :size="15"/></button>
      <section class="work-navigator"><input v-model="workSearch" class="work-search" aria-label="搜索工作" placeholder="搜索工作"><header><Files :size="15"/><strong>我的工作</strong><span>{{ works.length }}</span><button type="button" title="开始一项工作" :disabled="assistant.canInterrupt" @click="startAgentWork"><Plus :size="13"/>开始工作</button></header><div><article v-for="item in visibleWorks" :key="item.id" :class="{ active: activeTabId === 'home' && (selectedWorkId === item.sessionId || !selectedWorkId && works[0]?.id === item.id) }"><button type="button" @click="selectWork(item.sessionId)"><span :title="item.goal">{{ item.title || item.stages.at(-1)?.title || '未命名工作' }}</span><small>{{ workStatusLabels[workStatus(item)] ?? item.status }}</small></button></article></div></section>
      <section class="source-hub" :class="{ 'drop-active': sourceDropActive }" @dragenter.prevent="updateSourceDropState" @dragover.prevent="updateSourceDropState" @dragleave="leaveSourceDropArea" @drop.prevent="dropSource">
        <header class="source-hub-header"><div><Database :size="16"/><strong>来源</strong><span>{{ sourceResources.length }}</span></div><div><button v-if="internalFiles.size" type="button" :title="showInternalFiles ? '收起内部文件' : `显示内部文件（${internalFiles.size}）`" :aria-label="showInternalFiles ? '收起内部文件' : '显示内部文件'" :aria-pressed="showInternalFiles" :disabled="deletingResources" @click="showInternalFiles = !showInternalFiles"><EyeOff v-if="showInternalFiles" :size="15"/><Eye v-else :size="15"/></button><button type="button" :title="bulkMode ? '结束选择' : '批量管理'" :aria-label="bulkMode ? '结束选择' : '批量管理'" :aria-pressed="bulkMode" :disabled="deletingResources || movingBusy" @click="bulkMode = !bulkMode; selectedResources = []"><X v-if="bulkMode" :size="15"/><ListChecks v-else :size="15"/></button><button type="button" title="添加来源" aria-label="添加来源" @click="openSourceAdd"><Plus :size="15"/></button></div></header>
        <button v-if="!workspace?.resources.length || sourceDropActive || uploadingFiles" class="source-drop-zone" type="button" :disabled="uploadingFiles" @click="startSourceUpload"><Upload :size="16"/><span><strong>{{ uploadingFiles ? '正在上传文件' : '拖入文件，可一次选择多个' }}</strong><small>最多 {{ MAX_UPLOAD_FILES }} 个 · 单个 {{ formatUploadSize(MAX_UPLOAD_FILE_BYTES) }} · 合计 {{ formatUploadSize(MAX_UPLOAD_SELECTION_BYTES) }}</small><small>也可点击选择文件，或直接拖入网址</small></span></button>
        <label class="source-search"><Search :size="15"/><input v-model="search" placeholder="搜索来源"></label>
        <nav class="source-filters" aria-label="来源类型"><button v-for="filter in sourceFilters" :key="filter.id" type="button" :class="{ active: sourceFilter === filter.id }" @click="sourceFilter = filter.id">{{ filter.label }}</button></nav>
        <div v-if="bulkMode" class="bulk-toolbar" aria-label="批量操作"><label><input type="checkbox" aria-label="全选当前筛选" :checked="sourceResources.length > 0 && sourceResources.every(r => selectedResources.includes(r.id))" :indeterminate="sourceResources.some(r => selectedResources.includes(r.id)) && !sourceResources.every(r => selectedResources.includes(r.id))" :disabled="deletingResources || movingBusy || !sourceResources.length" @change="selectedResources = ($event.target as HTMLInputElement).checked ? [...new Set([...selectedResources, ...sourceResources.map(r => r.id)])] : selectedResources.filter(id => !sourceResources.some(r => r.id === id))">{{ deletingResources ? '正在删除…' : `已选 ${selectedResources.length} 项` }}</label><button class="danger" type="button" title="删除所选来源" aria-label="删除所选来源" :disabled="!selectedResources.length || deletingResources || movingBusy" @click="deleteSelectedResources"><Trash2 :size="16"/></button></div><div class="source-list">
          <button v-for="item in sourceResources" :key="item.id" draggable="true" type="button" :class="{ active: activeTab?.resource?.id === item.id }" :title="`${item.name}\n${resourceLocator(item)}`" @dragstart="dragResource($event, item)" @click="chooseResource(item)" :aria-pressed="bulkMode ? selectedResources.includes(item.id) : undefined">
            <span class="source-icon"><span v-if="bulkMode" class="selection-box" :class="{ selected: selectedResources.includes(item.id) }" aria-hidden="true"><Check v-if="selectedResources.includes(item.id)" :size="12"/></span><component v-else :is="iconFor(item)" :size="16"/></span><span class="source-copy"><strong>{{ item.name }}</strong><small>{{ internalFiles.has(item.id) ? '内部文件' : works.some(w => w.stages.some(s => s.outputs.some(o => o.resourceId === item.id))) ? '工序产物' : sourceTypeLabel(item) }} · {{ resourceLocator(item) }}</small></span><i :class="{ ready: ['READY', 'COMPLETED', 'SUCCEEDED', 'VERIFIED'].includes(item.status) }" :title="resourceStatus(item)"></i>
          </button>
          <div v-if="!sourceResources.length" class="source-empty"><Search :size="19"/><strong>没有匹配的来源</strong><span>调整筛选条件，或添加新的文件和连接。</span></div>
        </div>
      </section>
      <input ref="uploadInput" hidden multiple type="file" :accept="uploadAccept" @change="upload">
    </aside>
    <div class="resource-sidebar-resizer" role="separator" aria-orientation="vertical" aria-label="调整左侧来源区宽度" :aria-valuenow="sidebarWidth" aria-valuemin="220" aria-valuemax="440" tabindex="0" title="拖动调整宽度，双击恢复默认" @pointerdown="startSidebarResize" @dblclick="resetSidebarWidth" @keydown.left.prevent="resizeSidebar(sidebarWidth - 20)" @keydown.right.prevent="resizeSidebar(sidebarWidth + 20)" @keydown.home.prevent="resizeSidebar(220)" @keydown.end.prevent="resizeSidebar(440)"><span></span></div>

    <main class="project-main" :class="{ 'has-notice': !!notice }">
      <header class="project-topbar"><button class="icon-button navigation-toggle" title="项目导航" aria-label="项目导航" @click="navigationOpen = !navigationOpen"><FolderOpen :size="18"/></button><div><strong>{{ activeTab?.kind === 'home' ? '我的工作' : activeTab?.title }}</strong><small v-if="loadingWorkspace">正在同步项目内容</small><small v-else>{{ project?.name }}</small></div><button class="icon-button" title="专注阅读" aria-label="专注阅读" @click="focusMode = !focusMode; assistant.open = false"><Eye :size="19"/></button></header>
      <nav class="work-tabs"><button v-for="tab in tabs" :key="tab.id" type="button" :class="{ active: tab.id === activeTabId }" @click="activeTabId = tab.id"><span>{{ tab.title }}</span><X v-if="tab.id !== 'home'" :size="13" @click.stop="closeTab(tab)"/></button></nav>
      <p v-if="notice" class="project-notice">{{ notice }}<button type="button" @click="notice = ''"><X :size="14"/></button></p>
      <section class="project-stage">
        <div v-if="loading || (loadingWorkspace && !workspace)" class="resource-state">正在整理项目空间</div>
        <div v-else-if="error" class="resource-state error">{{ error }}</div>
        <AgentWorkOverview v-else-if="activeTab?.kind === 'home' && project" :project="project" :resources="workspace?.resources ?? []" :work-id="selectedWorkId" @works="works = $event" @started="selectedWorkId = $event" @open-resource="openWorkflowResource" @add-input="openSourceAdd" @resources-changed="loadWorkspace" @open-source="openCitationSource" @manage-data="openData" @use-as-input="useAsInput" @delete-resource="deleteResource" />
        <DataView v-else-if="activeTab?.kind === 'data'" :project="project" />
        <ResourceWorkbench v-else-if="activeTab?.resource && activeTab.resource.resourceType !== 'WEB_URL'" :key="`${activeTab.resource.id}-${activeTab.resource.currentVersion}`" :resource="activeTab.resource" :location="resourceLocator(activeTab.resource)" @use-as-input="useAsInput" @manage-data="openData" @delete-resource="deleteResource" @open-source="openCitationSource" @resources-changed="loadWorkspace" />
        <ResourceWorkbench v-for="tab in openWebTabs" v-show="!loading && !loadingWorkspace && !error && activeTabId === tab.id" :key="tab.id" :resource="tab.resource!" :location="resourceLocator(tab.resource!)" @use-as-input="useAsInput" @manage-data="openData" @delete-resource="deleteResource" @open-source="openCitationSource" @resources-changed="loadWorkspace" />
      </section>
    </main>

    <aside v-if="activeTab?.resource" class="context-sidebar"><header><strong>当前内容</strong></header><template v-if="activeTab?.resource"><component :is="iconFor(activeTab.resource)" :size="26"/><h3>{{ activeTab.resource.name }}</h3><dl><dt>位置</dt><dd>{{ resourcePath(activeTab.resource) }}</dd><dt>状态</dt><dd>{{ resourceStatus(activeTab.resource) }}</dd><dt>版本</dt><dd>第 {{ activeTab.resource.currentVersion }} 版</dd><template v-if="duplicateResourceName(activeTab.resource)"><dt>文件编号</dt><dd>{{ shortResourceId(activeTab.resource.id) }}</dd></template></dl><button class="primary-button" type="button" @click="useAsInput(activeTab.resource)"><Plus :size="15"/>用于当前工作</button></template><template v-else><WorkflowIcon :size="26"/><h3>项目空间</h3><p>选中文件或数据后，这里会显示版本与状态。</p></template></aside>
    <AssistantPanel :project="project" />
    <div v-if="addPanelOpen" class="project-form-backdrop" @click.self="addPanelOpen = false"><section class="project-form-panel add-content-panel" :class="{ 'paste-content-panel': addPanelMode === 'paste' }"><header><div><strong>{{ addPanelMode === 'choose' ? '添加来源' : addPanelMode === 'url' ? '添加网站地址' : addPanelMode === 'paste' ? '粘贴外部结果' : connectionForm.sourceType === 'HTTP_API' ? '接入数据服务' : '连接数据库' }}</strong><small v-if="addPanelMode === 'choose'">文件、数据库、API、网页和外部助手结果都可以作为来源</small><small v-else-if="addPanelMode === 'paste'">先识别结构并核对，再加入项目</small></div><button class="icon-button" type="button" title="关闭" @click="addPanelOpen = false"><X :size="17"/></button></header>
      <div v-if="addPanelMode === 'choose'" class="add-source-list"><p>新增资料将保存到项目。需要用于某项工作时，请选择文件并交给该工作的助手。</p>
        <button type="button" @click="chooseAdd('upload-data')"><FileSpreadsheet :size="19"/><span><strong>上传数据文件</strong><small>CSV、Excel、Parquet 等结构化数据</small></span><ChevronRight :size="15"/></button>
        <button type="button" @click="chooseAdd('upload-knowledge')"><Upload :size="19"/><span><strong>上传资料文件</strong><small>PDF、Word、PPT、图片或文本</small></span><ChevronRight :size="15"/></button>
        <button type="button" @click="chooseAdd('database')"><Database :size="19"/><span><strong>连接数据库</strong><small>连接后选择数据表或视图</small></span><ChevronRight :size="15"/></button>
        <button type="button" @click="chooseAdd('api')"><Braces :size="19"/><span><strong>接入 API</strong><small>通过 HTTP 服务读取业务数据</small></span><ChevronRight :size="15"/></button>
        <button type="button" @click="chooseAdd('paste')"><ClipboardPaste :size="19"/><span><strong>粘贴外部助手结果</strong><small>支持表格、CSV、JSON 或文字结论，核对后再使用</small></span><ChevronRight :size="15"/></button>
        <button type="button" @click="chooseAdd('url')"><Globe2 :size="19"/><span><strong>添加网页</strong><small>验证可访问后作为研究资料</small></span><ChevronRight :size="15"/></button>
      </div>
      <div v-else-if="addPanelMode === 'connection'" class="form-stack add-connection-form">
        <template v-if="connectionForm.sourceType === 'HTTP_API'">
          <label><span>名称</span><input v-model="connectionForm.name" autofocus placeholder="例如：经营数据服务"></label>
          <label><span>接口地址</span><input v-model="connectionForm.jdbcUrl" placeholder="https://"></label>
          <label><span>访问令牌或密钥（可选）</span><div class="password-input"><input v-model="connectionForm.password" :type="connectionPasswordVisible ? 'text' : 'password'" autocomplete="new-password" placeholder="在这里填写，保存后仅显示已配置"><button type="button" :title="connectionPasswordVisible ? '隐藏密码' : '显示密码'" :aria-label="connectionPasswordVisible ? '隐藏密码' : '显示密码'" @click="connectionPasswordVisible = !connectionPasswordVisible"><EyeOff v-if="connectionPasswordVisible" :size="16"/><Eye v-else :size="16"/></button></div></label>
          <label><span>返回格式</span><select v-model="connectionForm.format"><option value="json">JSON</option><option value="jsonl">JSON Lines</option><option value="csv">CSV</option></select></label>
        </template>
        <template v-else>
          <label class="connection-import-field"><span>粘贴连接串，自动识别</span><div class="connection-import-control"><ClipboardPaste :size="17"/><input v-model="connectionForm.connectionText" autofocus placeholder="粘贴 JDBC、postgresql:// 或 host=... 连接串" @paste="pasteConnectionAddress"><button type="button" @click="recognizeConnectionAddress()">识别</button></div><small v-if="connectionRecognition" class="field-help" :class="{ 'connection-recognized': connectionRecognition.startsWith('已识别') }">{{ connectionRecognition }}</small><small v-else class="field-help">识别后会填入下方各项，保存前可逐项确认和修改。</small></label>
          <div class="connection-confirm-grid">
            <label><span>连接名称</span><input v-model="connectionForm.name" placeholder="例如：经营数据仓库"></label>
            <label><span>数据库类型</span><select v-model="connectionForm.sourceType" @change="connectionTypeChanged"><option value="POSTGRESQL">PostgreSQL</option><option value="MYSQL">MySQL</option><option value="OPENGAUSS">openGauss</option><option value="GAUSS_DWS">GaussDB(DWS)</option><option value="DUCKDB">DuckDB</option></select></label>
            <template v-if="connectionForm.sourceType !== 'DUCKDB'"><label class="connection-host-field"><span>主机地址</span><input v-model="connectionForm.host" placeholder="db.example.com"></label><label class="connection-port-field"><span>端口</span><input v-model="connectionForm.port" inputmode="numeric" placeholder="5432"></label><label class="wide"><span>数据库名</span><input v-model="connectionForm.database" placeholder="database"></label><label><span>用户名</span><input v-model="connectionForm.username" autocomplete="username"></label><label><span>密码</span><div class="password-input"><input v-model="connectionForm.password" :type="connectionPasswordVisible ? 'text' : 'password'" autocomplete="new-password" placeholder="保存后仅显示已配置"><button type="button" :title="connectionPasswordVisible ? '隐藏密码' : '显示密码'" :aria-label="connectionPasswordVisible ? '隐藏密码' : '显示密码'" @click="connectionPasswordVisible = !connectionPasswordVisible"><EyeOff v-if="connectionPasswordVisible" :size="16"/><Eye v-else :size="16"/></button></div></label><label class="wide"><span>连接参数（可选）</span><input v-model="connectionForm.parameters" placeholder="sslmode=require"></label></template>
            <label v-else class="wide"><span>DuckDB 文件路径</span><input v-model="connectionForm.database" placeholder="/data/analytics.duckdb"></label>
          </div>
        </template>
      </div>
      <div v-else-if="addPanelMode === 'paste'" class="paste-import-layout">
        <div class="form-stack paste-import-form"><div class="paste-meta-grid"><label><span>来源名称</span><input v-model="pasteForm.name" autofocus maxlength="300" placeholder="例如：问数助手返回的未入账明细"></label><label><span>来自哪个助手</span><input v-model="pasteForm.sourceAgent" maxlength="200" placeholder="例如：经营问数助手"></label></div><label><span>当时问了什么（建议填写）</span><input v-model="pasteForm.sourceQuestion" maxlength="2000" placeholder="保留原问题，后续才能判断口径"></label><label><span>粘贴原始结果</span><textarea v-model="pasteForm.content" rows="11" maxlength="5000000" placeholder="可粘贴网页表格、Markdown 表格、CSV、JSON 或文字结论" @input="pastePreview = null"></textarea></label><p class="form-help">表格会转成可计算的数据；纯文字只作为资料。原文、来源、问题、时间和内容指纹都会保留。</p></div>
        <section class="paste-preview" :class="{ empty: !pastePreview }"><template v-if="pastePreview"><header><div><strong>{{ pastePreview.structured ? '识别为可计算数据' : '识别为参考资料' }}</strong><small>{{ pastePreview.detectedFormat }} · {{ pastePreview.structured ? `${pastePreview.rowCount} 行 / ${pastePreview.columns.length} 列` : '不参与自动计算' }}</small></div><span>待确认</span></header><div v-if="pastePreview.structured" class="paste-table-wrap"><table><thead><tr><th v-for="column in pastePreview.columns" :key="column">{{ column }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in pastePreview.rows" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td></tr></tbody></table></div><div v-else class="paste-evidence-note">这段内容没有稳定的行列结构。确认后会保留为原始资料，分析时可引用，但不会假装成数值数据参与汇总。</div><ul v-if="pastePreview.warnings.length" class="paste-warnings"><li v-for="warning in pastePreview.warnings" :key="warning">{{ warning }}</li></ul></template><template v-else><ClipboardPaste :size="24"/><strong>先预览，再加入项目</strong><span>系统会识别结构、展示样例并提示风险，不会直接把外部模型的回答当成事实。</span></template></section>
      </div>
      <div v-else class="form-stack"><label><span>网站名称</span><input v-model="urlForm.name" autofocus placeholder="例如：公司年度报告页面"></label><label><span>网站地址</span><input v-model="urlForm.url" placeholder="https://example.com/report"></label><p class="form-help">保存后可在工作区直接访问，也可以作为工作流的参考输入。</p></div>
      <p v-if="addPanelMode === 'connection' && connectionError" class="connection-save-error">{{ connectionError }}</p><p v-if="addPanelMode === 'paste' && pasteError" class="connection-save-error">{{ pasteError }}</p>
      <footer v-if="addPanelMode !== 'choose'"><button class="secondary-button" type="button" :disabled="savingContent" @click="addPanelMode = 'choose'">返回</button><template v-if="addPanelMode === 'paste'"><button v-if="!pastePreview" class="primary-button" type="button" :disabled="savingContent" @click="previewPastedContent">{{ savingContent ? '正在识别' : '预览并检查' }}</button><button v-else class="primary-button" type="button" :disabled="savingContent" @click="savePastedContent">{{ savingContent ? '正在加入' : '确认无误，加入项目' }}</button></template><button v-else class="primary-button" type="button" :disabled="savingContent" @click="addPanelMode === 'url' ? saveUrl() : saveConnection()">{{ savingContent ? (addPanelMode === 'connection' ? '正在验证连接' : '正在保存') : '保存' }}</button></footer>
    </section></div>
    <div v-if="projectManagerOpen" class="project-manager-backdrop" @click.self="projectManagerOpen = false">
      <section class="project-manager-panel"><header><div><LayoutGrid :size="20"/><div><strong>我的项目</strong><small>管理个人项目和工作流空间</small></div></div><button class="icon-button" type="button" title="关闭" @click="projectManagerOpen = false"><X :size="18"/></button></header><div class="project-manager-toolbar"><span>{{ projectStore.projects.length }} 个项目</span><button class="primary-button" type="button" @click="newProject"><Plus :size="15"/>新建项目</button></div><div class="project-list"><article v-for="item in projectStore.projects" :key="item.id" :class="{ active: item.id === project?.id }"><button class="project-card-main" type="button" @click="selectProject(item)"><span class="project-card-icon"><FolderOpen :size="20"/></span><span><strong>{{ item.name }}</strong><small>{{ item.description || '还没有填写项目说明' }}</small><i>{{ projectStats[item.id]?.resources ?? 0 }} 项内容</i></span></button><div class="project-card-actions"><button class="icon-button" type="button" title="编辑项目" @click="editProject(item)"><Pencil :size="15"/></button><button class="icon-button danger" type="button" title="删除项目" @click="deleteProject(item)"><Trash2 :size="15"/></button></div></article></div></section>
    </div>
    <div v-if="projectFormOpen" class="project-form-backdrop" @click.self="projectFormOpen = false"><section class="project-form-panel"><header><strong>{{ editingProjectId ? '编辑项目' : '新建项目' }}</strong><button class="icon-button" type="button" title="关闭" @click="projectFormOpen = false"><X :size="17"/></button></header><div class="form-stack"><label><span>项目名称</span><input v-model="projectName" maxlength="200" autofocus placeholder="例如：年度预算分析"></label><label><span>项目说明</span><textarea v-model="projectDescription" rows="4" maxlength="2000" placeholder="这个项目准备完成什么工作"></textarea></label></div><footer><button class="secondary-button" type="button" @click="projectFormOpen = false">取消</button><button class="primary-button" type="button" :disabled="savingProject" @click="saveProject">{{ savingProject ? '正在保存' : '保存项目' }}</button></footer></section></div>
    <div v-if="folderFormOpen" class="project-form-backdrop" @click.self="folderFormOpen = false"><section class="project-form-panel compact-panel"><header><strong>{{ folderEditingId ? '重命名目录' : '新建目录' }}</strong><button class="icon-button" type="button" title="关闭" @click="folderFormOpen = false"><X :size="17"/></button></header><div class="form-stack"><label><span>目录名称</span><input v-model="folderName" maxlength="200" autofocus placeholder="例如：月度盘点资料" @keydown.enter="saveFolder"></label><label v-if="!folderEditingId"><span>所在分类</span><select v-model="folderRoot"><option v-for="root in treeRoots" :key="root.id" :value="root.id">{{ root.label }}</option></select></label></div><footer><button class="secondary-button" type="button" @click="folderFormOpen = false">取消</button><button class="primary-button" type="button" @click="saveFolder">保存</button></footer></section></div>
    <div v-if="movingResource" class="project-form-backdrop" @click.self="movingResource = null"><section class="project-form-panel compact-panel"><header><div><strong>移动内容</strong><small>{{ bulkMoving ? `已选择 ${selectedResources.length} 项` : movingResource.name }}</small></div><button class="icon-button" type="button" title="关闭" @click="movingResource = null"><X :size="17"/></button></header><div class="form-stack"><label><span>目标目录</span><select v-model="targetFolderId"><option value="">{{ treeRoots.find(item => item.id === movingResource?.rootKind)?.label }}（根目录）</option><option v-for="folder in moveFolderOptions" :key="folder.id" :value="folder.id">{{ folder.label }}</option></select></label><p v-if="moveFolderOptions.length === 0" class="form-help">这个分类下还没有自定义目录，可以先在左侧点击 + 创建。</p></div><footer><button class="secondary-button" type="button" @click="movingResource = null">取消</button><button class="primary-button" type="button"  :disabled="movingBusy" @click="moveResource">{{ movingBusy ? '正在移动' : '移动' }}</button></footer></section></div>
  </div>
</template>
