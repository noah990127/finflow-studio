<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BarChart3, ChevronLeft, ChevronRight, Grid3X3, LineChart, RefreshCw, RotateCcw, Save, Sparkles, Table2 } from 'lucide-vue-next'
import * as echarts from 'echarts'
import viewerWasmUrl from '@perspective-dev/viewer/dist/wasm/perspective-viewer.wasm?url'
import { api, type CsvPreview, type FinancialReportSpec, type WorkspaceResource } from '../api/client'
import FinancialReportChart from './FinancialReportChart.vue'

const props = defineProps<{ projectId: string; deliverableId: string; reportName: string }>()
type ReportRow = Record<string, string | number | null>
type ReportDataset = { id: string; name: string; rows: ReportRow[]; columns: string[]; truncated: boolean }
type PerspectiveViewer = HTMLElement & { load: (table: unknown) => Promise<void>; restore: (config: Record<string, unknown>) => Promise<void>; save: () => Promise<Record<string, unknown>> }
type PerspectiveTable = { delete: () => Promise<void> }
type PerspectiveClient = { table: (rows: ReportRow[]) => Promise<PerspectiveTable> }

const loading = ref(true), error = ref(''), datasets = ref<ReportDataset[]>([]), selectedId = ref('')
const report = ref<FinancialReportSpec | null>(null), reportNotice = ref('')
const tab = ref<'overview' | 'explore' | 'data'>('overview'), metric = ref(''), chartMode = ref<'line' | 'bar'>('line')
const chartEl = ref<HTMLElement | null>(null), viewerEl = ref<PerspectiveViewer | null>(null)
const perspectiveLoading = ref(false), perspectiveError = ref('')
const perspectiveMessage = ref('')
const dataPage = ref(0), dataPageSize = 100
let chart: echarts.ECharts | null = null
let perspectiveClient: PerspectiveClient | null = null
let perspectiveTable: PerspectiveTable | null = null
let perspectiveLoadToken = 0
let perspectiveDatasetId = ''
let viewerReady: Promise<void> | null = null

function ensureViewer() {
  viewerReady ??= (async () => {
    const [perspective, perspectiveViewer, response] = await Promise.all([
      import('@perspective-dev/client/inline'),
      import('@perspective-dev/viewer'),
      fetch(viewerWasmUrl),
      import('@perspective-dev/viewer-datagrid'),
      import('@perspective-dev/viewer-d3fc'),
      import('@perspective-dev/viewer/dist/css/pro.css'),
      import('@perspective-dev/viewer/dist/css/intl/zh.css'),
    ])
    if (!response.ok) throw new Error('自助分析界面下载失败')
    await perspectiveViewer.default.init_client(await response.arrayBuffer())
    perspectiveClient = await perspective.worker() as PerspectiveClient
  })()
  return viewerReady
}

const active = computed(() => datasets.value.find(item => item.id === selectedId.value) ?? null)
const numericColumns = computed(() => active.value?.columns.filter(column => active.value!.rows.some(row => typeof row[column] === 'number')) ?? [])
const dimension = computed(() => active.value?.columns.find(column => !numericColumns.value.includes(column)) ?? active.value?.columns[0] ?? '')
const metricValues = computed(() => active.value?.rows.map(row => row[metric.value]).filter((value): value is number => typeof value === 'number') ?? [])
const latestValue = computed(() => metricValues.value.at(-1) ?? null)
const previousValue = computed(() => metricValues.value.at(-2) ?? null)
const changeRate = computed(() => latestValue.value !== null && previousValue.value ? (latestValue.value - previousValue.value) / Math.abs(previousValue.value) : null)
const averageValue = computed(() => metricValues.value.length ? metricValues.value.reduce((sum, value) => sum + value, 0) / metricValues.value.length : null)
const maxValue = computed(() => metricValues.value.length ? Math.max(...metricValues.value) : null)
const dataPageCount = computed(() => Math.max(1, Math.ceil((active.value?.rows.length ?? 0) / dataPageSize)))
const visibleDataRows = computed(() => active.value?.rows.slice(dataPage.value * dataPageSize, (dataPage.value + 1) * dataPageSize) ?? [])

function parseValue(value: string): string | number | null {
  const clean = value.trim()
  if (!clean) return null
  const normalized = clean.replace(/,/g, '').replace(/%$/, '')
  if (/^-?\d+(\.\d+)?$/.test(normalized)) return Number(normalized)
  return clean
}

