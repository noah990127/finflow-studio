<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ExternalLink } from 'lucide-vue-next'
import type { DocumentPreview } from '../api/client'
const props = defineProps<{ document: DocumentPreview }>()
const selected = ref(0)
const original = ref(false)
const sources = computed(() => props.document.research_sources || [])
const source = computed(() => sources.value[selected.value])
const sourceUrl = computed(() => {
  try {
    const url = new URL(source.value?.url || '')
    return ['http:', 'https:'].includes(url.protocol) ? url.href : ''
  } catch { return '' }
})
watch(() => props.document, () => { selected.value = 0; original.value = false })
watch(selected, () => { original.value = false })
const paragraphs = computed(() => (source.value?.text || source.value?.summary || '').split(/\n+/).filter(Boolean))
</script>

<template>
  <section class="research-reader" aria-label="来源阅读">
    <header class="reader-toolbar">
      <label v-if="sources.length > 1">来源 <select v-model.number="selected"><option v-for="(item, index) in sources" :key="index" :value="index">{{ index + 1 }}. {{ item.title }}</option></select></label>
      <span v-else>来源资料</span>
      <nav>
        <template v-if="source?.embedAllowed && sourceUrl">
          <button type="button" :aria-pressed="!original" @click="original = false">阅读版</button>
          <button type="button" :aria-pressed="original" @click="original = true">原网页</button>
        </template>
        <a v-if="sourceUrl" :href="sourceUrl" target="_blank" rel="noopener noreferrer" title="在新窗口打开原网页"><ExternalLink :size="16"/>原文</a>
      </nav>
    </header>
    <details v-if="document.warnings.length" class="reader-warnings"><summary>{{ document.warnings.length }} 条来源未能读取</summary><p v-for="warning in document.warnings" :key="warning">{{ warning }}</p></details>
    <template v-if="source">
      <iframe v-if="original && sourceUrl" :src="sourceUrl" :title="source.title" sandbox="allow-scripts" referrerpolicy="no-referrer" class="reader-original"></iframe>
      <article v-else class="reader-article">
        <header><span>{{ source.text ? '网页正文快照' : '搜索摘要 · 尚未采集正文' }}</span><time v-if="source.capturedAt">{{ new Date(source.capturedAt).toLocaleString() }}</time></header>
        <h1>{{ source.title }}</h1>
        <a v-if="sourceUrl" class="reader-source" :href="sourceUrl" target="_blank" rel="noopener noreferrer">{{ sourceUrl }}</a>
        <p v-for="(paragraph, index) in paragraphs" :key="index">{{ paragraph }}</p>
      </article>
    </template>
  </section>
</template>

<style scoped>
.research-reader { min-width: 0; width: 100%; overflow: auto; color: #26312e; background: #fff; }
.reader-toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 12px 20px; border-bottom: 1px solid #e5e9e7; }
.reader-toolbar label { display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1; font-size: 12px; }
select { min-width: 0; width: 100%; max-width: 600px; padding: 6px; border: 1px solid #d8dedb; background: white; border-radius: 4px; }
nav { display: flex; align-items: center; gap: 12px; }
nav button { border: 0; background: none; cursor: pointer; color: #69746e; padding: 5px 0; }
nav button[aria-pressed="true"] { color: #15715c; border-bottom: 2px solid currentColor; }
nav a { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; }
a { color: #15715c; overflow-wrap: anywhere; }
.reader-article { max-width: 780px; margin: auto; padding: 28px 24px 48px; }
.reader-article header { display: flex; flex-wrap: wrap; gap: 12px; color: #758079; font-size: 12px; }
h1 { font-size: 24px; line-height: 1.45; overflow-wrap: anywhere; margin: 16px 0 10px; letter-spacing: 0; }
.reader-source { display: block; font-size: 12px; margin-bottom: 24px; }
p { font-size: 15px; line-height: 1.9; margin: 0 0 14px; overflow-wrap: anywhere; white-space: pre-wrap; }
.reader-warnings { padding: 12px 20px; color: #78521b; }
.reader-original { width: 100%; height: 70dvh; border: 0; }
@media(max-width: 600px) { .reader-article { padding: 20px 16px; } h1 { font-size: 20px; } }
</style>
