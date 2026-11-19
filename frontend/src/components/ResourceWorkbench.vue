<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { AlertTriangle, BookOpen, Braces, CheckCircle2, ChevronDown, ChevronLeft, ChevronRight, ClipboardPaste, Database, Download, ExternalLink, Eye, EyeOff, FilePenLine, FileText, Globe2, LoaderCircle, Pencil, Play, Plus, RefreshCw, Save, Search, Server, ShieldAlert, Trash2, X } from 'lucide-vue-next'
import { api, deliverableContentUrl, downloadFile, inlineContentUrl, renderedOfficePreviewUrl, type CitationSource, type ConnectionPreview, type CsvPreview, type DataConnection, type DatabaseCatalog, type DatabaseTable, type DocumentPreview, type SpreadsheetCsvConversion, type SpreadsheetPreview, type WebEmbedStatus, type WebPreview, type WorkspaceResource } from '../api/client'
import { buildDatabaseJdbcUrl, defaultDatabasePort, parseDatabaseConnection, type DatabaseSourceType } from '../domain/databaseConnection'
import { formatFileSize, spreadsheetAccessMode } from '../domain/spreadsheetAccess'
import DiagramPreview from './DiagramPreview.vue'
import OfficeEditor from './OfficeEditor.vue'
import CitationAnchor from './CitationAnchor.vue'
import NativeDocument from './NativeDocument.vue'
import ResearchReader from './ResearchReader.vue'
import { isNativeDocument } from '../domain/nativeDocument'

const InteractiveFinancialReport = defineAsyncComponent(() => import('./InteractiveFinancialReport.vue'))

const props = defineProps<{ resource: WorkspaceResource; location?: string; compact?: boolean }>()
const emit = defineEmits<{ useAsInput: [resource: WorkspaceResource]; manageData: []; deleteResource: [resource: WorkspaceResource]; openSource: [citation: CitationSource]; resourcesChanged: []; documentState: [state: { dirty: boolean; version: number; loading: boolean }] }>()
const loading = ref(false), error = ref(''), editing = ref(false), officeFallback = ref(false), officePrompt = ref(false), csv = ref<CsvPreview | null>(null), document = ref<DocumentPreview | null>(null)
const spreadsheet = ref<SpreadsheetPreview | null>(null), spreadsheetSheet = ref(''), spreadsheetPage = ref(0)
const spreadsheetCursors = ref<Array<string | undefined>>([undefined])
const conversionLoading = ref(false), conversionError = ref(''), conversionResult = ref<SpreadsheetCsvConversion | null>(null)
const convertedDataMode = ref(false), convertedPart = ref(1), csvPage = ref(0)
const csvCursors = ref<Array<string | undefined>>([undefined])
let conversionPollTimer = 0
const downloadLoading = ref(false), downloadMessage = ref('')
async function openDocumentReference(href: string) {
  try {
    const project = await api.getProjectWorkspace(props.resource.projectId)
    const name = decodeURIComponent(href.replace(/^resource:/, '').split('#')[0]!.split('/').at(-1) ?? '')
    const matches = project.resources.filter(r => r.id === name || r.name === name)
    if (matches.length !== 1) { downloadMessage.value = matches.length ? '存在同名文件，请从资料列表选择' : '引用文件未找到，请检查名称或重新添加来源'; return }
    const r = matches[0]!
    emit('openSource', { id: r.id, resource_id: r.id, version: r.currentVersion, source_name: r.name, text: '', location: {}, content_hash: '', formatted: r.name })
  } catch (e) { downloadMessage.value = e instanceof Error ? e.message : '引用未能打开' }
}
const webLoading = ref(false), webPreview = ref<WebPreview | null>(null), webOriginalMode = ref(false)
const webEmbedStatus = ref<WebEmbedStatus>({ status: 'CHECKING', reason: '' })
const webEmbedOverride = ref(false)
const connection = ref<DataConnection | null>(null), connectionPreview = ref<ConnectionPreview | null>(null)
const citations = ref<CitationSource[]>([]), citationError = ref('')
const citationsOpen = ref(false)
const previewQuery = ref(''), previewLoading = ref(false), testLoading = ref(false), connectionMessage = ref('')
const connectionMessageError = ref(false)
const connectionEditing = ref(false), connectionSaving = ref(false), catalogLoading = ref(false), catalog = ref<DatabaseCatalog | null>(null)
const connectionEditRecognition = ref('')
const connectionPasswordVisible = ref(false)
const catalogSearch = ref(''), expandedSchemas = ref<string[]>([]), selectedTable = ref<DatabaseTable | null>(null)
const connectionDraft = ref({ name: '', sourceType: 'POSTGRESQL', connectionText: '', jdbcUrl: '', host: '', port: '5432', database: '', parameters: '', username: '', secretRef: '', method: 'GET', format: 'json', body: '' })
const extension = computed(() => props.resource.name.includes('.') ? props.resource.name.split('.').pop()?.toLowerCase() ?? '' : props.resource.mediaType.toLowerCase())
const isConnection = computed(() => ['DATABASE_CONNECTION', 'API_CONNECTION'].includes(props.resource.resourceType))
const isWebUrl = computed(() => props.resource.resourceType === 'WEB_URL')
const isTabularPreview = computed(() => props.resource.resourceType === 'DATASET' || ['csv', 'tsv', 'parquet'].includes(extension.value) || props.resource.mediaType.toLowerCase() === 'application/vnd.apache.parquet')
const isPaginatedSpreadsheet = computed(() => props.resource.resourceType !== 'DELIVERABLE' && ['xlsx', 'xlsm'].includes(extension.value))
const spreadsheetAccess = computed(() => spreadsheetAccessMode(extension.value, props.resource.sizeBytes))
const isPdf = computed(() => extension.value === 'pdf')
const isRasterImage = computed(() => props.resource.resourceType !== 'DELIVERABLE' && ['png', 'jpg', 'jpeg', 'webp'].includes(extension.value))
const isNative = computed(() => isNativeDocument(props.resource.name, props.resource.mediaType))
const deliverableFormat = computed(() => props.resource.mediaType.toLowerCase())
const isFinancialReport = computed(() => props.resource.resourceType === 'DELIVERABLE' && deliverableFormat.value === 'financial_report')
const isHtmlSlides = computed(() => props.resource.resourceType === 'DELIVERABLE' && deliverableFormat.value === 'html_slides')
const isEditable = computed(() => props.resource.resourceType === 'DATASET' || ['doc', 'docx', 'odt', 'rtf', 'xls', 'xlsx', 'xlsm', 'ods', 'csv', 'ppt', 'pptx', 'odp', 'pdf'].includes(extension.value))
const isOfficeDocument = computed(() => props.resource.resourceType !== 'DATASET' && ['doc', 'docx', 'odt', 'rtf', 'xls', 'xlsx', 'xlsm', 'ods', 'ppt', 'pptx', 'odp'].includes(extension.value))
const diagramType = computed<'mermaid' | 'excalidraw' | null>(() => ['mmd', 'mermaid'].includes(extension.value) ? 'mermaid' : extension.value === 'excalidraw' ? 'excalidraw' : null)
const diagramSource = computed(() => document.value?.pages.flatMap(page => page.blocks.filter(block => block.type === 'text').map(block => block.text)).join('\n') ?? '')
const officeKind = computed<'files' | 'extract-jobs' | 'deliverables'>(() => props.resource.resourceType === 'DATASET' ? 'extract-jobs' : props.resource.resourceType === 'DELIVERABLE' ? 'deliverables' : 'files')
const sourceKind = computed(() => props.resource.resourceType === 'DATASET' ? 'extract-jobs' : props.resource.resourceType === 'DELIVERABLE' ? 'deliverables' : 'files')
const filteredSchemas = computed(() => {
  const keyword = catalogSearch.value.trim().toLocaleLowerCase()
  if (!catalog.value || !keyword) return catalog.value?.schemas ?? []
  return catalog.value.schemas.map(schema => ({ ...schema, tables: schema.tables.filter(table => `${schema.name} ${schema.technicalName} ${table.name} ${table.description}`.toLocaleLowerCase().includes(keyword)) })).filter(schema => schema.tables.length)
})