async function readPages(resource: WorkspaceResource): Promise<ReportDataset> {
  const rows: ReportRow[] = []
  let cursor: string | undefined
  let page: CsvPreview | undefined
  do {
    page = resource.resourceType === 'DATASET' ? await api.previewExtract(resource.id, cursor) : await api.previewFileCsv(resource.id, cursor)
    for (const cells of page.rows) {
      if (!cells.some(cell => cell.trim())) continue
      const row: ReportRow = {}
      page.columns.forEach((column, index) => { row[column || `字段${index + 1}`] = parseValue(cells[index] ?? '') })
      rows.push(row)
    }
    cursor = page.nextCursor
  } while (page.hasMore && cursor && rows.length < 5000)
  return { id: resource.id, name: resource.name, rows, columns: page?.columns ?? [], truncated: Boolean(page?.hasMore) }
}

async function load() {
  loading.value = true; error.value = ''; reportNotice.value = ''; report.value = null
  try {
    const [workspace, reportResult] = await Promise.all([
      api.getProjectWorkspace(props.projectId),
      api.getFinancialReport(props.deliverableId).catch(reason => {
        reportNotice.value = reason instanceof Error ? '这份历史报告使用旧格式，请重新运行工作流以生成可编辑图表报告。' : '报告内容暂时无法读取。'
        return null
      })
    ])
    report.value = reportResult
    const candidates = workspace.resources.filter(resource => resource.resourceType === 'DATASET' ||
      (['DATA_FILE', 'KNOWLEDGE_FILE', 'OFFICE_FILE'].includes(resource.resourceType) && /\.(csv|tsv)$/i.test(resource.name)))
    const sources = Array.from(candidates
      .sort((left, right) => Number(right.resourceType === 'DATASET') - Number(left.resourceType === 'DATASET'))
      .reduce((items, resource) => {
        const key = resource.name.trim().toLocaleLowerCase()
        if (!items.has(key)) items.set(key, resource)
        return items
      }, new Map<string, WorkspaceResource>()).values())
    if (!sources.length) throw new Error('项目中还没有可用于报告的 CSV 或数据采集结果')
    const loaded = await Promise.all(sources.slice(0, 8).map(readPages))
    datasets.value = loaded.filter(item => item.rows.length > 0)
    if (!datasets.value.length) throw new Error('结构化数据中没有可显示的记录')
    selectedId.value = datasets.value[0].id
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '交互报告没有加载成功' }
  finally { loading.value = false }
}

function perspectiveStorageKey() { return `finflow-report-view:${props.deliverableId}:${selectedId.value}` }

async function savePerspectiveView() {
  if (!viewerEl.value) return
  try {
    localStorage.setItem(perspectiveStorageKey(), JSON.stringify(await viewerEl.value.save()))
    perspectiveMessage.value = '当前分析视图已保存'
  } catch { perspectiveMessage.value = '当前分析视图没有保存成功' }
}

async function resetPerspectiveView() {
  if (!viewerEl.value || !active.value) return
  localStorage.removeItem(perspectiveStorageKey())
  await viewerEl.value.restore({ plugin: 'Datagrid', settings: true, columns: active.value.columns })
  perspectiveMessage.value = '已恢复默认分析视图'
}

function label(column: string) {
  const labels: Record<string, string> = {
    fiscal_year: '财年', period_end: '期末日期', revenue_usd_m: '营业收入', gross_profit_usd_m: '毛利润',
    operating_income_usd_m: '营业利润', net_income_usd_m: '净利润', operating_cash_flow_usd_m: '经营现金流',
    rd_expense_usd_m: '研发投入', inventory_usd_m: '存货', data_center_revenue_usd_m: '数据中心收入', gross_margin_pct: '毛利率'
  }
  return labels[column] ?? column.replace(/_usd_m$/i, '').replace(/_/g, ' ')
}

function formatValue(value: number | null, column = metric.value) {
  if (value === null) return '—'
  if (/pct|rate|margin/i.test(column)) return `${value.toLocaleString('zh-CN', { maximumFractionDigits: 1 })}%`
  if (Math.abs(value) >= 1000) return `${(value / 1000).toLocaleString('zh-CN', { maximumFractionDigits: 1 })} 十亿`
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 1 })
}

