<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ChevronLeft, ChevronRight, Download, LoaderCircle, X } from 'lucide-vue-next'
import { api, deliverableContentUrl, downloadFile, inlineContentUrl, type CsvPreview, type DocumentPreview } from '../api/client'

type PreviewSource = { kind: 'extract-jobs' | 'files' | 'deliverables'; id: string; name: string; format: string }
const props = defineProps<{ source: PreviewSource }>()
defineEmits<{ close: [] }>()
const csv = ref<CsvPreview | null>(null)
const document = ref<DocumentPreview | null>(null)
const loading = ref(true)
const downloading = ref(false)
const error = ref('')
const cursors = ref<Array<string | undefined>>([undefined])
const pageIndex = ref(0)
const extension = computed(() => props.source.format.toLowerCase().replace('.', ''))
const mode = computed(() => props.source.kind === 'extract-jobs' || ['csv', 'tsv'].includes(extension.value) ? 'csv' : extension.value === 'pdf' ? 'pdf' : extension.value === 'html_slides' ? 'html-slides' : 'document')

async function loadCsv(cursor?: string) {
  loading.value = true; error.value = ''
  try { csv.value = props.source.kind === 'extract-jobs' ? await api.previewExtract(props.source.id, cursor) : await api.previewFileCsv(props.source.id, cursor) }
  catch (e) { error.value = e instanceof Error ? e.message : '表格预览加载失败' }
  finally { loading.value = false }
}
async function nextPage() {
  if (!csv.value?.nextCursor) return
  const next = pageIndex.value + 1
  cursors.value[next] = csv.value.nextCursor
  pageIndex.value = next
  await loadCsv(cursors.value[next])
}
async function previousPage() {
  if (pageIndex.value === 0) return
  pageIndex.value -= 1
  await loadCsv(cursors.value[pageIndex.value])
}
async function load() {
  if (mode.value === 'csv') return loadCsv()
  if (['pdf', 'html-slides'].includes(mode.value)) { loading.value = false; return }
  try { document.value = props.source.kind === 'deliverables' ? await api.previewDeliverable(props.source.id) : await api.previewFile(props.source.id) }
  catch (e) { error.value = e instanceof Error ? e.message : '文件预览加载失败' }
  finally { loading.value = false }
}
onMounted(load)
async function download() {
  downloading.value = true
  try { await downloadFile(props.source.kind, props.source.id, props.source.name) }
  finally { downloading.value = false }
}
</script>

<template>
  <Teleport to="body"><div class="preview-backdrop" role="presentation" @click.self="$emit('close')">
    <section class="preview-dialog" role="dialog" aria-modal="true" :aria-label="`预览 ${source.name}`">
      <header class="preview-toolbar"><div><strong>{{ source.name }}</strong><span v-if="csv">第 {{ csv.rowOffset + 1 }} - {{ csv.rowOffset + csv.rows.length }} 行</span><span v-else-if="document">{{ document.pages.length }} {{ document.kind === 'presentation' ? '页幻灯片' : '页' }}</span></div><nav><button class="icon-button" type="button" :disabled="downloading" title="下载原文件" @click="download"><LoaderCircle v-if="downloading" class="spin" :size="18"/><Download v-else :size="18"/></button><button class="icon-button" type="button" title="关闭" @click="$emit('close')"><X :size="19"/></button></nav></header>
      <div v-if="loading" class="preview-state">正在打开文件…</div>
      <div v-else-if="error" class="preview-state error">{{ error }}</div>
      <iframe v-else-if="mode === 'pdf'" class="pdf-preview" :src="inlineContentUrl(source.id)" :title="source.name"></iframe>
      <iframe v-else-if="mode === 'html-slides'" class="pdf-preview" :src="deliverableContentUrl(source.id)" :title="source.name" sandbox="allow-scripts" allow="fullscreen"></iframe>
      <template v-else-if="mode === 'csv' && csv">
        <div class="csv-preview"><table><thead><tr><th class="row-number">#</th><th v-for="(column, index) in csv.columns" :key="`${column}-${index}`">{{ column || `第 ${index + 1} 列` }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in csv.rows" :key="rowIndex"><td class="row-number">{{ csv.rowOffset + rowIndex + 1 }}</td><td v-for="(_, columnIndex) in csv.columns" :key="columnIndex" :title="row[columnIndex]">{{ row[columnIndex] }}</td></tr></tbody></table><div v-if="!csv.rows.length" class="preview-state">文件中没有数据行</div></div>
        <footer class="preview-pagination"><span>每页最多 100 行</span><div><button class="icon-button" type="button" title="上一页" :disabled="pageIndex === 0 || loading" @click="previousPage"><ChevronLeft :size="18"/></button><span>第 {{ pageIndex + 1 }} 页</span><button class="icon-button" type="button" title="下一页" :disabled="!csv.hasMore || loading" @click="nextPage"><ChevronRight :size="18"/></button></div></footer>
      </template>
      <div v-else-if="document" class="document-preview" :data-kind="document.kind">
        <p v-for="warning in document.warnings" :key="warning" class="preview-warning">{{ warning }}</p>
        <article v-for="page in document.pages" :key="page.number" class="document-page"><span v-if="document.kind === 'presentation'" class="page-number">{{ page.number }}</span><h2 v-if="page.title">{{ page.title }}</h2><template v-for="(block, index) in page.blocks" :key="index"><h3 v-if="block.type === 'heading'">{{ block.text }}</h3><p v-else-if="block.type === 'text'">{{ block.text }}</p><div v-else class="table-wrap"><table><tbody><tr v-for="(row, rowIndex) in block.rows" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td></tr></tbody></table></div></template></article>
      </div>
    </section>
  </div></Teleport>
</template>
