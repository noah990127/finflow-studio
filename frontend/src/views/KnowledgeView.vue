<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { BookOpen, Download, Eye, FileUp, Search } from 'lucide-vue-next'
import { api, downloadUrl, type FileResource, type KnowledgeRef, type Project } from '../api/client'
import FilePreview from '../components/FilePreview.vue'
const props = defineProps<{ project: Project | null }>(); const files = ref<FileResource[]>([]); const refs = ref<KnowledgeRef[]>([]); const query = ref(''); const busy = ref(false); const message = ref('')
const previewing = ref<FileResource | null>(null)
function previewFormat(file: FileResource) { return file.name.split('.').pop()?.toLowerCase() || (file.mediaType === 'application/pdf' ? 'pdf' : '') }
function canPreview(file: FileResource) { return ['pdf', 'pptx', 'docx', 'csv', 'tsv', 'txt', 'md', 'mmd', 'mermaid'].includes(previewFormat(file)) }
async function load() { if (props.project) files.value = await api.listFiles(props.project.id) }
async function upload(event: Event) { const input = event.target as HTMLInputElement; const file = input.files?.[0]; if (!file || !props.project) return; busy.value = true; try { await api.uploadFile(props.project.id, file); message.value = '文件已上传，正在生成可引用的 Ref'; await load() } catch (e) { message.value = e instanceof Error ? e.message : '上传失败' } finally { busy.value = false; input.value = '' } }
async function search() { if (!props.project || !query.value.trim()) return; busy.value = true; try { refs.value = await api.searchRefs(props.project.id, query.value) } catch (e) { message.value = e instanceof Error ? e.message : '搜索失败' } finally { busy.value = false } }
function location(item: KnowledgeRef) { return Object.entries(item.location).map(([key, value]) => `${key} ${value}`).join(' · ') || '原文位置' }
watch(() => props.project?.id, load); onMounted(load)
</script>
<template><main class="workspace"><header class="page-header"><div><p class="eyebrow">资料</p><h1>资料与 Ref</h1><p>上传 PDF、Word、PPT、表格或文本，从分析结论一键回到原始位置。</p></div><label class="primary-button upload-button"><FileUp :size="17"/>{{ busy ? '处理中…' : '上传资料' }}<input type="file" :disabled="busy" @change="upload"></label></header><p v-if="message" class="notice">{{ message }}</p>
  <section class="search-band"><Search :size="18"/><input v-model="query" placeholder="搜索资料中的内容" @keydown.enter="search"><button type="button" @click="search">搜索 Ref</button></section>
  <section v-if="refs.length" class="content-band"><div class="section-title-row"><div><span>搜索结果</span><h2>可引用片段</h2></div></div><div class="ref-list"><article v-for="item in refs" :key="item.id"><div><strong>{{ item.sourceName }}</strong><span>{{ location(item) }}</span></div><p>{{ item.text }}</p><code>{{ item.id }}</code></article></div></section>
  <section class="content-band"><div class="section-title-row"><div><span>当前空间</span><h2>全部资料</h2></div><span>{{ files.length }} 份</span></div><div v-if="!files.length" class="empty-state"><BookOpen :size="28"/><strong>还没有资料</strong><p>上传文件后，系统会保留原文件和每个版本。</p></div><div class="file-grid"><article v-for="file in files" :key="file.id"><span class="file-icon"><BookOpen :size="20"/></span><div><strong>{{ file.name }}</strong><p>第 {{ file.currentVersion }} 版 · {{ (file.sizeBytes / 1048576).toFixed(1) }} MB</p><span class="status" :data-status="file.parseStatus">{{ file.parseStatus }}</span></div><div class="row-actions"><button v-if="canPreview(file)" class="icon-button" type="button" title="在线查看" @click="previewing = file"><Eye :size="17"/></button><a class="icon-button" :href="downloadUrl('files', file.id)" title="下载"><Download :size="17"/></a></div></article></div></section>
  <FilePreview v-if="previewing" :source="{ kind: 'files', id: previewing.id, name: previewing.name, format: previewFormat(previewing) }" @close="previewing = null"/>
</main></template>
