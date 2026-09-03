<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Braces, ChevronDown, ChevronRight, Database, File, FileOutput, FileSpreadsheet, FileText, Folder, FolderInput, FolderOpen, FolderPlus, Globe2, LayoutGrid, MoreHorizontal, Pencil, Plus, Search, Server, Trash2, Upload, Workflow as WorkflowIcon, X } from 'lucide-vue-next'
import AssistantPanel from '../components/AssistantPanel.vue'
import ResourceWorkbench from '../components/ResourceWorkbench.vue'
import ResourceTreeFolder from '../components/ResourceTreeFolder.vue'
import { api, type CitationSource, type Project, type ProjectWorkspace, type Workflow, type WorkflowNode, type WorkspaceFolder, type WorkspaceResource, type WorkspaceRootKind } from '../api/client'
import DataView from './DataView.vue'
import WorkflowView from './WorkflowView.vue'
import { useProjectsStore } from '../stores/projects'
import { useAssistantStore } from '../stores/assistant'

type Tab = { id: string; title: string; kind: 'home' | 'workflow' | 'data' | 'resource'; resource?: WorkspaceResource }
type UiRoot = 'DATA' | 'KNOWLEDGE' | 'OUTPUT'
const props = defineProps<{ project: Project | null; loading: boolean; error: string }>()
const projectStore = useProjectsStore()
const assistant = useAssistantStore()
const workspace = ref<ProjectWorkspace | null>(null), search = ref(''), notice = ref(''), loadingWorkspace = ref(false), workflowKey = ref(0)
const projectManagerOpen = ref(false), projectFormOpen = ref(false), editingProjectId = ref(''), projectName = ref(''), projectDescription = ref(''), savingProject = ref(false)
const projectStats = ref<Record<string, { resources: number; workflow: string }>>({})
const folderFormOpen = ref(false), folderEditingId = ref(''), folderName = ref(''), folderRoot = ref<WorkspaceRootKind>('FILES'), folderParentId = ref<string | undefined>()
const movingResource = ref<WorkspaceResource | null>(null), targetFolderId = ref('')
const expandedRoots = ref<Record<UiRoot, boolean>>({ DATA: true, KNOWLEDGE: true, OUTPUT: true })
const expandedBuckets = ref<Record<string, boolean>>({ connections: true, urls: true, uploaded: false, extracts: false, knowledge: true, outputs: true })
const tabs = ref<Tab[]>([{ id: 'home', title: '项目概览', kind: 'home' }]), activeTabId = ref('home')
const uploadInput = ref<HTMLInputElement | null>(null)
const addPanelOpen = ref(false), addPanelMode = ref<'choose' | 'connection' | 'url'>('choose'), addTargetRoot = ref<UiRoot>('DATA'), addTargetFolderId = ref<string | undefined>(), addTargetBucket = ref('')
const uploadAccept = ref(''), savingContent = ref(false)
const connectionForm = reactive({ name: '', sourceType: 'POSTGRESQL', jdbcUrl: 'jdbc:postgresql://localhost:5432/database', username: '', secretRef: '', format: 'json' })
const urlForm = reactive({ name: '', url: 'https://' })
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
const filteredResources = computed(() => (workspace.value?.resources ?? []).filter(item => item.name.toLowerCase().includes(search.value.trim().toLowerCase())))