async function load() {
  csv.value = null; document.value = null; spreadsheet.value = null; connection.value = null; connectionPreview.value = null; catalog.value = null; selectedTable.value = null; error.value = ''; connectionMessage.value = ''; connectionMessageError.value = false
  if (isConnection.value) {
    loading.value = true
    try {
      connection.value = (await api.listConnections(props.resource.projectId)).find(item => item.id === props.resource.id) ?? null
      if (!connection.value) throw new Error('没有找到这个数据连接')
      previewQuery.value = connection.value.sourceType === 'HTTP_API' ? '' : defaultPreviewQuery(connection.value)
      if (connection.value.sourceType !== 'HTTP_API') await loadCatalog()
    } catch (reason) { error.value = reason instanceof Error ? reason.message : '连接信息没有打开' }
    finally { loading.value = false }
    return
  }
  if (isWebUrl.value) {
    if (webOriginalMode.value) await checkWebEmbedding()
    else await loadWebPreview()
    return
  }
  if (isPaginatedSpreadsheet.value && !editing.value) {
    if (convertedDataMode.value) return loadCsv()
    return loadSpreadsheet()
  }
  if ((isNative.value && !props.resource.name.toLowerCase().endsWith('.json.md')) || isPdf.value || isRasterImage.value || isOfficeDocument.value || isFinancialReport.value || isHtmlSlides.value || editing.value) return
  loading.value = true
  try {
    if (isTabularPreview.value) {
      csv.value = props.resource.resourceType === 'DATASET'
        ? await api.previewExtract(props.resource.id)
        : props.resource.resourceType === 'DELIVERABLE'
          ? await api.previewDeliverableCsv(props.resource.id)
          : await api.previewFileCsv(props.resource.id)
    }
    else document.value = props.resource.resourceType === 'DELIVERABLE' ? await api.previewDeliverable(props.resource.id) : await api.previewFile(props.resource.id)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '内容没有打开' }
  finally { loading.value = false }
}
async function loadCsv(cursor?: string) {
  loading.value = true; error.value = ''
  try {
    csv.value = convertedDataMode.value
      ? await api.previewSpreadsheetCsvConversion(props.resource.id, convertedPart.value, cursor)
      : props.resource.resourceType === 'DATASET'
        ? await api.previewExtract(props.resource.id, cursor)
        : props.resource.resourceType === 'DELIVERABLE'
          ? await api.previewDeliverableCsv(props.resource.id, cursor)
          : await api.previewFileCsv(props.resource.id, cursor)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : 'CSV 数据没有打开' }
  finally { loading.value = false }
}
async function nextCsvPage() {
  if (!csv.value?.nextCursor) return
  const next = csvPage.value + 1
  csvCursors.value[next] = csv.value.nextCursor
  csvPage.value = next
  await loadCsv(csvCursors.value[next])
}
async function previousCsvPage() {
  if (csvPage.value === 0) return
  csvPage.value -= 1
  await loadCsv(csvCursors.value[csvPage.value])
}
async function changeConvertedPart() {
  csvPage.value = 0; csvCursors.value = [undefined]
  await loadCsv()
}
async function loadSpreadsheet(cursor?: string) {
  loading.value = true; error.value = ''
  try {
    spreadsheet.value = await api.previewSpreadsheet(props.resource.id, spreadsheetSheet.value || undefined, cursor)
    spreadsheetSheet.value = spreadsheet.value.sheetName
    if (spreadsheetAccess.value === 'CONVERT' && !conversionResult.value) {
      try {
        const existing = await api.getSpreadsheetCsvConversion(props.resource.id)
        if (existing.status === 'SUCCEEDED') conversionResult.value = existing
      } catch { /* 原表预览不应被可选的转换状态阻断 */ }
    }
  } catch (reason) { error.value = reason instanceof Error ? reason.message : 'Excel 分页预览没有打开' }
  finally { loading.value = false }
}
async function changeSpreadsheetSheet() {
  spreadsheetPage.value = 0; spreadsheetCursors.value = [undefined]
  await loadSpreadsheet()
}
async function nextSpreadsheetPage() {
  if (!spreadsheet.value?.nextCursor) return
  const next = spreadsheetPage.value + 1
  spreadsheetCursors.value[next] = spreadsheet.value.nextCursor
  spreadsheetPage.value = next
  await loadSpreadsheet(spreadsheetCursors.value[next])
}
async function previousSpreadsheetPage() {
  if (spreadsheetPage.value === 0) return
  spreadsheetPage.value -= 1
  await loadSpreadsheet(spreadsheetCursors.value[spreadsheetPage.value])
}
async function requestOnlineEdit() {
  if (!isPaginatedSpreadsheet.value || spreadsheetAccess.value === 'DIRECT') {
    editing.value = true
    return
  }
  officePrompt.value = true
  if (spreadsheetAccess.value === 'CONVERT') await pollConversion()
}
function continueOfficeEdit() {
  if (spreadsheetAccess.value === 'CONVERT') return
  officePrompt.value = false
  editing.value = true
}
function useLargeDataMode() {
  officePrompt.value = false
  editing.value = false
  if (convertedDataMode.value) {
    convertedDataMode.value = false
    csv.value = null
    loadSpreadsheet()
  }
}
async function openConvertedData() {
  officePrompt.value = false
  editing.value = false
  convertedDataMode.value = true
  convertedPart.value = conversionResult.value?.parts[0]?.sequenceNumber ?? 1
  csvPage.value = 0
  csvCursors.value = [undefined]
  spreadsheet.value = null
  await loadCsv()
}
function stopConversionPolling() { window.clearTimeout(conversionPollTimer); conversionPollTimer = 0 }
async function pollConversion() {
  stopConversionPolling()
  try {
    conversionResult.value = await api.getSpreadsheetCsvConversion(props.resource.id)
    const status = conversionResult.value.status
    conversionLoading.value = status === 'QUEUED' || status === 'RUNNING'
    conversionError.value = status === 'FAILED' ? conversionResult.value.errorMessage || '转换没有完成，请重试' : ''
    if (conversionLoading.value) conversionPollTimer = window.setTimeout(pollConversion, 1000)
  } catch (reason) {
    conversionLoading.value = false
    conversionError.value = reason instanceof Error ? reason.message : '转换状态暂时无法读取'
  }
}
async function convertToCsv() {
  conversionLoading.value = true; conversionError.value = ''; conversionResult.value = null
  try {
    conversionResult.value = await api.convertSpreadsheetToCsv(props.resource.id)
    conversionPollTimer = window.setTimeout(pollConversion, 500)
  } catch (reason) {
    conversionError.value = reason instanceof Error ? reason.message : '转换没有完成，请稍后重试'
    conversionLoading.value = false
  }
}
onBeforeUnmount(stopConversionPolling)
async function loadWebPreview(refresh = false) {
  if (!isWebUrl.value) return
  webLoading.value = true; error.value = ''
  try { webPreview.value = await api.getWebPreview(props.resource.projectId, props.resource.id, refresh) }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '网站内容没有打开' }
  finally { webLoading.value = false }
}
async function openWebReader() {
  webOriginalMode.value = false
  if (!webPreview.value) await loadWebPreview()
}
async function openOriginalWeb() {
  webOriginalMode.value = true
  if (webEmbedStatus.value.status === 'CHECKING') await checkWebEmbedding()
}
async function checkWebEmbedding() {
  const resourceId = props.resource.id
  webEmbedOverride.value = false
  webEmbedStatus.value = { status: 'CHECKING', reason: '' }
  try {
    const result = await api.getWebEmbedStatus(props.resource.projectId, resourceId)
    if (props.resource.id === resourceId) webEmbedStatus.value = result
  } catch {
    if (props.resource.id === resourceId) webEmbedStatus.value = { status: 'UNKNOWN', reason: 'Studio 服务暂时无法连接该网站，可能受到网络限制或被目标网站拒绝访问' }
  }
}
async function loadCitations() {
  citations.value = []; citationError.value = ''
  if (props.resource.resourceType !== 'DELIVERABLE') return
  try { citations.value = await api.getDeliverableCitations(props.resource.id, props.resource.currentVersion) }
  catch (reason) { citationError.value = reason instanceof Error ? reason.message : '引用信息没有加载成功' }
}

