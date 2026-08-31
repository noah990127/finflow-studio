<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { FilePenLine, LoaderCircle } from 'lucide-vue-next'
import { api } from '../api/client'

declare global {
  interface Window { DocsAPI?: { DocEditor: new (id: string, config: Record<string, unknown>) => { destroyEditor?: () => void } } }
}
const props = withDefaults(defineProps<{ resourceId: string; kind: 'files' | 'extract-jobs' | 'deliverables'; mode?: 'view' | 'edit' }>(), { mode: 'edit' })
const emit = defineEmits<{ unavailable: [mode: 'view' | 'edit'] }>()
const elementId = `onlyoffice-${crypto.randomUUID()}`
const loading = ref(true), error = ref('')
let editor: { destroyEditor?: () => void } | undefined

function loadScript(baseUrl: string) {
  return new Promise<void>((resolve, reject) => {
    if (window.DocsAPI) return resolve()
    const existing = document.querySelector<HTMLScriptElement>('script[data-finflow-onlyoffice]')
    if (existing) { existing.addEventListener('load', () => resolve(), { once: true }); existing.addEventListener('error', () => reject(new Error('在线编辑器没有连接')), { once: true }); return }
    const script = document.createElement('script')
    script.src = `${baseUrl.replace(/\/$/, '')}/web-apps/apps/api/documents/api.js`
    script.dataset.finflowOnlyoffice = 'true'; script.onload = () => resolve(); script.onerror = () => reject(new Error('在线编辑器没有连接'))
    document.head.appendChild(script)
  })
}
async function open() {
  loading.value = true; error.value = ''
  try {
    const session = await api.createOfficeSession(props.kind, props.resourceId, props.mode)
    if (!session.enabled) throw new Error(session.message || '在线编辑服务尚未启用')
    await loadScript(session.documentServerUrl)
    if (!window.DocsAPI) throw new Error('在线编辑器没有正确加载')
    editor = new window.DocsAPI.DocEditor(elementId, session.config)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '文件没有打开'; emit('unavailable', props.mode) }
  finally { loading.value = false }
}
onMounted(open)
onBeforeUnmount(() => editor?.destroyEditor?.())
</script>

<template>
  <div class="office-editor-wrap">
    <div :id="elementId" class="office-editor-host"></div>
    <div v-if="loading" class="office-editor-state"><LoaderCircle class="spin" :size="22"/>正在打开在线编辑器</div>
    <div v-else-if="error" class="office-editor-state error"><FilePenLine :size="25"/><strong>暂时不能在线{{ mode === 'view' ? '查看' : '编辑' }}</strong><p>{{ error }}</p></div>
  </div>
</template>
