<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Eye, Pencil, Columns2, Save, Bold, Italic, Heading2, List, Link, RotateCcw, Download } from 'lucide-vue-next'
import { api } from '../api/client'
import { renderNativeDocument, linkResourceReferences } from '../domain/nativeDocument'
import { ElMessageBox } from 'element-plus'

const props = defineProps<{ resourceId: string; kind: 'files' | 'deliverables'; requestedVersion?: number; projectId?: string }>()
const emit = defineEmits<{ saved: []; openReference: [href: string]; state: [state: { dirty: boolean; version: number; loading: boolean }] }>()
const content = ref(''), original = ref(''), version = ref(0), format = ref('markdown')
const mode = ref<'read' | 'edit' | 'split'>('read'), loading = ref(true), saving = ref(false), error = ref(''), notice = ref('')
const editor = ref<HTMLTextAreaElement>()
const dirty = computed(() => content.value !== original.value)
const documentName = ref('document.md'), latest = ref<{ content: string; version: number } | null>(null), compareOpen = ref(false)
watch([dirty, version, loading], () => emit('state', { dirty: dirty.value, version: version.value, loading: loading.value }), { immediate: true })
const allowImages = ref(false)
const references = ref<Array<{ id: string; name: string }>>([])
const html = computed(() => linkResourceReferences(renderNativeDocument(content.value, format.value, allowImages.value), references.value))
const draftKey = `finflow:document:${props.kind}:${props.resourceId}`
function persist() {
  if (!version.value || props.requestedVersion) return
  try {
    if (dirty.value) sessionStorage.setItem(draftKey, JSON.stringify({ content: content.value, version: version.value }))
    else sessionStorage.removeItem(draftKey)
  } catch { notice.value = '草稿暂存失败，请保存后再离开' }
}
async function load(restore = true) {
  loading.value = true; error.value = ''
  try {
    const doc = await api.nativeDocument(props.kind, props.resourceId, props.requestedVersion)
    content.value = original.value = doc.content; version.value = doc.version; format.value = doc.format; documentName.value = doc.name
    const draft = restore && !props.requestedVersion ? sessionStorage.getItem(draftKey) : null
    if (draft) {
      const saved = JSON.parse(draft)
      if (typeof saved.content === 'string' && Number.isInteger(saved.version)) {
        content.value = saved.content; mode.value = 'split'
        if (saved.version !== version.value) {
          version.value = saved.version
          latest.value = { content: doc.content, version: doc.version }; compareOpen.value = true
          notice.value = '已恢复草稿，服务器已有新版本；请对照后合并，草稿不会被覆盖'
        } else notice.value = '已恢复未保存的草稿'
      }
    }
  } catch (e) { error.value = e instanceof Error ? e.message : '文档打开失败' }
  finally { loading.value = false }
}
async function save() {
  if (saving.value || loading.value || !dirty.value || props.requestedVersion) return
  saving.value = true; error.value = ''
  const submitted = content.value
  try {
    const doc = await api.saveNativeDocument(props.kind, props.resourceId, submitted, version.value)
    version.value = doc.version; original.value = doc.content; persist(); notice.value = '已保存新版本'; emit('saved')
  } catch (e) { error.value = e instanceof Error ? e.message : '保存失败'; persist(); await compareLatest() }
  finally { saving.value = false }
}
async function reload() {
  if (dirty.value) {
    try { await ElMessageBox.confirm('放弃未提交修改并载入最新版本？', '重新载入文档', { confirmButtonText: '放弃修改并载入', cancelButtonText: '保留草稿', type: 'warning' }) } catch { return }
  }
  if (!props.requestedVersion) sessionStorage.removeItem(draftKey)
  notice.value = ''; void load(false)
}
function downloadDraft() {
  const url = URL.createObjectURL(new Blob([content.value], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = `${documentName.value.replace(/\.(md|markdown)$/i, '')}-${dirty.value ? 'draft' : 'v' + version.value}-${new Date().toISOString().replace(/[:.]/g, '-')}.md`; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function compareLatest() {
  try { const doc = await api.nativeDocument(props.kind, props.resourceId); latest.value = doc; compareOpen.value = true }
  catch (e) { notice.value = e instanceof Error ? e.message : '无法获取最新版本，草稿已保留' }
}
function useLatestAsBase() {
  if (!latest.value) return
  original.value = latest.value.content; version.value = latest.value.version; compareOpen.value = false; persist()
  notice.value = '已保留编辑内容，请完成合并后保存为新版本'
}
function openLink(event: MouseEvent) {
  const anchor = (event.target as HTMLElement).closest('a')
  if (!anchor) return
  event.preventDefault()
  const href = anchor.getAttribute('href') || ''
  if (href.startsWith('#')) {
    const target = Array.from((event.currentTarget as HTMLElement).querySelectorAll('h1,h2,h3,h4,h5,h6')).find(h => '#' + (h.textContent || '').trim().toLowerCase().replace(/\s+/g, '-') === decodeURI(href))
    target?.scrollIntoView({ block: 'start', behavior: 'smooth' }); return
  }
  if (/^https?:\/\//i.test(href)) window.open(href, '_blank', 'noopener,noreferrer')
  else if (href && !/^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith('resource:')) emit('openReference', href)
  else notice.value = '此链接类型不支持在平台打开'
}
function insert(before: string, after = '') {
  const node = editor.value
  if (!node) return
  const start = node.selectionStart, end = node.selectionEnd
  content.value = content.value.slice(0, start) + before + content.value.slice(start, end) + after + content.value.slice(end)
  persist(); node.focus()
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { persist(); event.preventDefault(); event.returnValue = '' } }
onMounted(() => {
  void load(); window.addEventListener('beforeunload', beforeUnload)
  if (props.projectId) void api.getProjectWorkspace(props.projectId).then(w => { references.value = w.resources }).catch(() => { notice.value = '引用名称暂未同步，可稍后重新打开' })
})
onBeforeUnmount(() => { persist(); window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <section class="native-document" aria-label="原生文档">
    <header class="native-toolbar">
      <nav v-if="!requestedVersion" aria-label="文档模式"><button v-for="item in [{id:'read',title:'阅读',icon:Eye},{id:'edit',title:'编辑',icon:Pencil},{id:'split',title:'编辑与预览',icon:Columns2}]" :key="item.id" :title="item.title" :aria-label="item.title" :aria-pressed="mode === item.id" @click="mode = item.id as typeof mode"><component :is="item.icon" :size="17"/></button></nav>
      <span>{{ loading ? '正在打开' : `第 ${version} 版 · ${dirty ? '有未提交修改 · 草稿暂存于本标签页' : '已保存'}` }}</span>
      <button title="重新载入最新版本" aria-label="重新载入最新版本" :disabled="saving || loading" @click="reload"><RotateCcw :size="17"/></button>
      <button v-if="!requestedVersion" title="保存" aria-label="保存文档" :disabled="!dirty || saving || loading" @click="save"><Save :size="17"/></button>
      <button :title="dirty ? '下载草稿' : '下载文档'" :aria-label="dirty ? '下载草稿' : '下载文档'" :disabled="loading" @click="downloadDraft"><Download :size="17"/></button>
      <button title="比较服务器版本" aria-label="比较服务器版本" :disabled="loading" @click="compareLatest"><Columns2 :size="17"/></button>
      <div class="native-extra-actions"><slot name="actions" /></div>
    </header>
    <section v-if="compareOpen && latest" class="version-comparison"><strong>服务器第 {{ latest.version }} 版 / {{ requestedVersion ? `引用的第 ${requestedVersion} 版` : '当前编辑内容' }}</strong><div><pre>{{ latest.content }}</pre><textarea v-model="content" :readonly="!!requestedVersion" aria-label="合并后的内容" @input="persist" /></div><button v-if="!requestedVersion" class="merge-action" @click="useLatestAsBase">保留编辑内容，基于此版本合并</button><button class="merge-action" @click="compareOpen = false">关闭对比</button></section>
    <p v-if="/!\[.*?\]\(/.test(content) && !allowImages" class="native-message">文档含图片，载入会请求图片来源地址。<button class="load-images" @click="allowImages = true">载入图片</button></p>
    <p v-if="error" class="native-message error" role="alert">{{ error }}</p><p v-if="notice" class="native-message" role="status">{{ notice }}</p>
    <div v-if="loading" class="native-message">正在打开文档…</div>
    <div v-else-if="version" class="native-body" :data-mode="mode">
      <div v-if="mode !== 'read'" class="native-source">
        <nav aria-label="格式工具"><button title="加粗" @click="insert('**','**')"><Bold :size="16"/></button><button title="斜体" @click="insert('*','*')"><Italic :size="16"/></button><button title="标题" @click="insert('\n## ')"><Heading2 :size="16"/></button><button title="列表" @click="insert('\n- ')"><List :size="16"/></button><button title="链接" @click="insert('[','](https://)')"><Link :size="16"/></button></nav>
        <textarea ref="editor" v-model="content" :disabled="saving" aria-label="Markdown 原文" spellcheck="false" @input="persist" @keydown.ctrl.s.prevent="save" @keydown.meta.s.prevent="save" />
      </div>
      <article v-if="mode !== 'edit'" class="native-rendered" @click="openLink" v-html="html" />
    </div>
  </section>
</template>

<style scoped>
.native-extra-actions{display:flex;align-items:center;gap:4px}
.native-extra-actions :deep(button){display:inline-flex;align-items:center;justify-content:center;width:30px;height:30px;border:0;border-radius:4px;background:transparent;color:#57606a;cursor:pointer}
.native-extra-actions :deep(button:hover){background:#eef2f5}
.native-document{display:flex;flex-direction:column;flex:1;min-height:0;min-width:0;background:#fff;color:#24292f;overflow:hidden}
.native-toolbar,.native-source nav{display:flex;gap:8px;align-items:center;padding:8px 12px;border-bottom:1px solid #d8dee4;flex-wrap:wrap}
.native-toolbar nav{display:flex;gap:4px;flex-shrink:0}.native-toolbar span{flex:1;min-width:0;font-size:12px;color:#57606a;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.native-toolbar>button{flex-shrink:0}
button{display:inline-flex;align-items:center;justify-content:center;width:32px;height:32px;border:1px solid transparent;background:transparent;border-radius:4px;color:inherit;cursor:pointer}button:hover,button[aria-pressed=true]{background:#e7f2ed;color:#176a49}button:disabled{opacity:.4;cursor:default}
.native-body{display:grid;grid-template-columns:minmax(0,1fr);flex:1;min-height:0;overflow:auto}.native-body[data-mode=split]{grid-template-columns:minmax(0,1fr) minmax(0,1fr)}
.native-source{display:flex;flex-direction:column;min-width:0;min-height:0;overflow:hidden;border-right:1px solid #d8dee4}.native-source textarea{flex:1;width:100%;height:0;min-height:0;overflow:auto;resize:none;border:0;outline:none;padding:18px 18px 32px;box-sizing:border-box;font:13px/1.7 ui-monospace,monospace;white-space:pre-wrap;overflow-wrap:anywhere;background:#f7f9fa;color:#24292f}
.native-toolbar,.native-source nav,.native-message{flex-shrink:0}
.native-body{grid-template-rows:minmax(0,1fr)}
.native-rendered{min-height:0;scroll-padding-bottom:24px}
.native-rendered{padding:24px;min-width:0;overflow:auto;overflow-wrap:anywhere;line-height:1.75;font-size:14px}.native-rendered :deep(h1){font-size:26px}.native-rendered :deep(h2){font-size:21px}.native-rendered :deep(h3){font-size:17px}.native-rendered :deep(pre){padding:14px;background:#f1f4f7;overflow:auto;white-space:pre;border-radius:4px}.native-rendered :deep(table){display:block;overflow:auto;border-collapse:collapse}.native-rendered :deep(th),.native-rendered :deep(td){padding:8px 12px;border:1px solid #d8dee4}.native-rendered :deep(blockquote){margin-left:0;border-left:3px solid #208761;padding-left:16px;color:#57606a}.native-rendered :deep(a){color:#0969da}.native-message{font-size:12px;padding:8px 12px;margin:0;background:#f0f6ff}.native-message.error{color:#b42318;background:#fff0ee}
@media(max-width:700px){.native-body[data-mode=split]{grid-template-columns:minmax(0,1fr)}.native-rendered{padding:16px}}
</style>