async function downloadCurrent() {
  downloadLoading.value = true; downloadMessage.value = ''
  try { await downloadFile(sourceKind.value, props.resource.id, props.resource.name) }
  catch (reason) { downloadMessage.value = reason instanceof Error ? reason.message : '文件下载失败' }
  finally { downloadLoading.value = false }
}
async function loadCatalog() {
  if (!connection.value || connection.value.sourceType === 'HTTP_API') return
  catalogLoading.value = true
  try {
    catalog.value = await api.getConnectionCatalog(connection.value.id)
    expandedSchemas.value = catalog.value.schemas.length <= 8 ? catalog.value.schemas.map(item => item.technicalName) : catalog.value.schemas.slice(0, 1).map(item => item.technicalName)
  } catch (reason) { connectionMessageError.value = true; connectionMessage.value = reason instanceof Error ? reason.message : '数据目录没有加载成功' }
  finally { catalogLoading.value = false }
}
function toggleSchema(name: string) {
  expandedSchemas.value = expandedSchemas.value.includes(name) ? expandedSchemas.value.filter(item => item !== name) : [...expandedSchemas.value, name]
}
async function selectTable(table: DatabaseTable) {
  selectedTable.value = table
  previewQuery.value = table.previewQuery
  await previewData()
}
function beginConnectionEdit() {
  if (!connection.value) return
  const parsed = connection.value.sourceType === 'HTTP_API' ? null : parseDatabaseConnection(connection.value.jdbcUrl, connection.value.sourceType as DatabaseSourceType)
  connectionDraft.value = {
    name: connection.value.name, sourceType: connection.value.sourceType, connectionText: '', jdbcUrl: connection.value.jdbcUrl,
    host: parsed?.host ?? '', port: parsed?.port ?? '', database: parsed?.database ?? '', parameters: parsed?.parameters ?? '',
    username: connection.value.username, secretRef: '', method: connection.value.options.method || 'GET', format: connection.value.options.format || 'json', body: connection.value.options.body || ''
  }
  connectionEditing.value = true; connectionMessage.value = ''; connectionMessageError.value = false; connectionEditRecognition.value = ''; connectionPasswordVisible.value = false
}
function recognizeEditedConnection(raw = connectionDraft.value.connectionText) {
  if (connectionDraft.value.sourceType === 'HTTP_API') return false
  const parsed = parseDatabaseConnection(raw, connectionDraft.value.sourceType as DatabaseSourceType)
  if (!parsed) { connectionEditRecognition.value = '没有识别出完整连接信息，请检查连接串或手动填写'; return false }
  connectionDraft.value.sourceType = parsed.sourceType
  connectionDraft.value.jdbcUrl = parsed.jdbcUrl
  connectionDraft.value.host = parsed.host
  connectionDraft.value.port = parsed.port
  connectionDraft.value.database = parsed.database
  connectionDraft.value.parameters = parsed.parameters
  if (parsed.username) connectionDraft.value.username = parsed.username
  if (parsed.password) connectionDraft.value.secretRef = parsed.password
  connectionEditRecognition.value = `已识别 ${parsed.sourceType}，请确认下方各项信息`
  return true
}
function pasteEditedConnection(event: ClipboardEvent) {
  const value = event.clipboardData?.getData('text') ?? ''
  if (!value) return
  connectionDraft.value.connectionText = value
  recognizeEditedConnection(value)
  event.preventDefault()
}
function editedConnectionTypeChanged() {
  if (connectionDraft.value.sourceType === 'HTTP_API') { if (!connectionDraft.value.jdbcUrl.startsWith('http')) connectionDraft.value.jdbcUrl = 'https://'; connectionEditRecognition.value = ''; return }
  if (connectionDraft.value.sourceType === 'DUCKDB') { connectionDraft.value.host = ''; connectionDraft.value.port = ''; connectionDraft.value.database ||= '/path/to/data.duckdb' }
  else { connectionDraft.value.host ||= 'localhost'; connectionDraft.value.port = defaultDatabasePort(connectionDraft.value.sourceType as DatabaseSourceType); if (connectionDraft.value.database.startsWith('/')) connectionDraft.value.database = 'database' }
  connectionEditRecognition.value = ''
}
async function saveConnection() {
  if (!connection.value) return
  connectionSaving.value = true; connectionMessage.value = ''; connectionMessageError.value = false
  try {
    const options = connectionDraft.value.sourceType === 'HTTP_API' ? { ...connection.value.options, method: connectionDraft.value.method, format: connectionDraft.value.format, body: connectionDraft.value.body } : connection.value.options
    const jdbcUrl = connectionDraft.value.sourceType === 'HTTP_API' ? connectionDraft.value.jdbcUrl : buildDatabaseJdbcUrl({ ...connectionDraft.value, sourceType: connectionDraft.value.sourceType as DatabaseSourceType })
    connection.value = await api.updateConnection(connection.value.id, { name: connectionDraft.value.name, sourceType: connectionDraft.value.sourceType, jdbcUrl, username: connectionDraft.value.username, secretRef: connectionDraft.value.secretRef, options })
    const checked = await api.testConnection(connection.value.id)
    connection.value.status = checked.success ? 'READY' : 'FAILED'
    if (!checked.success) { connectionMessageError.value = true; connectionMessage.value = `已保存修改，但连接失败：${checked.message}`; return }
    connectionEditing.value = false; catalog.value = null; connectionPreview.value = null; selectedTable.value = null
    previewQuery.value = connection.value.sourceType === 'HTTP_API' ? '' : defaultPreviewQuery(connection.value)
    if (connection.value.sourceType !== 'HTTP_API') await loadCatalog()
    connectionMessage.value = `连接已保存并验证通过（${checked.latencyMs} ms）`
  } catch (reason) { connectionMessageError.value = true; connectionMessage.value = reason instanceof Error ? reason.message : '连接信息没有保存' }
  finally { connectionSaving.value = false }
}
watch(() => props.resource.id, () => {
  stopConversionPolling()
  conversionLoading.value = false
  conversionError.value = ''
  conversionResult.value = null
  convertedDataMode.value = false
  convertedPart.value = 1
  csvPage.value = 0
  csvCursors.value = [undefined]
  editing.value = false
  officeFallback.value = false
  officePrompt.value = false
  webOriginalMode.value = false
  webEmbedOverride.value = false
  webPreview.value = null
  citationsOpen.value = false
  spreadsheetSheet.value = ''
  spreadsheetPage.value = 0
  spreadsheetCursors.value = [undefined]
  load()
  loadCitations()
}, { immediate: true })
watch(editing, value => { if (!value) load() })
function officeUnavailable(mode: 'view' | 'edit') { if (mode === 'view') officeFallback.value = true }
function defaultPreviewQuery(item: DataConnection) {
  const configured = item.options.previewQuery || item.options.query || ''
  return configured === '已配置' ? '' : configured
}
function sourceName(type: string) {
  return ({ POSTGRESQL: 'PostgreSQL', MYSQL: 'MySQL', OPENGAUSS: 'openGauss', GAUSS_DWS: 'GaussDB(DWS)', DUCKDB: 'DuckDB', HTTP_API: '数据服务 API' } as Record<string, string>)[type] ?? type
}
function optionLabel(key: string) {
  return ({ format: '返回格式', method: '请求方式', schema: '默认模式', database: '数据库' } as Record<string, string>)[key] ?? key.replace(/^header\./, '请求头 · ')
}
async function testConnection() {
  if (!connection.value) return
  testLoading.value = true; connectionMessage.value = ''; connectionMessageError.value = false
  try {
    const result = await api.testConnection(connection.value.id)
    connectionMessage.value = `${result.message}${result.latencyMs ? ` · ${result.latencyMs} ms` : ''}`
    connection.value.status = result.success ? 'READY' : 'FAILED'
    connectionMessageError.value = !result.success
  } catch (reason) { connectionMessageError.value = true; connectionMessage.value = reason instanceof Error ? reason.message : '连接检查没有完成' }
  finally { testLoading.value = false }
}
async function previewData() {
  if (!connection.value) return
  if (connection.value.sourceType !== 'HTTP_API' && !previewQuery.value.trim()) {
    connectionMessage.value = '请先输入一条 SELECT 或 WITH 查询'; return
  }
  previewLoading.value = true; connectionMessage.value = ''; connectionMessageError.value = false; connectionPreview.value = null
  try { connectionPreview.value = await api.previewConnection(connection.value.id, previewQuery.value.trim(), 100) }
  catch (reason) { connectionMessageError.value = true; connectionMessage.value = reason instanceof Error ? reason.message : '数据预览没有完成' }
  finally { previewLoading.value = false }
}
</script>