function drawChart() {
  if (!chartEl.value || !active.value || !metric.value) return
  chart ??= echarts.init(chartEl.value)
  const categories = active.value.rows.map((row, index) => String(row[dimension.value] ?? index + 1))
  const values = active.value.rows.map(row => typeof row[metric.value] === 'number' ? row[metric.value] : null)
  chart.setOption({
    animationDuration: 450,
    color: ['#1677ff'],
    grid: { top: 28, right: 24, bottom: 44, left: 68 },
    tooltip: { trigger: 'axis', valueFormatter: (value: unknown) => typeof value === 'number' ? formatValue(value) : String(value ?? '') },
    xAxis: { type: 'category', data: categories, axisLine: { lineStyle: { color: '#cad5e5' } }, axisLabel: { color: '#64748b' } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#e8eef6' } }, axisLabel: { color: '#64748b' } },
    series: [{ name: label(metric.value), type: chartMode.value, data: values, smooth: chartMode.value === 'line', symbolSize: 8,
      lineStyle: { width: 3 }, areaStyle: chartMode.value === 'line' ? { color: 'rgba(22,119,255,.08)' } : undefined,
      itemStyle: { borderRadius: chartMode.value === 'bar' ? [4, 4, 0, 0] : 0 } }]
  }, true)
}

async function loadPerspective(force = false) {
  if (!viewerEl.value || !active.value) return
  if (!force && perspectiveTable && perspectiveDatasetId === active.value.id) return
  const loadToken = ++perspectiveLoadToken
  perspectiveLoading.value = true
  perspectiveError.value = ''
  try {
    await ensureViewer()
    await customElements.whenDefined('perspective-viewer')
    if (!perspectiveClient) throw new Error('自助分析引擎没有准备完成')
    const nextTable = await perspectiveClient.table(active.value.rows)
    if (loadToken !== perspectiveLoadToken) { await nextTable.delete(); return }
    const previousTable = perspectiveTable
    await viewerEl.value.load(nextTable)
    if (loadToken !== perspectiveLoadToken) { await nextTable.delete(); return }
    perspectiveTable = nextTable
    perspectiveDatasetId = active.value.id
    const saved = localStorage.getItem(perspectiveStorageKey())
    if (saved) {
      try { await viewerEl.value.restore(JSON.parse(saved)) }
      catch { localStorage.removeItem(perspectiveStorageKey()); await viewerEl.value.restore({ plugin: 'Datagrid', settings: true, columns: active.value.columns }) }
    } else await viewerEl.value.restore({ plugin: 'Datagrid', settings: true, columns: active.value.columns })
    if (previousTable) await previousTable.delete()
  } catch (reason) {
    perspectiveError.value = reason instanceof Error ? reason.message : '自助分析组件没有加载成功'
  } finally {
    if (loadToken === perspectiveLoadToken) perspectiveLoading.value = false
  }
}

watch(active, async value => {
  if (!value) return
  metric.value = numericColumns.value[0] ?? ''
  dataPage.value = 0
  await nextTick()
  if (tab.value === 'overview') drawChart()
  if (tab.value === 'explore' || perspectiveTable) await loadPerspective(true)
})
watch([metric, chartMode], async () => { await nextTick(); if (tab.value === 'overview') drawChart() })
watch(tab, async value => {
  await nextTick()
  if (value === 'overview') { chart?.resize(); drawChart() }
  if (value === 'explore') await loadPerspective()
})
watch(() => props.deliverableId, load)
onMounted(load)
onBeforeUnmount(async () => { chart?.dispose(); perspectiveLoadToken++; if (perspectiveTable) await perspectiveTable.delete() })
</script>

<template>
  <section class="financial-report-shell">
    <div v-if="loading" class="resource-state"><RefreshCw class="spin" :size="22"/>正在准备交互报告</div>
    <div v-else-if="error" class="resource-state error"><strong>报告暂时无法显示</strong><p>{{ error }}</p><button class="secondary-button" @click="load"><RefreshCw :size="15"/>重试</button></div>
    <template v-else-if="active">
      <header class="financial-report-header">
        <div><span>AI 图表报告</span><h1>{{ report?.title || reportName }}</h1><p>{{ report?.subtitle || active.name }} · {{ active.rows.length.toLocaleString() }} 条记录<span v-if="active.truncated"> · 当前分析前 5,000 条</span></p></div>
        <label>数据集<select v-model="selectedId"><option v-for="item in datasets" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      </header>
      <nav class="financial-report-tabs">
        <button :class="{ active: tab === 'overview' }" @click="tab = 'overview'"><Sparkles :size="16"/>AI 报告</button>
        <button :class="{ active: tab === 'explore' }" @click="tab = 'explore'"><Grid3X3 :size="16"/>自助分析</button>
        <button :class="{ active: tab === 'data' }" @click="tab = 'data'"><Table2 :size="16"/>数据明细</button>
      </nav>

      <section v-show="tab === 'overview'" class="financial-overview">
        <p v-if="reportNotice" class="report-format-notice">{{ reportNotice }}</p>
        <section v-if="report?.sections.length" class="ai-report-sections">
          <article v-for="(section, sectionIndex) in report.sections" :key="`${section.heading}-${sectionIndex}`" class="ai-report-section">
            <header><span>{{ String(sectionIndex + 1).padStart(2, '0') }}</span><h2>{{ section.heading }}</h2><button class="secondary-button" @click="tab = 'explore'"><Grid3X3 :size="14"/>基于数据继续分析</button></header>
            <div class="ai-report-section-body" :class="{ 'without-chart': !section.chart }">
              <div class="ai-report-copy"><p v-for="paragraph in section.paragraphs" :key="paragraph">{{ paragraph }}</p><ul v-if="section.bullets.length"><li v-for="bullet in section.bullets" :key="bullet">{{ bullet }}</li></ul></div>
              <FinancialReportChart v-if="section.chart" :chart="section.chart" :storage-key="`finflow-report-chart:${deliverableId}:${sectionIndex}`"/>
            </div>
          </article>
          <section v-if="report.references.length" class="ai-report-references"><h2>参考来源</h2><ol><li v-for="entry in report.references" :key="entry">{{ entry }}</li></ol></section>
        </section>
        <div class="report-control-row">
          <label>分析指标<select v-model="metric"><option v-for="column in numericColumns" :key="column" :value="column">{{ label(column) }}</option></select></label>
          <div class="report-chart-switch"><button :class="{ active: chartMode === 'line' }" title="折线图" @click="chartMode = 'line'"><LineChart :size="16"/></button><button :class="{ active: chartMode === 'bar' }" title="柱状图" @click="chartMode = 'bar'"><BarChart3 :size="16"/></button></div>
        </div>
        <div class="financial-kpis">
          <article><span>最新值</span><strong>{{ formatValue(latestValue) }}</strong><small>{{ label(metric) }}</small></article>
          <article><span>较上期</span><strong :class="{ positive: changeRate !== null && changeRate >= 0, negative: changeRate !== null && changeRate < 0 }">{{ changeRate === null ? '—' : `${changeRate >= 0 ? '+' : ''}${(changeRate * 100).toFixed(1)}%` }}</strong><small>环比变化</small></article>
          <article><span>期间均值</span><strong>{{ formatValue(averageValue) }}</strong><small>{{ metricValues.length }} 个期间</small></article>
          <article><span>期间峰值</span><strong>{{ formatValue(maxValue) }}</strong><small>样本内最高</small></article>
        </div>
        <article class="financial-trend-panel"><header><div><strong>{{ label(metric) }}趋势</strong><span>可悬停查看每期数值</span></div></header><div ref="chartEl" class="financial-trend-chart"></div></article>
      </section>

      <section v-show="tab === 'explore'" class="perspective-report-panel"><header><div><strong>自助分析</strong><span>拖动字段即可筛选、分组、聚合和更换图表</span></div><nav><span v-if="perspectiveMessage">{{ perspectiveMessage }}</span><button class="secondary-button" @click="resetPerspectiveView"><RotateCcw :size="14"/>恢复默认</button><button class="primary-button" @click="savePerspectiveView"><Save :size="14"/>保存当前分析</button></nav></header><div v-if="perspectiveLoading" class="perspective-report-state"><RefreshCw class="spin" :size="18"/>正在加载分析工具</div><div v-else-if="perspectiveError" class="perspective-report-state error"><strong>分析工具暂时无法显示</strong><span>{{ perspectiveError }}</span><button class="secondary-button" @click="loadPerspective(true)"><RefreshCw :size="14"/>重试</button></div><perspective-viewer ref="viewerEl" theme="Pro Light"></perspective-viewer></section>

      <section v-show="tab === 'data'" class="financial-data-panel">
        <header><div><strong>数据明细</strong><span>查看报告所使用的原始记录</span></div><small>共 {{ active.rows.length.toLocaleString() }} 条</small></header>
        <div class="financial-data-table"><table><thead><tr><th class="row-number">#</th><th v-for="column in active.columns" :key="column">{{ label(column) }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in visibleDataRows" :key="dataPage * dataPageSize + rowIndex"><td class="row-number">{{ dataPage * dataPageSize + rowIndex + 1 }}</td><td v-for="column in active.columns" :key="column" :title="String(row[column] ?? '')">{{ row[column] ?? '' }}</td></tr></tbody></table></div>
        <footer><span>每页 {{ dataPageSize }} 条</span><nav><button class="icon-button" title="上一页" :disabled="dataPage === 0" @click="dataPage--"><ChevronLeft :size="16"/></button><span>第 {{ dataPage + 1 }} / {{ dataPageCount }} 页</span><button class="icon-button" title="下一页" :disabled="dataPage + 1 >= dataPageCount" @click="dataPage++"><ChevronRight :size="16"/></button></nav></footer>
      </section>
    </template>
  </section>
</template>
