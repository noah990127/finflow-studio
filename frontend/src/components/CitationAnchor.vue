<script setup lang="ts">
import { autoUpdate, computePosition, flip, offset, shift } from '@floating-ui/dom'
import { BookOpen, ExternalLink, FileText } from 'lucide-vue-next'
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import type { CitationSource } from '../api/client'

const props = withDefaults(defineProps<{ citation: CitationSource; label?: string; compact?: boolean }>(), { label: '', compact: false })
const emit = defineEmits<{ openSource: [citation: CitationSource] }>()
const anchor = ref<HTMLElement | null>(null), card = ref<HTMLElement | null>(null), open = ref(false)
let cleanup: (() => void) | undefined
let closeTimer: number | undefined

const displayLabel = computed(() => props.label || props.citation.formatted || props.citation.source_name)
const excerpt = computed(() => {
  const value = props.citation.text?.replace(/\s+/g, ' ').trim() || '当前来源没有可展示的原文摘要。'
  return value.length > 220 ? `${value.slice(0, 220)}…` : value
})
const location = computed(() => {
  const labels: Record<string, string> = { page: '第 {value} 页', slide: '第 {value} 页', paragraph: '第 {value} 段', table: '表格 {value}', sheet: '工作表 {value}', rows: '第 {value} 行', row: '第 {value} 行', column: '字段 {value}', url: '{value}', start_seconds: '{value} 秒' }
  return Object.entries(props.citation.location || {}).filter(([key, value]) => key !== 'type' && value !== null && value !== '').map(([key, value]) => (labels[key] || `${key} {value}`).replace('{value}', String(value))).join(' · ') || '文件原文'
})

async function position() {
  if (!anchor.value || !card.value) return
  const result = await computePosition(anchor.value, card.value, { placement: 'top', middleware: [offset(8), flip(), shift({ padding: 12 })] })
  Object.assign(card.value.style, { left: `${result.x}px`, top: `${result.y}px` })
}
async function show() {
  if (closeTimer) window.clearTimeout(closeTimer)
  open.value = true
  await nextTick()
  cleanup?.()
  if (anchor.value && card.value) cleanup = autoUpdate(anchor.value, card.value, position)
}
function scheduleClose() {
  closeTimer = window.setTimeout(() => { open.value = false; cleanup?.(); cleanup = undefined }, 140)
}
function openSource() { emit('openSource', props.citation); open.value = false }
onBeforeUnmount(() => { cleanup?.(); if (closeTimer) window.clearTimeout(closeTimer) })
</script>

<template>
  <button ref="anchor" type="button" class="citation-anchor" :class="{ compact }" :aria-label="`查看来源：${citation.source_name}`" @mouseenter="show" @mouseleave="scheduleClose" @focus="show" @blur="scheduleClose" @click="show">
    <BookOpen v-if="compact" :size="12"/><span>{{ displayLabel }}</span>
  </button>
  <Teleport to="body">
    <article v-if="open" ref="card" class="citation-popover" role="dialog" @mouseenter="show" @mouseleave="scheduleClose">
      <header><span><FileText :size="15"/></span><div><small>参考来源</small><strong>{{ citation.source_name }}</strong></div></header>
      <dl><div><dt>位置</dt><dd>{{ location }}</dd></div><div v-if="citation.version"><dt>版本</dt><dd>第 {{ citation.version }} 版</dd></div></dl>
      <blockquote>{{ excerpt }}</blockquote>
      <footer><span v-if="citation.content_hash">来源内容已固化</span><button v-if="citation.resource_id" type="button" @click="openSource">在工作区查看<ExternalLink :size="13"/></button></footer>
    </article>
  </Teleport>
</template>
