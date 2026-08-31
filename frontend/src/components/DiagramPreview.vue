<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps<{ kind: 'mermaid' | 'excalidraw'; source: string }>()
const host = ref<HTMLElement | null>(null), error = ref(''), loading = ref(false)
let renderVersion = 0

async function renderDiagram() {
  const version = ++renderVersion
  error.value = ''
  loading.value = true
  await nextTick()
  try {
    if (!host.value) return
    host.value.replaceChildren()
    if (props.kind === 'mermaid') {
      const mermaid = (await import('mermaid')).default
      mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral', flowchart: { htmlLabels: false, curve: 'basis' } })
      const id = `finflow-mermaid-${crypto.randomUUID()}`
      const rendered = await mermaid.render(id, props.source.trim())
      if (version !== renderVersion || !host.value) return
      host.value.innerHTML = rendered.svg
    } else {
      const data = JSON.parse(props.source) as { type?: string; elements?: unknown[]; appState?: Record<string, unknown>; files?: Record<string, unknown> }
      if (data.type !== 'excalidraw' || !Array.isArray(data.elements)) throw new Error('Excalidraw 文件结构不完整')
      const { exportToSvg } = await import('@excalidraw/excalidraw')
      const svg = await exportToSvg({
        elements: data.elements as never,
        appState: { ...(data.appState ?? {}), exportBackground: true, viewBackgroundColor: '#ffffff' } as never,
        files: (data.files ?? {}) as never,
        exportPadding: 28,
        skipInliningFonts: true,
      })
      if (version !== renderVersion || !host.value) return
      host.value.replaceChildren(svg)
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '图形没有成功渲染'
  } finally {
    if (version === renderVersion) loading.value = false
  }
}

watch(() => [props.kind, props.source], renderDiagram, { immediate: true })
onBeforeUnmount(() => { renderVersion += 1 })
</script>

<template>
  <section class="diagram-preview" :data-kind="kind">
    <div v-if="loading" class="diagram-state">正在绘制图形</div>
    <div v-if="error" class="diagram-state error"><strong>图形无法显示</strong><p>{{ error }}</p></div>
    <div ref="host" class="diagram-canvas" :class="{ hidden: loading || error }"></div>
  </section>
</template>
