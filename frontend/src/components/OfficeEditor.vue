<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { FilePenLine, LoaderCircle } from 'lucide-vue-next'
import { api } from '../api/client'

type OfficeEvent = { data?: unknown }
type OfficeEventHandler = (event: OfficeEvent) => void
type DocEditorInstance = { destroyEditor?: () => void }

declare global {
  interface Window { DocsAPI?: { DocEditor: new (id: string, config: Record<string, unknown>) => DocEditorInstance } }
}
const props = withDefaults(defineProps<{ resourceId: string; kind: 'files' | 'extract-jobs' | 'deliverables'; mode?: 'view' | 'edit' }>(), { mode: 'edit' })
const emit = defineEmits<{ unavailable: [mode: 'view' | 'edit'] }>()
const elementId = `onlyoffice-${crypto.randomUUID()}`
const wrapper = ref<HTMLElement | null>(null)
const host = ref<HTMLElement | null>(null)
const loading = ref(true), error = ref('')
let editor: DocEditorInstance | undefined
let disposed = false
let openGeneration = 0
let readyTimer: number | undefined
let unavailableSent = false

function clearReadyTimer() {
  if (readyTimer !== undefined) window.clearTimeout(readyTimer)
  readyTimer = undefined
}

function destroyEditor() {
  clearReadyTimer()
  try { editor?.destroyEditor?.() } catch { /* The host may already be gone during a fast tab switch. */ }
  editor = undefined
  wrapper.value?.querySelectorAll('iframe').forEach(frame => frame.remove())
  host.value?.replaceChildren()
}

function loadScript(baseUrl: string) {
  return new Promise<void>((resolve, reject) => {
    if (window.DocsAPI) return resolve()
    const src = `${baseUrl.replace(/\/$/, '')}/web-apps/apps/api/documents/api.js`
    let script = document.querySelector<HTMLScriptElement>('script[data-finbtp-onlyoffice], script[data-finflow-onlyoffice]')
    if (script && (script.dataset.loadState === 'error' || script.dataset.loadState === 'loaded' || script.src !== new URL(src, window.location.href).href)) {
      script.remove()
      script = null
    }
    let created = false
    if (!script) {
      script = document.createElement('script')
      script.src = src
      script.dataset.finbtpOnlyoffice = 'true'
      script.dataset.loadState = 'loading'
      created = true
    }

    let settled = false
    const finish = (reason?: Error) => {
      if (settled) return
      settled = true
      window.clearTimeout(timeout)
      script?.removeEventListener('load', loaded)
      script?.removeEventListener('error', failed)
      if (reason) reject(reason); else resolve()
    }
    const loaded = () => {
      if (script) script.dataset.loadState = 'loaded'
      finish(window.DocsAPI ? undefined : new Error('在线编辑器脚本没有正确初始化'))
    }
    const failed = () => {
      if (script) { script.dataset.loadState = 'error'; script.remove() }
      finish(new Error('在线编辑器没有连接'))
    }
    const timeout = window.setTimeout(() => {
      if (script && !window.DocsAPI) { script.dataset.loadState = 'error'; script.remove() }
      finish(new Error('连接在线编辑器超时'))
    }, 15000)
    script.addEventListener('load', loaded, { once: true })
    script.addEventListener('error', failed, { once: true })
    if (created) document.head.appendChild(script)
  })
}

function fail(reason: unknown, generation: number) {
  if (disposed || generation !== openGeneration) return
  destroyEditor()
  loading.value = false
  error.value = reason instanceof Error ? reason.message : typeof reason === 'string' ? reason : '文件没有打开'
  if (!unavailableSent) { unavailableSent = true; emit('unavailable', props.mode) }
}

async function open() {
  const generation = ++openGeneration
  loading.value = true; error.value = ''
  unavailableSent = false
  try {
    const session = await api.createOfficeSession(props.kind, props.resourceId, props.mode)
    if (disposed || generation !== openGeneration) return
    if (!session.enabled) throw new Error(session.message || '在线编辑服务尚未启用')
    await loadScript(session.documentServerUrl)
    await nextTick()
    if (disposed || generation !== openGeneration) return
    if (!window.DocsAPI) throw new Error('在线编辑器没有正确加载')
    if (!host.value || !document.getElementById(elementId)) throw new Error('文件查看区域已经关闭')

    const configuredEvents = (session.config.events ?? {}) as Record<string, OfficeEventHandler>
    const callConfigured = (name: string, event: OfficeEvent) => {
      const handler = configuredEvents[name]
      if (typeof handler === 'function') handler(event)
    }
    const config = {
      ...session.config,
      width: '100%',
      height: '100%',
      events: {
        ...configuredEvents,
        onAppReady: (event: OfficeEvent) => {
          callConfigured('onAppReady', event)
          if (disposed || generation !== openGeneration) return
          clearReadyTimer()
          loading.value = false
        },
        onError: (event: OfficeEvent) => {
          callConfigured('onError', event)
          const detail = typeof event.data === 'object' && event.data !== null && 'errorDescription' in event.data
            ? String((event.data as { errorDescription: unknown }).errorDescription)
            : '在线编辑器没有完成文件加载'
          fail(detail, generation)
        },
      },
    }
    editor = new window.DocsAPI.DocEditor(elementId, config)
    readyTimer = window.setTimeout(() => fail(new Error('在线编辑器长时间没有响应'), generation), 30000)
  } catch (reason) { fail(reason, generation) }
}
onMounted(() => { void open() })
onBeforeUnmount(() => {
  disposed = true
  openGeneration += 1
  destroyEditor()
})
</script>

<template>
  <div ref="wrapper" class="office-editor-wrap">
    <div :id="elementId" ref="host" class="office-editor-host"></div>
    <div v-if="loading" class="office-editor-state"><LoaderCircle class="spin" :size="22"/>正在打开在线编辑器</div>
    <div v-else-if="error" class="office-editor-state error"><FilePenLine :size="25"/><strong>暂时不能在线{{ mode === 'view' ? '查看' : '编辑' }}</strong><p>{{ error }}</p></div>
  </div>
</template>
