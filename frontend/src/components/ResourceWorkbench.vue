<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { BookOpen, Braces, CheckCircle2, ChevronDown, ChevronRight, Database, Download, ExternalLink, Eye, FilePenLine, FileText, Globe2, LoaderCircle, Pencil, Play, Plus, RefreshCw, Save, Search, Server, ShieldAlert, Trash2, X } from 'lucide-vue-next'
import { api, deliverableContentUrl, downloadFile, inlineContentUrl, renderedOfficePreviewUrl, type CitationSource, type ConnectionPreview, type CsvPreview, type DataConnection, type DatabaseCatalog, type DatabaseTable, type DocumentPreview, type WebEmbedStatus, type WebPreview, type WorkspaceResource } from '../api/client'
import DiagramPreview from './DiagramPreview.vue'
import OfficeEditor from './OfficeEditor.vue'
import CitationAnchor from './CitationAnchor.vue'

const InteractiveFinancialReport = defineAsyncComponent(() => import('./InteractiveFinancialReport.vue'))

const props = defineProps<{ resource: WorkspaceResource }>()
defineEmits<{ addToWorkflow: [resource: WorkspaceResource]; manageData: []; deleteResource: [resource: WorkspaceResource]; openSource: [citation: CitationSource] }>()
const loading = ref(false), error = ref(''), editing = ref(false), officeFallback = ref(false), csv = ref<CsvPreview | null>(null), document = ref<DocumentPreview | null>(null)
const downloadLoading = ref(false), downloadMessage = ref('')
const webLoading = ref(false), webPreview = ref<WebPreview | null>(null), webOriginalMode = ref(true)
const webEmbedStatus = ref<WebEmbedStatus>({ status: 'CHECKING', reason: '' })
const connection = ref<DataConnection | null>(null), connectionPreview = ref<ConnectionPreview | null>(null)
const citations = ref<CitationSource[]>([]), citationError = ref('')
const citationsOpen = ref(false)
const previewQuery = ref(''), previewLoading = ref(false), testLoading = ref(false), connectionMessage = ref('')
const connectionEditing = ref(false), connectionSaving = ref(false), catalogLoading = ref(false), catalog = ref<DatabaseCatalog | null>(null)
const catalogSearch = ref(''), expandedSchemas = ref<string[]>([]), selectedTable = ref<DatabaseTable | null>(null)
const connectionDraft = ref({ name: '', sourceType: 'POSTGRESQL', jdbcUrl: '', username: '', secretRef: '', method: 'GET', format: 'json', body: '' })
const extension = computed(() => props.resource.name.includes('.') ? props.resource.name.split('.').pop()?.toLowerCase() ?? '' : props.resource.mediaType.toLowerCase())
const isConnection = computed(() => ['DATABASE_CONNECTION', 'API_CONNECTION'].includes(props.resource.resourceType))
const isWebUrl = computed(() => props.resource.resourceType === 'WEB_URL')
const isCsv = computed(() => props.resource.resourceType === 'DATASET' || ['csv', 'tsv'].includes(extension.value))
const isPdf = computed(() => extension.value === 'pdf')
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
  csv.value = null; document.value = null; connection.value = null; connectionPreview.value = null; catalog.value = null; selectedTable.value = null; error.value = ''; connectionMessage.value = ''
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
    checkWebEmbedding()
    if (!webOriginalMode.value) await loadWebPreview()
    return
  }
  if (isPdf.value || isOfficeDocument.value || isFinancialReport.value || isHtmlSlides.value || editing.value) return
  loading.value = true
  try {
    if (isCsv.value) csv.value = props.resource.resourceType === 'DATASET' ? await api.previewExtract(props.resource.id) : await api.previewFileCsv(props.resource.id)
    else document.value = props.resource.resourceType === 'DELIVERABLE' ? await api.previewDeliverable(props.resource.id) : await api.previewFile(props.resource.id)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '内容没有打开' }
  finally { loading.value = false }
}
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
async function checkWebEmbedding() {
  webEmbedStatus.value = { status: 'CHECKING', reason: '' }
  try { webEmbedStatus.value = await api.getWebEmbedStatus(props.resource.projectId, props.resource.id) }
  catch { webEmbedStatus.value = { status: 'UNKNOWN', reason: '暂时无法确认该网站是否允许在 Studio 内显示' } }
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
  } catch (reason) { connectionMessage.value = reason instanceof Error ? reason.message : '数据目录没有加载成功' }
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
  connectionDraft.value = {
    name: connection.value.name, sourceType: connection.value.sourceType, jdbcUrl: connection.value.jdbcUrl,
    username: connection.value.username, secretRef: '', method: connection.value.options.method || 'GET', format: connection.value.options.format || 'json', body: connection.value.options.body || ''
  }
  connectionEditing.value = true; connectionMessage.value = ''
}
async function saveConnection() {
  if (!connection.value) return
  connectionSaving.value = true; connectionMessage.value = ''
  try {
    const options = connectionDraft.value.sourceType === 'HTTP_API' ? { ...connection.value.options, method: connectionDraft.value.method, format: connectionDraft.value.format, body: connectionDraft.value.body } : connection.value.options
    connection.value = await api.updateConnection(connection.value.id, { ...connectionDraft.value, options })
    connectionEditing.value = false; catalog.value = null; connectionPreview.value = null; selectedTable.value = null
    previewQuery.value = connection.value.sourceType === 'HTTP_API' ? '' : defaultPreviewQuery(connection.value)
    if (connection.value.sourceType !== 'HTTP_API') await loadCatalog()
    connectionMessage.value = '连接信息已保存，建议再检查一次连接'
  } catch (reason) { connectionMessage.value = reason instanceof Error ? reason.message : '连接信息没有保存' }
  finally { connectionSaving.value = false }
}
watch(() => props.resource.id, () => {
  editing.value = false
  officeFallback.value = false
  webOriginalMode.value = true
  webPreview.value = null
  citationsOpen.value = false
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
  testLoading.value = true; connectionMessage.value = ''
  try {
    const result = await api.testConnection(connection.value.id)
    connectionMessage.value = `${result.message}${result.latencyMs ? ` · ${result.latencyMs} ms` : ''}`
    connection.value.status = result.success ? 'READY' : 'FAILED'
  } catch (reason) { connectionMessage.value = reason instanceof Error ? reason.message : '连接检查没有完成' }
  finally { testLoading.value = false }
}
async function previewData() {
  if (!connection.value) return
  if (connection.value.sourceType !== 'HTTP_API' && !previewQuery.value.trim()) {
    connectionMessage.value = '请先输入一条 SELECT 或 WITH 查询'; return
  }
  previewLoading.value = true; connectionMessage.value = ''; connectionPreview.value = null
  try { connectionPreview.value = await api.previewConnection(connection.value.id, previewQuery.value.trim(), 100) }
  catch (reason) { connectionMessage.value = reason instanceof Error ? reason.message : '数据预览没有完成' }
  finally { previewLoading.value = false }
}
</script>

<template>
  <section class="resource-workbench">
    <header class="resource-toolbar">
      <div><span>{{ resource.group === 'DATA' ? '数据' : resource.group === 'OUTPUT' ? '输出件' : '资料' }}</span><strong>{{ resource.name }}</strong></div>
      <nav>
        <div v-if="isEditable" class="view-mode-switch"><button type="button" :class="{ active: !editing }" title="查看" @click="editing = false"><Eye :size="15"/></button><button type="button" :class="{ active: editing }" title="在线编辑" @click="editing = true"><FilePenLine :size="15"/></button></div>
        <button class="secondary-button" type="button" @click="$emit('addToWorkflow', resource)"><Plus :size="15"/>{{ resource.inProjectWorkflow ? '已在工作流' : '加入工作流' }}</button>
        <a v-if="isWebUrl" class="secondary-button" :href="resource.url" target="_blank" rel="noopener noreferrer"><ExternalLink :size="15"/>新窗口打开</a>
        <button v-else-if="!isConnection" class="secondary-button" type="button" :disabled="downloadLoading" @click="downloadCurrent"><LoaderCircle v-if="downloadLoading" class="spin" :size="15"/><Download v-else :size="15"/>{{ downloadLoading ? '正在下载' : '下载' }}</button>
        <button class="icon-button danger" type="button" title="删除" @click="$emit('deleteResource', resource)"><Trash2 :size="16"/></button>
        <button class="icon-button" type="button" title="刷新内容" @click="load"><RefreshCw :size="17"/></button>
      </nav>
    </header>
    <aside v-if="downloadMessage" class="resource-download-message"><span>{{ downloadMessage }}</span><button type="button" title="关闭" @click="downloadMessage = ''"><X :size="14"/></button></aside>
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
          <label><span>名称</span><input v-model="connectionDraft.name"></label>
          <label><span>类型</span><select v-model="connectionDraft.sourceType"><option value="POSTGRESQL">PostgreSQL</option><option value="MYSQL">MySQL</option><option value="OPENGAUSS">openGauss</option><option value="GAUSS_DWS">GaussDB(DWS)</option><option value="DUCKDB">DuckDB</option><option value="HTTP_API">数据服务 API</option></select></label>
          <label class="wide"><span>{{ connectionDraft.sourceType === 'HTTP_API' ? '接口地址' : '连接地址' }}</span><input v-model="connectionDraft.jdbcUrl"></label>
          <label v-if="connectionDraft.sourceType !== 'HTTP_API'"><span>账号</span><input v-model="connectionDraft.username"></label>
          <label><span>访问凭据</span><input v-model="connectionDraft.secretRef" placeholder="留空保留原配置，或填 env:变量名"></label>
          <template v-if="connectionDraft.sourceType === 'HTTP_API'"><label><span>请求方式</span><select v-model="connectionDraft.method"><option>GET</option><option>POST</option></select></label><label><span>返回格式</span><select v-model="connectionDraft.format"><option value="json">JSON</option><option value="jsonl">JSON Lines</option><option value="csv">CSV</option></select></label><label v-if="connectionDraft.method === 'POST'" class="wide"><span>请求内容</span><textarea v-model="connectionDraft.body" rows="4" placeholder="输入 JSON 请求内容"></textarea></label></template>
        </div>
        <footer><button class="primary-button" type="button" :disabled="connectionSaving" @click="saveConnection"><LoaderCircle v-if="connectionSaving" class="spin" :size="15"/><Save v-else :size="15"/>{{ connectionSaving ? '正在保存' : '保存修改' }}</button></footer>
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
        <p v-if="connectionMessage" class="connection-message" :class="{ error: connection.status === 'FAILED' || !connectionPreview }">{{ connectionMessage }}</p>
        <div v-if="connectionPreview" class="connection-preview-table">
          <table><thead><tr><th class="row-number">#</th><th v-for="(column, index) in connectionPreview.columns" :key="`${column}-${index}`">{{ column || `第 ${index + 1} 列` }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in connectionPreview.rows" :key="rowIndex"><td class="row-number">{{ rowIndex + 1 }}</td><td v-for="(_, columnIndex) in connectionPreview.columns" :key="columnIndex" :title="row[columnIndex]">{{ row[columnIndex] }}</td></tr></tbody></table>
          <footer>已显示 {{ connectionPreview.rowCount }} 行<span v-if="connectionPreview.truncated">，还有更多数据</span></footer>
        </div>
        <div v-else-if="!previewLoading" class="connection-preview-empty"><Eye :size="23"/><span>点击“预览数据”查看实际取数结果</span></div>
      </section>
    </section>
    <section v-else-if="isWebUrl" class="web-url-work-area">
      <header><Globe2 :size="18"/><span>{{ resource.url }}</span><div class="web-preview-modes"><button type="button" :class="{ active: webOriginalMode }" @click="webOriginalMode = true">原网页</button><button type="button" :class="{ active: !webOriginalMode }" @click="openWebReader">快速阅读</button><a :href="resource.url" target="_blank" rel="noopener noreferrer"><ExternalLink :size="14"/>新窗口</a></div></header>
      <div v-if="webOriginalMode && webEmbedStatus.status === 'BLOCKED'" class="web-embed-blocked"><ShieldAlert :size="28"/><strong>该网页无法在 Studio 内打开</strong><p>{{ webEmbedStatus.reason }}。你仍可以快速阅读已提取的正文，或在浏览器中访问完整原文。</p><div><button class="secondary-button" type="button" @click="openWebReader">快速阅读</button><a class="primary-button" :href="resource.url" target="_blank" rel="noopener noreferrer"><ExternalLink :size="15"/>在浏览器中打开</a></div></div>
      <div v-else-if="webOriginalMode" class="web-url-frame"><iframe :src="resource.url" :title="resource.name" referrerpolicy="strict-origin-when-cross-origin"></iframe></div>
      <div v-else-if="webLoading" class="web-url-loading"><LoaderCircle class="spin" :size="21"/><strong>正在整理网页内容</strong><p>首次读取后会缓存，之后打开会更快。</p></div>
      <div v-else-if="error" class="resource-state error"><Globe2 :size="24"/><strong>网站内容暂时无法读取</strong><p>{{ error }}</p><a class="secondary-button" :href="resource.url" target="_blank" rel="noopener noreferrer"><ExternalLink :size="14"/>新窗口打开</a></div>
      <article v-else-if="webPreview" class="web-reader">
        <header><span>{{ webPreview.siteName }}</span><em>{{ webPreview.previewMode === 'CURATED' ? `已核验${webPreview.verifiedAt ? ` · ${webPreview.verifiedAt}` : ''}` : '实时正文' }}</em></header>
        <h1>{{ webPreview.title }}</h1>
        <p v-for="paragraph in webPreview.summary.split('\n').filter(Boolean)" :key="paragraph" class="web-reader-summary">{{ paragraph }}</p>
        <section v-if="webPreview.highlights.length" class="web-reader-highlights"><h2>关键内容</h2><ul><li v-for="item in webPreview.highlights" :key="item">{{ item }}</li></ul></section>
        <section v-for="section in webPreview.sections" :key="section.heading" class="web-reader-section"><h2>{{ section.heading }}</h2><p v-for="paragraph in section.paragraphs" :key="paragraph">{{ paragraph }}</p></section>
        <footer><button class="secondary-button" type="button" @click="loadWebPreview(true)"><RefreshCw :size="14"/>读取最新正文</button><a :href="resource.url" target="_blank" rel="noopener noreferrer">查看官方原文<ExternalLink :size="13"/></a></footer>
      </article>
    </section>
    <iframe v-else-if="isOfficeDocument && !editing && officeFallback" class="inline-pdf" :src="renderedOfficePreviewUrl(officeKind === 'deliverables' ? 'deliverables' : 'files', resource.id, resource.currentVersion)" :title="resource.name"></iframe>
    <OfficeEditor v-else-if="isOfficeDocument" :key="`${resource.id}-${resource.currentVersion}-${editing ? 'edit' : 'view'}`" :resource-id="resource.id" :kind="officeKind" :mode="editing ? 'edit' : 'view'" @unavailable="officeUnavailable" />
    <OfficeEditor v-else-if="editing && isEditable" :key="`${resource.id}-${resource.currentVersion}-edit`" :resource-id="resource.id" :kind="officeKind" mode="edit" />
    <iframe v-else-if="isPdf" class="inline-pdf" :src="resource.resourceType === 'DELIVERABLE' ? deliverableContentUrl(resource.id) : inlineContentUrl(resource.id)" :title="resource.name"></iframe>
    <InteractiveFinancialReport v-else-if="isFinancialReport" :project-id="resource.projectId" :deliverable-id="resource.id" :report-name="resource.name" @open-source="$emit('openSource', $event)"/>
    <section v-else-if="isHtmlSlides" class="html-slides-workbench"><div class="html-slides-badge">网页演示 · HTML + JS · 非 PowerPoint 文件</div><iframe :src="deliverableContentUrl(resource.id)" :title="resource.name" sandbox="allow-scripts" allow="fullscreen"></iframe></section>
    <div v-else-if="csv" class="inline-csv"><table><thead><tr><th class="row-number">#</th><th v-for="(column, index) in csv.columns" :key="`${column}-${index}`">{{ column || `第 ${index + 1} 列` }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in csv.rows" :key="rowIndex"><td class="row-number">{{ csv.rowOffset + rowIndex + 1 }}</td><td v-for="(_, columnIndex) in csv.columns" :key="columnIndex" :title="row[columnIndex]">{{ row[columnIndex] }}</td></tr></tbody></table><footer>在线显示前 {{ csv.rows.length }} 行<span v-if="csv.hasMore">，文件还有更多数据</span></footer></div>
    <DiagramPreview v-else-if="document && diagramType" :kind="diagramType" :source="diagramSource" />
    <div v-else-if="document" class="inline-document" :data-kind="document.kind"><p v-for="warning in document.warnings" :key="warning" class="preview-warning">{{ warning }}</p><article v-for="page in document.pages" :key="page.number" class="document-page"><span v-if="document.kind === 'presentation'" class="page-number">{{ page.number }}</span><h2 v-if="page.title">{{ page.title }}</h2><template v-for="(block, index) in page.blocks" :key="index"><h3 v-if="block.type === 'heading'">{{ block.text }}</h3><p v-else-if="block.type === 'text'">{{ block.text }}</p><div v-else class="table-wrap"><table><tbody><tr v-for="(row, rowIndex) in block.rows" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td></tr></tbody></table></div></template></article></div>
    <div v-else class="resource-state"><FileText :size="24"/><strong>文件已经就绪</strong><p>可下载原文件，或把它加入主工作流继续处理。</p></div>
  </section>
</template>