<template>
  <section class="resource-workbench" :class="{ 'has-office-prompt': officePrompt, 'native-workbench': isNative }">
    <header v-if="!(compact && isNative)" class="resource-toolbar">
      <div><span>{{ resource.group === 'DATA' ? '数据' : resource.group === 'OUTPUT' ? '输出件' : '资料' }}</span><strong>{{ resource.name }}</strong><small v-if="location" class="resource-location">{{ location }}</small></div>
      <nav>
        <div v-if="isEditable" class="view-mode-switch"><button type="button" :class="{ active: !editing }" title="查看" @click="useLargeDataMode"><Eye :size="15"/></button><button type="button" :class="{ active: editing }" :title="spreadsheetAccess === 'CONVERT' ? '转换为 CSV 后使用' : '在线编辑'" @click="requestOnlineEdit"><FilePenLine :size="15"/></button></div>
        <button class="secondary-button" type="button" @click="$emit('useAsInput', resource)"><Plus :size="15"/>用于当前工作</button>
        <a v-if="isWebUrl" class="secondary-button" :href="resource.url" target="_blank" rel="noopener noreferrer"><ExternalLink :size="15"/>新窗口打开</a>
        <button v-else-if="!isConnection && !isNative" class="secondary-button" type="button" :disabled="downloadLoading" @click="downloadCurrent"><LoaderCircle v-if="downloadLoading" class="spin" :size="15"/><Download v-else :size="15"/>{{ downloadLoading ? '正在下载' : '下载' }}</button>
        <details class="resource-more"><summary aria-label="更多文件操作">更多</summary><button class="text-button danger" type="button" @click="$emit('deleteResource', resource)"><Trash2 :size="16"/>删除文件及历史版本</button></details>
        <button v-if="!isNative" class="icon-button" type="button" title="刷新内容" @click="load"><RefreshCw :size="17"/></button>
      </nav>
    </header>
    <aside v-if="downloadMessage" class="resource-download-message"><span>{{ downloadMessage }}</span><button type="button" title="关闭" @click="downloadMessage = ''"><X :size="14"/></button></aside>
    <aside v-if="officePrompt" class="spreadsheet-office-prompt">
      <AlertTriangle :size="19"/>
      <div v-if="spreadsheetAccess === 'CONFIRM'"><strong>这个工作簿较大</strong><p>文件大小为 {{ formatFileSize(resource.sizeBytes) }}。可以在线编辑，但首次打开、筛选和保存可能需要更长时间。</p></div>
      <div v-else><strong>转换前请确认可能的变化</strong><p>文件大小为 {{ formatFileSize(resource.sizeBytes) }}，超过 100 MB。为避免浏览器卡顿，需要转换成 CSV 后使用。</p><ul><li>每个工作表会在文件内部单独转换，超过 250,000 行会继续拆分。</li><li>CSV 保留单元格当前显示的数据，不保留公式、样式、合并单元格、图表、图片和宏。</li><li>目录中仍然只显示这个文件；原 Excel 完整保留，CSV 分片作为内部数据副本使用。</li></ul><p v-if="conversionLoading" class="conversion-progress"><LoaderCircle class="spin" :size="15"/>正在后台转换，已处理 {{ (conversionResult?.processedRows ?? 0).toLocaleString() }} 行。可以离开当前页面。</p><p v-else-if="conversionError" class="conversion-error">{{ conversionError }}</p><p v-else-if="conversionResult?.status === 'SUCCEEDED'" class="conversion-success"><CheckCircle2 :size="15"/>转换完成：内部生成 {{ conversionResult.partCount }} 个分片，共 {{ conversionResult.processedRows.toLocaleString() }} 行，目录中不会新增文件。</p></div>
      <nav><button class="secondary-button" type="button" @click="useLargeDataMode">{{ conversionResult?.status === 'SUCCEEDED' ? '查看原表' : '继续分页查看' }}</button><button v-if="spreadsheetAccess === 'CONFIRM'" class="primary-button" type="button" @click="continueOfficeEdit">继续在线编辑</button><button v-else-if="conversionResult?.status === 'SUCCEEDED'" class="primary-button" type="button" @click="openConvertedData">查看转换数据</button><button v-else class="primary-button" type="button" :disabled="conversionLoading" @click="convertToCsv"><LoaderCircle v-if="conversionLoading" class="spin" :size="15"/><span>{{ conversionLoading ? '后台转换中' : '确认并转换为 CSV' }}</span></button></nav>
    </aside>
    <aside v-if="(citations.length || citationError) && !isFinancialReport" class="deliverable-citation-strip" :class="{ error: citationError, open: citationsOpen }">
      <button type="button" class="citation-strip-trigger" :disabled="!!citationError" @click="citationsOpen = !citationsOpen"><BookOpen :size="14"/><span>{{ citationError || '引用来源' }}</span><strong v-if="citations.length">{{ citations.length }}</strong><ChevronDown v-if="citations.length" :size="13"/></button>
      <section v-if="citationsOpen && citations.length" class="citation-strip-panel"><header><div><small>引用来源</small><strong>点击查看原文片段</strong></div><button type="button" title="关闭" @click="citationsOpen = false"><X :size="14"/></button></header><div><CitationAnchor v-for="(citation, index) in citations" :key="citation.id" compact :citation="citation" :label="`[${index + 1}] ${citation.source_name}`" @open-source="$emit('openSource', $event)"/></div></section>
    </aside>

    <div v-if="loading" class="resource-state"><LoaderCircle class="spin" :size="22"/>正在打开内容</div>
    <div v-else-if="error" class="resource-state error"><FileText :size="24"/><strong>暂时无法显示</strong><p>{{ error }}</p></div>
    <section v-else-if="isConnection && connection" class="connection-detail-workbench">
      <header class="connection-summary">
        <div class="connection-mark"><Braces v-if="connection.sourceType === 'HTTP_API'" :size="21"/><Database v-else :size="21"/></div>
        <div><h2>{{ connection.name }}</h2><p>{{ sourceName(connection.sourceType) }} · {{ connection.status === 'READY' ? '连接正常' : connection.status === 'FAILED' ? '连接失败' : '尚未检查' }}</p></div>
        <div class="connection-actions"><button class="secondary-button" type="button" @click="beginConnectionEdit"><Pencil :size="15"/>编辑</button><button class="secondary-button" type="button" :disabled="testLoading" @click="testConnection"><LoaderCircle v-if="testLoading" class="spin" :size="15"/><CheckCircle2 v-else :size="15"/>检查连接</button><button class="secondary-button" type="button" @click="$emit('manageData')"><Server :size="15"/>数据采集</button></div>
      </header>

      <section v-if="connectionEditing" class="connection-edit-section">
        <header><div><h3>编辑连接</h3><p>修改后需要重新检查连接</p></div><button class="icon-button" type="button" title="取消" @click="connectionEditing = false"><X :size="16"/></button></header>
        <div class="connection-edit-grid">
          <label v-if="connectionDraft.sourceType !== 'HTTP_API'" class="wide connection-import-field"><span>粘贴新连接串，自动识别</span><div class="connection-import-control"><ClipboardPaste :size="17"/><input v-model="connectionDraft.connectionText" placeholder="粘贴后自动拆分到下方各项" @paste="pasteEditedConnection"><button type="button" @click="recognizeEditedConnection()">识别</button></div><small v-if="connectionEditRecognition" class="field-help" :class="{ 'connection-recognized': connectionEditRecognition.startsWith('已识别') }">{{ connectionEditRecognition }}</small><small v-else class="field-help">不粘贴也可直接修改下方已拆分的信息。</small></label>
          <label><span>名称</span><input v-model="connectionDraft.name"></label>
          <label><span>类型</span><select v-model="connectionDraft.sourceType" @change="editedConnectionTypeChanged"><option value="POSTGRESQL">PostgreSQL</option><option value="MYSQL">MySQL</option><option value="OPENGAUSS">openGauss</option><option value="GAUSS_DWS">GaussDB(DWS)</option><option value="DUCKDB">DuckDB</option><option value="HTTP_API">数据服务 API</option></select></label>
          <template v-if="connectionDraft.sourceType === 'HTTP_API'"><label class="wide"><span>接口地址</span><input v-model="connectionDraft.jdbcUrl"></label><label><span>访问令牌或密钥</span><div class="password-input"><input v-model="connectionDraft.secretRef" :type="connectionPasswordVisible ? 'text' : 'password'" autocomplete="new-password" placeholder="留空保留原配置"><button type="button" :title="connectionPasswordVisible ? '隐藏密码' : '显示密码'" :aria-label="connectionPasswordVisible ? '隐藏密码' : '显示密码'" @click="connectionPasswordVisible = !connectionPasswordVisible"><EyeOff v-if="connectionPasswordVisible" :size="16"/><Eye v-else :size="16"/></button></div></label><label><span>请求方式</span><select v-model="connectionDraft.method"><option>GET</option><option>POST</option></select></label><label><span>返回格式</span><select v-model="connectionDraft.format"><option value="json">JSON</option><option value="jsonl">JSON Lines</option><option value="csv">CSV</option></select></label><label v-if="connectionDraft.method === 'POST'" class="wide"><span>请求内容</span><textarea v-model="connectionDraft.body" rows="4" placeholder="输入 JSON 请求内容"></textarea></label></template>
          <template v-else-if="connectionDraft.sourceType !== 'DUCKDB'"><label><span>主机地址</span><input v-model="connectionDraft.host"></label><label><span>端口</span><input v-model="connectionDraft.port" inputmode="numeric"></label><label class="wide"><span>数据库名</span><input v-model="connectionDraft.database"></label><label><span>用户名</span><input v-model="connectionDraft.username"></label><label><span>密码</span><div class="password-input"><input v-model="connectionDraft.secretRef" :type="connectionPasswordVisible ? 'text' : 'password'" autocomplete="new-password" placeholder="留空保留原配置，填写后加密保存"><button type="button" :title="connectionPasswordVisible ? '隐藏密码' : '显示密码'" :aria-label="connectionPasswordVisible ? '隐藏密码' : '显示密码'" @click="connectionPasswordVisible = !connectionPasswordVisible"><EyeOff v-if="connectionPasswordVisible" :size="16"/><Eye v-else :size="16"/></button></div></label><label class="wide"><span>连接参数（可选）</span><input v-model="connectionDraft.parameters" placeholder="sslmode=require"></label></template>
          <label v-else class="wide"><span>DuckDB 文件路径</span><input v-model="connectionDraft.database"></label>
        </div>
        <p v-if="connectionMessage" class="connection-message" :class="{ error: connectionMessageError }">{{ connectionMessage }}</p>
        <footer><button class="primary-button" type="button" :disabled="connectionSaving" @click="saveConnection"><LoaderCircle v-if="connectionSaving" class="spin" :size="15"/><Save v-else :size="15"/>{{ connectionSaving ? '正在验证连接' : '保存修改' }}</button></footer>
      </section>

      <section v-else class="connection-config-section">
        <h3>连接信息</h3>
        <dl class="connection-config-grid">
          <div><dt>类型</dt><dd>{{ sourceName(connection.sourceType) }}</dd></div>
          <div><dt>{{ connection.sourceType === 'HTTP_API' ? '请求方式' : '账号' }}</dt><dd>{{ connection.sourceType === 'HTTP_API' ? (connection.options.method || 'GET') : (connection.username || '未填写') }}</dd></div>
          <div class="wide"><dt>{{ connection.sourceType === 'HTTP_API' ? '接口地址' : '连接地址' }}</dt><dd>{{ connection.jdbcUrl }}</dd></div>
          <div><dt>访问凭据</dt><dd>{{ connection.secretRef || '未配置' }}</dd></div>
          <div v-for="(value, key) in connection.options" :key="key" v-show="key !== 'method' && key !== 'previewQuery' && key !== 'query'"><dt>{{ optionLabel(key) }}</dt><dd>{{ value || '未填写' }}</dd></div>
          <div v-if="!Object.keys(connection.options).length"><dt>附加参数</dt><dd>无</dd></div>
        </dl>
      </section>

      <section v-if="connection.sourceType !== 'HTTP_API'" class="database-catalog-section">
        <header><div><h3>选择要使用的数据</h3><p>按数据分类浏览，点击一张表即可查看样例</p></div><label class="catalog-search"><Search :size="15"/><input v-model="catalogSearch" placeholder="搜索数据表"></label></header>
        <div v-if="catalogLoading" class="catalog-state"><LoaderCircle class="spin" :size="19"/>正在读取数据目录</div>
        <div v-else-if="catalog" class="database-catalog">
          <div class="catalog-overview">共 {{ catalog.schemas.length }} 个数据分类、{{ catalog.tableCount }} 张数据表<span v-if="catalog.truncated">，目录较大，已显示前 10,000 张</span></div>
          <div v-if="!filteredSchemas.length" class="catalog-state">没有找到匹配的数据表</div>
          <section v-for="schema in filteredSchemas" :key="schema.technicalName" class="catalog-schema">
            <button type="button" class="catalog-schema-toggle" @click="toggleSchema(schema.technicalName)"><ChevronDown v-if="expandedSchemas.includes(schema.technicalName) || catalogSearch" :size="16"/><ChevronRight v-else :size="16"/><span><strong>{{ schema.name }}</strong><small v-if="schema.name !== schema.technicalName">库内名称：{{ schema.technicalName }}</small></span><em>{{ schema.tables.length }} 张</em></button>
            <div v-if="expandedSchemas.includes(schema.technicalName) || catalogSearch" class="catalog-tables">
              <button v-for="table in schema.tables" :key="`${table.catalog}-${table.schema}-${table.name}`" type="button" :class="{ active: selectedTable?.name === table.name && selectedTable?.schema === table.schema }" @click="selectTable(table)"><Database :size="15"/><span><strong>{{ table.name }}</strong><small>{{ table.description || (table.tableType === 'VIEW' ? '数据视图' : '数据表') }}</small></span><Eye :size="14"/></button>
            </div>
          </section>
        </div>
      </section>

      <section class="connection-preview-section">
        <header><div><h3>{{ selectedTable ? `数据预览 · ${selectedTable.name}` : '数据预览' }}</h3><p>{{ connection.sourceType === 'HTTP_API' ? '读取接口当前返回的数据' : selectedTable ? '用样例数据确认这张表是否符合需求' : '先从上方选择一张数据表' }}</p></div><button v-if="connection.sourceType === 'HTTP_API' || previewQuery" class="primary-button" type="button" :disabled="previewLoading" @click="previewData"><LoaderCircle v-if="previewLoading" class="spin" :size="15"/><Play v-else :size="15"/>{{ previewLoading ? '正在取数' : '重新预览' }}</button></header>
        <details v-if="connection.sourceType !== 'HTTP_API'" class="advanced-query"><summary>高级查询</summary><p>仅支持只读查询，一般情况下无需修改。</p><textarea v-model="previewQuery" rows="4" spellcheck="false" placeholder="例如：SELECT * FROM inventory"></textarea></details>
        <p v-if="connectionMessage && !connectionEditing" class="connection-message" :class="{ error: connectionMessageError }">{{ connectionMessage }}</p>
        <div v-if="connectionPreview" class="connection-preview-table">
          <table><thead><tr><th class="row-number">#</th><th v-for="(column, index) in connectionPreview.columns" :key="`${column}-${index}`">{{ column || `第 ${index + 1} 列` }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in connectionPreview.rows" :key="rowIndex"><td class="row-number">{{ rowIndex + 1 }}</td><td v-for="(_, columnIndex) in connectionPreview.columns" :key="columnIndex" :title="row[columnIndex]">{{ row[columnIndex] }}</td></tr></tbody></table>
          <footer>已显示 {{ connectionPreview.rowCount }} 行<span v-if="connectionPreview.truncated">，还有更多数据</span></footer>
        </div>
        <div v-else-if="!previewLoading" class="connection-preview-empty"><Eye :size="23"/><span>点击“预览数据”查看实际取数结果</span></div>
      </section>
    </section>
    <section v-else-if="isWebUrl" class="web-url-work-area">
      <header><Globe2 :size="18"/><span>{{ resource.url }}</span><div class="web-preview-modes"><button type="button" :class="{ active: !webOriginalMode }" @click="openWebReader">快速阅读</button><button type="button" :class="{ active: webOriginalMode }" @click="openOriginalWeb">原网页</button><a :href="resource.url" target="_blank" rel="noopener noreferrer"><ExternalLink :size="14"/>新窗口</a></div></header>
      <div v-if="webOriginalMode && webEmbedStatus.status === 'CHECKING'" class="web-url-loading"><LoaderCircle class="spin" :size="21"/><strong>正在检查网页访问条件</strong><p>确认该网页是否允许在 Studio 内显示。</p></div>
      <div v-else-if="webOriginalMode && !webEmbedOverride && ['BLOCKED', 'UNKNOWN'].includes(webEmbedStatus.status)" class="web-embed-blocked"><ShieldAlert :size="28"/><strong>该网页无法在 Studio 内打开</strong><p>{{ webEmbedStatus.reason }}。你仍可以快速阅读已提取的正文，或在浏览器中访问完整原文。</p><div><button class="secondary-button" type="button" @click="openWebReader">快速阅读</button><button v-if="webEmbedStatus.status === 'UNKNOWN'" class="secondary-button" type="button" @click="webEmbedOverride = true">仍尝试打开</button><a class="primary-button" :href="resource.url" target="_blank" rel="noopener noreferrer"><ExternalLink :size="15"/>在浏览器中打开</a></div></div>
      <div v-else-if="webOriginalMode" class="web-url-frame"><iframe :src="resource.url" :title="resource.name" referrerpolicy="strict-origin-when-cross-origin"></iframe></div>
      <div v-else-if="webLoading" class="web-url-loading"><LoaderCircle class="spin" :size="21"/><strong>正在整理网页内容</strong><p>首次读取后会缓存，之后打开会更快。</p></div>
      <div v-else-if="error" class="resource-state error"><Globe2 :size="24"/><strong>网站内容暂时无法读取</strong><p>{{ error }}</p><a class="secondary-button" :href="resource.url" target="_blank" rel="noopener noreferrer"><ExternalLink :size="14"/>新窗口打开</a></div>
      <article v-else-if="webPreview" class="web-reader">
        <header><span>{{ webPreview.siteName }}</span><em>{{ webPreview.previewMode === 'CURATED' ? `已核验${webPreview.verifiedAt ? ` · ${webPreview.verifiedAt}` : ''}` : '实时正文' }}</em></header>
        <h1>{{ webPreview.title }}</h1>
        <p v-for="paragraph in webPreview.summary.split('\n').filter(Boolean)" :key="paragraph" class="web-reader-summary">{{ paragraph }}</p>
        <section v-if="webPreview.facts?.length" class="web-reader-facts"><h2>关键信息</h2><dl><div v-for="fact in webPreview.facts" :key="`${fact.label}-${fact.value}`"><dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd></div></dl></section>
        <section v-if="webPreview.highlights.length" class="web-reader-highlights"><h2>关键内容</h2><ul><li v-for="item in webPreview.highlights" :key="item">{{ item }}</li></ul></section>
        <section v-for="section in webPreview.sections" :key="section.heading" class="web-reader-section"><h2>{{ section.heading }}</h2><p v-for="paragraph in section.paragraphs" :key="paragraph">{{ paragraph }}</p></section>
        <section v-if="webPreview.links?.length" class="web-reader-links"><h2>相关入口</h2><div><a v-for="link in webPreview.links" :key="link.url" :href="link.url" target="_blank" rel="noopener noreferrer"><span>{{ link.label }}</span><ExternalLink :size="13"/></a></div></section>
        <footer><button class="secondary-button" type="button" @click="loadWebPreview(true)"><RefreshCw :size="14"/>读取最新正文</button><a :href="resource.url" target="_blank" rel="noopener noreferrer">查看官方原文<ExternalLink :size="13"/></a></footer>
      </article>
    </section>
    <ResearchReader v-else-if="document?.kind === 'research'" :document="document" />
    <section v-else-if="convertedDataMode && csv" class="spreadsheet-pager csv-pager">
      <header><div><strong>转换数据</strong><span v-if="csv.rows.length">第 {{ csv.rowOffset + 1 }} - {{ csv.rowOffset + csv.rows.length }} 行 · 当前分片共 {{ (conversionResult?.parts.find(part => part.sequenceNumber === convertedPart)?.rowCount ?? 0).toLocaleString() }} 行</span><span v-else>暂无数据</span><small>这是当前文件内部的数据副本，原 Excel 未改变</small></div><label v-if="(conversionResult?.parts.length ?? 0) > 1"><span>工作表 / 分片</span><select v-model.number="convertedPart" @change="changeConvertedPart"><option v-for="part in conversionResult?.parts ?? []" :key="part.sequenceNumber" :value="part.sequenceNumber">{{ part.sheetName }} · 第 {{ part.partNumber }} 段</option></select></label><button class="secondary-button" type="button" @click="useLargeDataMode">查看原 Excel</button></header>
      <div class="inline-csv"><table><thead><tr><th class="row-number">#</th><th v-for="(column, index) in csv.columns" :key="`${column}-${index}`">{{ column || `第 ${index + 1} 列` }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in csv.rows" :key="rowIndex"><td class="row-number">{{ csv.rowOffset + rowIndex + 1 }}</td><td v-for="(_, columnIndex) in csv.columns" :key="columnIndex" :title="row[columnIndex]">{{ row[columnIndex] }}</td></tr></tbody></table></div>
      <footer><span>每页最多 100 行，仅加载当前页</span><div><button class="icon-button" type="button" title="上一页" :disabled="csvPage === 0 || loading" @click="previousCsvPage"><ChevronLeft :size="18"/></button><span>第 {{ csvPage + 1 }} / {{ Math.max(1, Math.ceil((conversionResult?.parts.find(part => part.sequenceNumber === convertedPart)?.rowCount ?? csv.rows.length) / 100)) }} 页</span><button class="icon-button" type="button" title="下一页" :disabled="!csv.hasMore || loading" @click="nextCsvPage"><ChevronRight :size="18"/></button></div></footer>
    </section>
    <NativeDocument v-else-if="isNative" :key="resource.id" :resource-id="resource.id" :project-id="resource.projectId" :kind="resource.resourceType === 'DELIVERABLE' ? 'deliverables' : 'files'" @saved="emit('resourcesChanged')" @state="emit('documentState', $event)" @open-reference="openDocumentReference"><template v-if="compact" #actions><button title="用于当前工作" aria-label="用于当前工作" @click="emit('useAsInput', resource)"><Plus :size="16"/></button><button title="删除文件及历史版本" aria-label="删除文件及历史版本" @click="emit('deleteResource', resource)"><Trash2 :size="16"/></button></template></NativeDocument>
    <section v-else-if="isPaginatedSpreadsheet && !editing && spreadsheet" class="spreadsheet-pager">
      <header><div><strong>{{ spreadsheetAccess === 'CONVERT' ? '大型表格预览' : '数据预览' }}</strong><span v-if="spreadsheet.rows.length">第 {{ spreadsheet.rowOffset + 1 }} - {{ spreadsheet.rowOffset + spreadsheet.rows.length }} 行 · 共 {{ spreadsheet.totalRows.toLocaleString() }} 行</span><span v-else>暂无数据</span><small v-if="spreadsheetAccess === 'CONVERT'">当前仅分页预览；需要加工时可使用转换后的数据副本</small></div><div class="spreadsheet-header-actions"><label><span>工作表</span><select v-model="spreadsheetSheet" @change="changeSpreadsheetSheet"><option v-for="sheet in spreadsheet.sheets" :key="sheet" :value="sheet">{{ sheet }}</option></select></label><button v-if="conversionResult?.status === 'SUCCEEDED'" class="secondary-button" type="button" @click="openConvertedData">查看转换数据</button></div></header>
      <div class="inline-csv"><table><thead><tr><th class="row-number">#</th><th v-for="(column, index) in spreadsheet.columns" :key="`${column}-${index}`">{{ column || `第 ${index + 1} 列` }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in spreadsheet.rows" :key="rowIndex"><td class="row-number">{{ spreadsheet.rowOffset + rowIndex + 1 }}</td><td v-for="(_, columnIndex) in spreadsheet.columns" :key="columnIndex" :title="row[columnIndex]">{{ row[columnIndex] }}</td></tr></tbody></table></div>
      <footer><span>每页 {{ spreadsheet.pageSize }} 行，仅加载当前页</span><div><button class="icon-button" type="button" title="上一页" :disabled="spreadsheetPage === 0 || loading" @click="previousSpreadsheetPage"><ChevronLeft :size="18"/></button><span>第 {{ spreadsheetPage + 1 }} / {{ Math.max(1, spreadsheet.totalPages) }} 页</span><button class="icon-button" type="button" title="下一页" :disabled="!spreadsheet.hasMore || loading" @click="nextSpreadsheetPage"><ChevronRight :size="18"/></button></div></footer>
    </section>
    <iframe v-else-if="isOfficeDocument && !editing && officeFallback" class="inline-pdf" :src="renderedOfficePreviewUrl(officeKind === 'deliverables' ? 'deliverables' : 'files', resource.id, resource.currentVersion)" :title="resource.name"></iframe>
    <OfficeEditor v-else-if="isOfficeDocument" :key="`${resource.id}-${resource.currentVersion}-${editing ? 'edit' : 'view'}`" :resource-id="resource.id" :kind="officeKind" :mode="editing ? 'edit' : 'view'" @unavailable="officeUnavailable" />
    <OfficeEditor v-else-if="editing && isEditable" :key="`${resource.id}-${resource.currentVersion}-edit`" :resource-id="resource.id" :kind="officeKind" mode="edit" />
    <img v-else-if="isRasterImage" :src="`${inlineContentUrl(resource.id)}?version=${resource.currentVersion}`" :alt="resource.name" style="display:block;max-width:100%;height:auto;object-fit:contain" @error="error = '图片加载失败，请重新打开或下载原文件'" />
    <iframe v-else-if="isPdf" class="inline-pdf" :src="resource.resourceType === 'DELIVERABLE' ? deliverableContentUrl(resource.id) : inlineContentUrl(resource.id)" :title="resource.name"></iframe>
    <InteractiveFinancialReport v-else-if="isFinancialReport" :project-id="resource.projectId" :deliverable-id="resource.id" :report-name="resource.name" @open-source="$emit('openSource', $event)"/>
    <section v-else-if="isHtmlSlides" class="html-slides-workbench"><div class="html-slides-badge">网页演示</div><iframe :src="deliverableContentUrl(resource.id)" :title="resource.name" sandbox="allow-scripts" allow="fullscreen"></iframe></section>
    <section v-else-if="csv" class="spreadsheet-pager csv-pager">
      <header><div><strong>CSV 数据预览</strong><span v-if="csv.rows.length">第 {{ csv.rowOffset + 1 }} - {{ csv.rowOffset + csv.rows.length }} 行</span><span v-else>暂无数据</span><small>仅加载当前页，打开大文件不会占满浏览器内存</small></div></header>
      <div class="inline-csv"><table><thead><tr><th class="row-number">#</th><th v-for="(column, index) in csv.columns" :key="`${column}-${index}`">{{ column || `第 ${index + 1} 列` }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in csv.rows" :key="rowIndex"><td class="row-number">{{ csv.rowOffset + rowIndex + 1 }}</td><td v-for="(_, columnIndex) in csv.columns" :key="columnIndex" :title="row[columnIndex]">{{ row[columnIndex] }}</td></tr></tbody></table></div>
      <footer><span>每页最多 100 行</span><div><button class="icon-button" type="button" title="上一页" :disabled="csvPage === 0 || loading" @click="previousCsvPage"><ChevronLeft :size="18"/></button><span>第 {{ csvPage + 1 }} 页</span><button class="icon-button" type="button" title="下一页" :disabled="!csv.hasMore || loading" @click="nextCsvPage"><ChevronRight :size="18"/></button></div></footer>
    </section>
    <DiagramPreview v-else-if="document && diagramType" :kind="diagramType" :source="diagramSource" />
    <div v-else-if="document" class="inline-document" :data-kind="document.kind"><p v-for="warning in document.warnings" :key="warning" class="preview-warning">{{ warning }}</p><article v-for="page in document.pages" :key="page.number" class="document-page"><span v-if="document.kind === 'presentation'" class="page-number">{{ page.number }}</span><h2 v-if="page.title">{{ page.title }}</h2><template v-for="(block, index) in page.blocks" :key="index"><h3 v-if="block.type === 'heading'">{{ block.text }}</h3><p v-else-if="block.type === 'text'">{{ block.text }}</p><div v-else class="table-wrap"><table><tbody><tr v-for="(row, rowIndex) in block.rows" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td></tr></tbody></table></div></template></article></div>
    <div v-else class="resource-state"><FileText :size="24"/><strong>文件已经就绪</strong><p>可下载原文件，或把它加入主工作流继续处理。</p></div>
  </section>
</template>