async function loadWorkspace() {
  if (!props.project) return
  loadingWorkspace.value = true
  try {
    workspace.value = await api.getProjectWorkspace(props.project.id)
    tabs.value.forEach(tab => {
      if (tab.resource) tab.resource = workspace.value?.resources.find(item => item.id === tab.resource?.id) ?? tab.resource
    })
  }
  catch (reason) { notice.value = reason instanceof Error ? reason.message : '项目内容没有加载' }
  finally { loadingWorkspace.value = false }
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
function openTab(tab: Tab) {
  const existing = tabs.value.find(item => item.id === tab.id)
  if (!existing) tabs.value.push(tab)
  else if (tab.resource) existing.resource = tab.resource
  activeTabId.value = tab.id
}
function openResource(resource: WorkspaceResource) {
  openTab({ id: `resource-${resource.id}`, title: resource.name, kind: 'resource', resource })
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
  await loadWorkspace()
  const resource = workspace.value?.resources.find(item => item.id === deliverableId)
  if (resource) openResource(resource)
  else notice.value = '输出件已经生成，但项目目录暂时没有同步完成'
}
async function openWorkflowResource(resourceId: string) {
  await loadWorkspace()
  const resource = workspace.value?.resources.find(item => item.id === resourceId)
  if (resource) openResource(resource)
  else notice.value = '中间结果已保存，但项目目录暂时没有同步完成'
}
function openWorkflow() { workflowKey.value += 1; openTab({ id: 'workflow', title: '主工作流', kind: 'workflow' }) }
function syncWorkflow(workflow: Workflow) { if (workspace.value) workspace.value = { ...workspace.value, workflow } }
function openData() { openTab({ id: 'data', title: '数据采集', kind: 'data' }) }
function dragResource(event: DragEvent, resource: WorkspaceResource) { if (!event.dataTransfer) return; event.dataTransfer.effectAllowed = 'copy'; event.dataTransfer.setData('application/x-finflow-resource', JSON.stringify(resource)) }
function storageRoot(root: WorkspaceRootKind | UiRoot): WorkspaceRootKind { return root === 'DATA' || root === 'KNOWLEDGE' ? 'FILES' : root === 'OUTPUT' ? 'OUTPUTS' : root }
function startFolder(root: WorkspaceRootKind | UiRoot, parentId?: string) { folderEditingId.value = ''; folderName.value = ''; folderRoot.value = storageRoot(root); folderParentId.value = parentId; folderFormOpen.value = true }
function addSubfolder(parent: WorkspaceFolder) { startFolder(parent.rootKind, parent.id) }
function openAdd(root: UiRoot, folderId?: string, bucketId = '') {
  if (root === 'OUTPUT') { startFolder(root, folderId); return }
  addTargetRoot.value = root; addTargetFolderId.value = folderId; addTargetBucket.value = bucketId
  addPanelMode.value = 'choose'; addPanelOpen.value = true
}
function chooseAdd(kind: 'upload-data' | 'upload-knowledge' | 'database' | 'api' | 'url' | 'folder') {
  if (kind === 'folder') { addPanelOpen.value = false; startFolder(addTargetRoot.value, addTargetFolderId.value); return }
  if (kind.startsWith('upload')) {
    uploadAccept.value = kind === 'upload-data' ? '.csv,.tsv,.xls,.xlsx,.xlsm' : '.pdf,.doc,.docx,.ppt,.pptx,.txt,.md,.png,.jpg,.jpeg'
    addPanelOpen.value = false
    void nextTick(() => uploadInput.value?.click())
    return
  }
  if (kind === 'url') { urlForm.name = ''; urlForm.url = 'https://'; addPanelMode.value = 'url'; return }
  connectionForm.name = ''; connectionForm.username = ''; connectionForm.secretRef = ''; connectionForm.format = 'json'
  connectionForm.sourceType = kind === 'api' ? 'HTTP_API' : 'POSTGRESQL'
  connectionForm.jdbcUrl = kind === 'api' ? 'https://' : 'jdbc:postgresql://localhost:5432/database'
  addPanelMode.value = 'connection'
}
function connectionTypeChanged() {
  const examples: Record<string, string> = { POSTGRESQL: 'jdbc:postgresql://localhost:5432/database', MYSQL: 'jdbc:mysql://localhost:3306/database', OPENGAUSS: 'jdbc:opengauss://localhost:5432/database', GAUSS_DWS: 'jdbc:postgresql://localhost:5432/database', DUCKDB: 'jdbc:duckdb:/path/to/data.duckdb' }
  connectionForm.jdbcUrl = examples[connectionForm.sourceType] ?? connectionForm.jdbcUrl
}
async function saveConnection() {
  if (!props.project || !connectionForm.name.trim() || !connectionForm.jdbcUrl.trim()) { notice.value = '请填写名称和连接地址'; return }
  savingContent.value = true
  try {
    const created = await api.createConnection({ projectId: props.project.id, name: connectionForm.name.trim(), sourceType: connectionForm.sourceType, jdbcUrl: connectionForm.jdbcUrl.trim(), username: connectionForm.sourceType === 'HTTP_API' ? '' : connectionForm.username.trim(), secretRef: connectionForm.secretRef.trim(), options: connectionForm.sourceType === 'HTTP_API' ? { format: connectionForm.format, method: 'GET' } : {} })
    addPanelOpen.value = false; await loadWorkspace(); notice.value = connectionForm.sourceType === 'HTTP_API' ? '数据服务已加入项目' : '数据库连接已加入项目'
    const resource = workspace.value?.resources.find(item => item.id === created.id); if (resource) openResource(resource)
  } catch (reason) { notice.value = reason instanceof Error ? reason.message : '数据连接没有保存' }
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
  if (!props.project || !window.confirm(`删除目录“${folder.name}”？仅空目录可以删除。`)) return
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
  if (!props.project || !movingResource.value) return
  try { await api.moveWorkspaceResource(props.project.id, movingResource.value, targetFolderId.value || undefined); movingResource.value = null; notice.value = '内容已移动'; await loadWorkspace() }
  catch (reason) { notice.value = reason instanceof Error ? reason.message : '内容没有移动' }
}
async function openProjectManager() {
  projectManagerOpen.value = true
  const results = await Promise.all(projectStore.projects.map(async project => { try { const item = await api.getProjectWorkspace(project.id); return [project.id, { resources: item.resources.length, workflow: item.workflow.status }] as const } catch { return [project.id, { resources: 0, workflow: 'DRAFT' }] as const } }))
  projectStats.value = Object.fromEntries(results)
}
function newProject() { editingProjectId.value = ''; projectName.value = ''; projectDescription.value = ''; projectFormOpen.value = true }
function editProject(project: Project) { editingProjectId.value = project.id; projectName.value = project.name; projectDescription.value = project.description; projectFormOpen.value = true }
async function deleteProject(project: Project) {
  if (!window.confirm(`删除项目“${project.name}”？项目将从个人空间移除。`)) return
  try { await projectStore.delete(project.id); projectManagerOpen.value = false; tabs.value = [{ id: 'home', title: '项目概览', kind: 'home' }]; activeTabId.value = 'home' }
  catch (reason) { notice.value = reason instanceof Error ? reason.message : '项目没有删除' }
}
async function saveProject() {
  if (!projectName.value.trim()) { notice.value = '请填写项目名称'; return }
  savingProject.value = true
  try { if (editingProjectId.value) await projectStore.update(editingProjectId.value, projectName.value, projectDescription.value); else await projectStore.create(projectName.value, projectDescription.value); projectFormOpen.value = false; projectManagerOpen.value = false; tabs.value = [{ id: 'home', title: '项目概览', kind: 'home' }]; activeTabId.value = 'home' }
  catch (reason) { notice.value = reason instanceof Error ? reason.message : '项目没有保存' } finally { savingProject.value = false }
}
function selectProject(project: Project) { projectStore.select(project); projectManagerOpen.value = false; tabs.value = [{ id: 'home', title: '项目概览', kind: 'home' }]; activeTabId.value = 'home' }
function closeTab(tab: Tab) {
  if (tab.id === 'home') return
  const index = tabs.value.findIndex(item => item.id === tab.id)
  tabs.value.splice(index, 1)
  if (activeTabId.value === tab.id) activeTabId.value = tabs.value[Math.max(0, index - 1)]?.id ?? 'home'
}
async function upload(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  if (!props.project || files.length === 0) return
  notice.value = `正在上传 ${files.length} 个文件`
  try {
    const uploaded = []; for (const file of files) uploaded.push(await api.uploadFile(props.project.id, file))
    await loadWorkspace()
    if (addTargetFolderId.value) {
      for (const file of uploaded) { const resource = workspace.value?.resources.find(item => item.id === file.id); if (resource) await api.moveWorkspaceResource(props.project.id, resource, addTargetFolderId.value) }
      await loadWorkspace()
    }
    notice.value = '文件已加入项目'
    const resource = workspace.value?.resources.find(item => item.id === uploaded[0]?.id); if (resource && uploaded.length === 1) openResource(resource)
  }
  catch (reason) { notice.value = reason instanceof Error ? reason.message : '文件没有上传' }
  finally { if (uploadInput.value) uploadInput.value.value = '' }
}
function nodeFor(resource: WorkspaceResource, index: number): WorkflowNode {
  const position = { x: 80 + (index % 3) * 260, y: 80 + Math.floor(index / 3) * 150 }
  if (resource.resourceType === 'DATABASE_CONNECTION' || resource.resourceType === 'API_CONNECTION') return { id: `data_${crypto.randomUUID().slice(0, 8)}`, type: 'DATA_EXTRACT', name: resource.name, ...position, config: { connectionId: resource.id, sql: resource.resourceType === 'API_CONNECTION' ? 'GET /' : 'select * from your_table', outputName: `${resource.name}.csv`, fetchSize: 5000 } }
  if (resource.resourceType === 'WEB_URL') return { id: resource.id, type: 'LINK_INPUT', name: resource.name, ...position, config: { url: resource.url, title: resource.name } }
  if (resource.resourceType === 'DELIVERABLE') { const format = (resource.mediaType || 'PPTX').toUpperCase(); return { id: `output_${crypto.randomUUID().slice(0, 8)}`, type: 'DELIVERABLE', name: resource.name, ...position, config: { outputResourceId: resource.id, title: resource.name, subtitle: '由工作流自动生成', format, pptSkill: format === 'HTML_SLIDES' ? 'frontend-slides' : format === 'PPTX' ? 'guizang-huawei-style-c' : '', heading: '分析结果', targetAudience: '业务负责人', lengthHint: '适中', includeCitations: true, citationStyle: 'IEEE', generationPrompt: '根据上游数据和参考资料生成结论清晰、可直接使用的业务成果。' } } }
  if (resource.resourceType === 'DATASET') return { id: `dataset_${crypto.randomUUID().slice(0, 8)}`, type: 'DATASET_INPUT', name: resource.name, ...position, config: { extractJobId: resource.id } }
  return { id: `file_${crypto.randomUUID().slice(0, 8)}`, type: 'FILE_INPUT', name: resource.name, ...position, config: { resourceId: resource.id } }
}
async function addToWorkflow(resource: WorkspaceResource) {
  if (!props.project) return
  if (resource.resourceType === 'WEB_URL') { notice.value = '这个网站地址已经在主工作流中'; openWorkflow(); return }
  try {
    const workflow = await api.getProjectWorkflow(props.project.id)
    const used = workflow.nodes.some(node => Object.values(node.config ?? {}).includes(resource.id))
    if (!used) await api.saveProjectWorkflow(props.project.id, { name: workflow.name, description: workflow.description, nodes: [...workflow.nodes, nodeFor(resource, workflow.nodes.length)], edges: workflow.edges, executionMode: workflow.executionMode, schedule: workflow.schedule, expectedVersion: workflow.currentVersion })
    notice.value = used ? '这个资源已经在主工作流中' : '已加入主工作流，可以继续连接处理步骤'
    await loadWorkspace(); openWorkflow()
  } catch (reason) { notice.value = reason instanceof Error ? reason.message : '资源没有加入工作流' }
}
async function deleteResource(resource: WorkspaceResource) {
  if (!window.confirm(`删除“${resource.name}”？相关文件和历史版本也会被删除。`)) return
  try {
    if (resource.resourceType === 'WEB_URL') { const workflow = await api.getProjectWorkflow(resource.projectId); await api.saveProjectWorkflow(resource.projectId, { name: workflow.name, description: workflow.description, nodes: workflow.nodes.filter(node => node.id !== resource.id), edges: workflow.edges.filter(edge => edge.source !== resource.id && edge.target !== resource.id), executionMode: workflow.executionMode, schedule: workflow.schedule, expectedVersion: workflow.currentVersion }) }
    else if (resource.resourceType === 'DATABASE_CONNECTION' || resource.resourceType === 'API_CONNECTION') await api.deleteConnection(resource.id)
    else if (resource.resourceType === 'DATASET') await api.deleteExtract(resource.id)
    else if (resource.resourceType === 'DELIVERABLE') await api.deleteDeliverable(resource.id)
    else await api.deleteFile(resource.id)
    tabs.value = tabs.value.filter(tab => tab.resource?.id !== resource.id)
    if (!tabs.value.some(tab => tab.id === activeTabId.value)) activeTabId.value = 'home'
    notice.value = '内容已删除'
    await loadWorkspace()
  } catch (reason) { notice.value = reason instanceof Error ? reason.message : '内容没有删除' }
}
function handleAssistantAction(event: Event) {
  const detail = (event as CustomEvent<{ type?: string; resourceId?: string }>).detail
  if (!detail?.type) return
  if (detail.type === 'OPEN_WORKFLOW') openWorkflow()
  else if (detail.type === 'OPEN_DATA') openData()
  else if (detail.type === 'OPEN_HOME') activeTabId.value = 'home'
  else if (detail.type === 'OPEN_RESOURCE' && detail.resourceId) {
    const resource = workspace.value?.resources.find(item => item.id === detail.resourceId)
    if (resource) openResource(resource)
  }
}
onMounted(() => window.addEventListener('finflow:assistant-action', handleAssistantAction))
onBeforeUnmount(() => window.removeEventListener('finflow:assistant-action', handleAssistantAction))
watch(activeTab, tab => {
  if (!tab) return
  const page = tab.kind === 'workflow' ? 'workflow' : tab.kind === 'data' ? 'data-collection' : tab.kind === 'resource' ? 'resource' : 'project-home'
  const selection = tab.resource ? { type: tab.resource.resourceType, resourceId: tab.resource.id, range: [tab.resource.name] } : undefined
  assistant.setWorkbenchContext(page, tab.title, selection)
}, { immediate: true })
watch(() => props.project?.id, (id, previousId) => {
  if (previousId && id !== previousId) { tabs.value = [{ id: 'home', title: '项目概览', kind: 'home' }]; activeTabId.value = 'home' }
  void loadWorkspace()
}, { immediate: true })
</script>

<template>
  <div class="project-shell" :class="{ 'workflow-mode': activeTab?.kind === 'workflow' }">
    <aside class="resource-sidebar">
      <div class="workbench-brand"><span>F</span><div><strong>FinBTP Studio</strong><small>个人专注工作台</small></div></div>
      <button class="project-switcher" type="button" @click="openProjectManager"><div><small>当前项目</small><strong>{{ project?.name ?? '正在打开' }}</strong></div><ChevronDown :size="15"/></button>
      <div class="resource-search-row"><label class="resource-search"><Search :size="15"/><input v-model="search" placeholder="查找项目内容"></label><button type="button" title="新建目录" @click="startFolder('FILES')"><FolderPlus :size="16"/></button></div>
      <button class="workflow-entry" type="button" :class="{ active: activeTab?.kind === 'workflow' }" @click="openWorkflow"><WorkflowIcon :size="17"/><span>主工作流</span><small>第 {{ workspace?.workflow.currentVersion ?? 1 }} 版</small></button>
      <nav class="resource-tree">
        <section v-for="root in treeRoots" :key="root.id" class="tree-root"><header><button class="tree-toggle" type="button" @click="expandedRoots[root.id] = !expandedRoots[root.id]"><ChevronDown v-if="expandedRoots[root.id]" :size="13"/><ChevronRight v-else :size="13"/></button><component :is="root.icon" :size="15"/><strong>{{ root.label }}</strong><span>{{ rootCount(root.id) }}</span><button type="button" :title="root.id === 'OUTPUT' ? '新建目录' : '添加内容'" @click="openAdd(root.id)"><Plus :size="13"/></button></header><div v-if="expandedRoots[root.id] || search"><ResourceTreeFolder v-for="folder in rootFolders(root.id)" :key="folder.id" :folder="folder" :folders="workspace?.folders ?? []" :resources="filteredResources" :active-resource-id="activeTab?.resource?.id" :query="search" :ui-root="root.id" :icon-for="iconFor" @open="openResource" @drag="dragResource" @add-content="(uiRoot, folder) => openAdd(uiRoot, folder.id)" @add-folder="addSubfolder" @rename-folder="renameFolder" @delete-folder="deleteFolder" @move-resource="startMoveResource"/><div v-for="bucket in rootBuckets(root.id)" :key="bucket.id" class="tree-folder virtual-folder"><div class="tree-folder-row"><button class="tree-toggle" type="button" @click="expandedBuckets[bucket.id] = !expandedBuckets[bucket.id]"><ChevronDown v-if="expandedBuckets[bucket.id] || search" :size="13"/><ChevronRight v-else :size="13"/></button><Folder :size="15"/><span>{{ bucket.label }}</span><small>{{ bucket.items.length }}</small><button v-if="root.id !== 'OUTPUT' && !['extracts'].includes(bucket.id)" type="button" title="在此添加" @click="openAdd(root.id, undefined, bucket.id)"><Plus :size="13"/></button></div><div v-if="expandedBuckets[bucket.id] || search"><div v-for="item in bucket.items" :key="item.id" class="tree-resource-row" :class="{ active: activeTab?.resource?.id === item.id }"><button draggable="true" type="button" @dragstart="dragResource($event, item)" @click="openResource(item)"><component :is="iconFor(item)" :size="15"/><span :title="item.name">{{ item.name }}</span><i v-if="item.inProjectWorkflow" title="已在工作流中"></i></button><button type="button" title="移动到目录" @click="startMoveResource(item)"><FolderInput :size="13"/></button></div></div></div><div v-for="item in directRootResources(root.id)" :key="item.id" class="tree-resource-row" :class="{ active: activeTab?.resource?.id === item.id }"><button draggable="true" type="button" @dragstart="dragResource($event, item)" @click="openResource(item)"><component :is="iconFor(item)" :size="15"/><span :title="item.name">{{ item.name }}</span><i v-if="item.inProjectWorkflow" title="已在工作流中"></i></button><button type="button" title="移动到目录" @click="startMoveResource(item)"><FolderInput :size="13"/></button></div></div></section>
      </nav>
      <input ref="uploadInput" hidden multiple type="file" :accept="uploadAccept" @change="upload">
    </aside>

    <main class="project-main" :class="{ 'has-notice': !!notice }">
      <header class="project-topbar"><div><strong>{{ activeTab?.title }}</strong><small v-if="loadingWorkspace">正在同步项目内容</small><small v-else>所有内容自动保存在当前项目</small></div><button class="icon-button" title="更多操作"><MoreHorizontal :size="19"/></button></header>
      <nav class="work-tabs"><button v-for="tab in tabs" :key="tab.id" type="button" :class="{ active: tab.id === activeTabId }" @click="activeTabId = tab.id"><span>{{ tab.title }}</span><X v-if="tab.id !== 'home'" :size="13" @click.stop="closeTab(tab)"/></button></nav>
      <p v-if="notice" class="project-notice">{{ notice }}<button type="button" @click="notice = ''"><X :size="14"/></button></p>
      <section class="project-stage">
        <div v-if="loading || loadingWorkspace" class="resource-state">正在整理项目空间</div>
        <div v-else-if="error" class="resource-state error">{{ error }}</div>
        <div v-else-if="activeTab?.kind === 'home'" class="project-overview"><header><FolderOpen :size="32"/><div><h1>{{ project?.name }}</h1><p>{{ project?.description || '把文件、数据和资料组织成一条可以反复运行的工作流。' }}</p></div></header><div class="overview-stats"><button v-for="group in groups" :key="group.id" type="button"><component :is="group.icon" :size="19"/><strong>{{ resources(group.id).length }}</strong><span>{{ group.label }}</span></button><button type="button" @click="openWorkflow"><WorkflowIcon :size="19"/><strong>{{ workspace?.workflow.status === 'READY' ? '可运行' : '草稿' }}</strong><span>主工作流</span></button></div><section><h2>最近使用</h2><button v-for="item in filteredResources.slice(0, 8)" :key="item.id" type="button" @click="openResource(item)"><component :is="iconFor(item)" :size="17"/><span>{{ item.name }}</span><small>{{ item.inProjectWorkflow ? '已加入工作流' : '尚未编排' }}</small></button></section></div>
        <WorkflowView v-else-if="activeTab?.kind === 'workflow'" :key="workflowKey" :project="project" :resources="workspace?.resources ?? []" @resources-changed="loadWorkspace" @workflow-changed="syncWorkflow" @open-deliverable="openGeneratedDeliverable" @open-resource="openWorkflowResource" />
        <DataView v-else-if="activeTab?.kind === 'data'" :project="project" />
        <ResourceWorkbench v-else-if="activeTab?.resource && activeTab.resource.resourceType !== 'WEB_URL'" :key="`${activeTab.resource.id}-${activeTab.resource.currentVersion}`" :resource="activeTab.resource" @add-to-workflow="addToWorkflow" @manage-data="openData" @delete-resource="deleteResource" @open-source="openCitationSource" />
        <ResourceWorkbench v-for="tab in openWebTabs" v-show="!loading && !loadingWorkspace && !error && activeTabId === tab.id" :key="tab.id" :resource="tab.resource!" @add-to-workflow="addToWorkflow" @manage-data="openData" @delete-resource="deleteResource" @open-source="openCitationSource" />
      </section>
    </main>

    <aside class="context-sidebar"><header><strong>当前内容</strong></header><template v-if="activeTab?.resource"><component :is="iconFor(activeTab.resource)" :size="26"/><h3>{{ activeTab.resource.name }}</h3><dl><dt>状态</dt><dd>{{ activeTab.resource.status }}</dd><dt>版本</dt><dd>第 {{ activeTab.resource.currentVersion }} 版</dd><dt>工作流</dt><dd>{{ activeTab.resource.inProjectWorkflow ? '已加入' : '未加入' }}</dd></dl><button class="primary-button" type="button" @click="addToWorkflow(activeTab.resource)"><Plus :size="15"/>加入主工作流</button></template><template v-else><WorkflowIcon :size="26"/><h3>{{ activeTab?.kind === 'workflow' ? '项目主工作流' : '项目空间' }}</h3><p>选中文件或数据后，这里会显示版本、状态和编排关系。</p></template></aside>
    <AssistantPanel :project="project" />
    <div v-if="addPanelOpen" class="project-form-backdrop" @click.self="addPanelOpen = false"><section class="project-form-panel add-content-panel"><header><div><strong>{{ addPanelMode === 'choose' ? `向“${addTargetRoot === 'DATA' ? '数据' : '资料'}”添加` : addPanelMode === 'url' ? '添加网站地址' : connectionForm.sourceType === 'HTTP_API' ? '接入数据服务' : '连接数据库' }}</strong><small v-if="addTargetFolderId">内容会保存在当前目录</small></div><button class="icon-button" type="button" title="关闭" @click="addPanelOpen = false"><X :size="17"/></button></header>
      <div v-if="addPanelMode === 'choose'" class="add-source-list">
        <template v-if="addTargetRoot === 'DATA'">
          <button v-if="!addTargetBucket || addTargetBucket === 'uploaded'" type="button" @click="chooseAdd('upload-data')"><FileSpreadsheet :size="19"/><span><strong>上传表格文件</strong><small>CSV、Excel 等结构化数据</small></span><ChevronRight :size="15"/></button>
          <button v-if="!addTargetFolderId && (!addTargetBucket || addTargetBucket === 'connections')" type="button" @click="chooseAdd('database')"><Database :size="19"/><span><strong>连接数据库</strong><small>PostgreSQL、MySQL、openGauss 等</small></span><ChevronRight :size="15"/></button>
          <button v-if="!addTargetFolderId && (!addTargetBucket || addTargetBucket === 'connections')" type="button" @click="chooseAdd('api')"><Braces :size="19"/><span><strong>接入数据服务</strong><small>通过 HTTP API 读取数据</small></span><ChevronRight :size="15"/></button>
        </template>
        <template v-else>
          <button v-if="!addTargetBucket || addTargetBucket === 'knowledge'" type="button" @click="chooseAdd('upload-knowledge')"><Upload :size="19"/><span><strong>上传资料</strong><small>PDF、Word、PPT、图片或文本</small></span><ChevronRight :size="15"/></button>
          <button v-if="!addTargetBucket || addTargetBucket === 'urls'" type="button" @click="chooseAdd('url')"><Globe2 :size="19"/><span><strong>添加网站地址</strong><small>在工作区直接查看网页内容</small></span><ChevronRight :size="15"/></button>
        </template>
        <button v-if="!addTargetBucket" type="button" @click="chooseAdd('folder')"><FolderPlus :size="19"/><span><strong>新建目录</strong><small>继续整理当前分类的内容</small></span><ChevronRight :size="15"/></button>
      </div>
      <div v-else-if="addPanelMode === 'connection'" class="form-stack add-connection-form"><label><span>名称</span><input v-model="connectionForm.name" autofocus placeholder="例如：经营数据仓库"></label><label v-if="connectionForm.sourceType !== 'HTTP_API'"><span>数据库类型</span><select v-model="connectionForm.sourceType" @change="connectionTypeChanged"><option value="POSTGRESQL">PostgreSQL</option><option value="MYSQL">MySQL</option><option value="OPENGAUSS">openGauss</option><option value="GAUSS_DWS">GaussDB(DWS)</option><option value="DUCKDB">DuckDB</option></select></label><label><span>{{ connectionForm.sourceType === 'HTTP_API' ? '接口地址' : '连接地址' }}</span><input v-model="connectionForm.jdbcUrl"></label><label v-if="connectionForm.sourceType !== 'HTTP_API'"><span>用户名</span><input v-model="connectionForm.username"></label><label><span>{{ connectionForm.sourceType === 'HTTP_API' ? '访问凭据环境变量' : '密码环境变量' }}</span><input v-model="connectionForm.secretRef" placeholder="env:FINFLOW_DATA_PASSWORD"></label><label v-if="connectionForm.sourceType === 'HTTP_API'"><span>返回格式</span><select v-model="connectionForm.format"><option value="json">JSON</option><option value="jsonl">JSON Lines</option><option value="csv">CSV</option></select></label></div>
      <div v-else class="form-stack"><label><span>网站名称</span><input v-model="urlForm.name" autofocus placeholder="例如：公司年度报告页面"></label><label><span>网站地址</span><input v-model="urlForm.url" placeholder="https://example.com/report"></label><p class="form-help">保存后可在工作区直接访问，也可以作为工作流的参考输入。</p></div>
      <footer v-if="addPanelMode !== 'choose'"><button class="secondary-button" type="button" @click="addPanelMode = 'choose'">返回</button><button class="primary-button" type="button" :disabled="savingContent" @click="addPanelMode === 'url' ? saveUrl() : saveConnection()">{{ savingContent ? '正在保存' : '保存' }}</button></footer>
    </section></div>
    <div v-if="projectManagerOpen" class="project-manager-backdrop" @click.self="projectManagerOpen = false">
      <section class="project-manager-panel"><header><div><LayoutGrid :size="20"/><div><strong>我的项目</strong><small>管理个人项目和工作流空间</small></div></div><button class="icon-button" type="button" title="关闭" @click="projectManagerOpen = false"><X :size="18"/></button></header><div class="project-manager-toolbar"><span>{{ projectStore.projects.length }} 个项目</span><button class="primary-button" type="button" @click="newProject"><Plus :size="15"/>新建项目</button></div><div class="project-list"><article v-for="item in projectStore.projects" :key="item.id" :class="{ active: item.id === project?.id }"><button class="project-card-main" type="button" @click="selectProject(item)"><span class="project-card-icon"><FolderOpen :size="20"/></span><span><strong>{{ item.name }}</strong><small>{{ item.description || '还没有填写项目说明' }}</small><i>{{ projectStats[item.id]?.resources ?? 0 }} 项内容 · {{ projectStats[item.id]?.workflow === 'READY' ? '工作流可运行' : '工作流草稿' }}</i></span></button><div class="project-card-actions"><button class="icon-button" type="button" title="编辑项目" @click="editProject(item)"><Pencil :size="15"/></button><button class="icon-button danger" type="button" title="删除项目" @click="deleteProject(item)"><Trash2 :size="15"/></button></div></article></div></section>
    </div>
    <div v-if="projectFormOpen" class="project-form-backdrop" @click.self="projectFormOpen = false"><section class="project-form-panel"><header><strong>{{ editingProjectId ? '编辑项目' : '新建项目' }}</strong><button class="icon-button" type="button" title="关闭" @click="projectFormOpen = false"><X :size="17"/></button></header><div class="form-stack"><label><span>项目名称</span><input v-model="projectName" maxlength="200" autofocus placeholder="例如：年度预算分析"></label><label><span>项目说明</span><textarea v-model="projectDescription" rows="4" maxlength="2000" placeholder="这个项目准备完成什么工作"></textarea></label></div><footer><button class="secondary-button" type="button" @click="projectFormOpen = false">取消</button><button class="primary-button" type="button" :disabled="savingProject" @click="saveProject">{{ savingProject ? '正在保存' : '保存项目' }}</button></footer></section></div>
    <div v-if="folderFormOpen" class="project-form-backdrop" @click.self="folderFormOpen = false"><section class="project-form-panel compact-panel"><header><strong>{{ folderEditingId ? '重命名目录' : '新建目录' }}</strong><button class="icon-button" type="button" title="关闭" @click="folderFormOpen = false"><X :size="17"/></button></header><div class="form-stack"><label><span>目录名称</span><input v-model="folderName" maxlength="200" autofocus placeholder="例如：月度盘点资料" @keydown.enter="saveFolder"></label><label v-if="!folderEditingId"><span>所在分类</span><select v-model="folderRoot"><option v-for="root in treeRoots" :key="root.id" :value="root.id">{{ root.label }}</option></select></label></div><footer><button class="secondary-button" type="button" @click="folderFormOpen = false">取消</button><button class="primary-button" type="button" @click="saveFolder">保存</button></footer></section></div>
    <div v-if="movingResource" class="project-form-backdrop" @click.self="movingResource = null"><section class="project-form-panel compact-panel"><header><div><strong>移动内容</strong><small>{{ movingResource.name }}</small></div><button class="icon-button" type="button" title="关闭" @click="movingResource = null"><X :size="17"/></button></header><div class="form-stack"><label><span>目标目录</span><select v-model="targetFolderId"><option value="">{{ treeRoots.find(item => item.id === movingResource?.rootKind)?.label }}（根目录）</option><option v-for="folder in moveFolderOptions" :key="folder.id" :value="folder.id">{{ folder.label }}</option></select></label><p v-if="moveFolderOptions.length === 0" class="form-help">这个分类下还没有自定义目录，可以先在左侧点击 + 创建。</p></div><footer><button class="secondary-button" type="button" @click="movingResource = null">取消</button><button class="primary-button" type="button" @click="moveResource">移动</button></footer></section></div>
  </div>
</template>
