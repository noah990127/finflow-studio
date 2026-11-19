<script setup lang="ts">
import { computed, ref } from 'vue'
import { citationPreviewRequest, matchesCitationPage, rows, type Dispatch, type Entry, type TacitState } from '../../domain/tacit'
const props = defineProps<{ citation: Entry; state: TacitState; dispatch: Dispatch; busy: boolean }>()
const page = ref<Entry | null>(null), failure = ref(''), priorOffsets = ref<number[]>([])
const source = computed(() => props.state.catalog.find(s => s.source_id === props.citation.source_id))
const clause = computed(() => rows(props.state.spec.sources?.find(s => s.source_id === props.citation.source_id)?.clauses).find(c => c.clause_id === props.citation.clause_id))
const version = computed(() => String(props.citation.version ?? source.value?.version ?? ''))
const request = computed(() => citationPreviewRequest(props.citation,version.value))
const quote = computed(() => String(props.citation.quote ?? props.citation.text ?? ''))
const sameVersion = computed(() => !!source.value && String(source.value.version) === version.value)
async function preview() {
  failure.value = ''
  if (!request.value) return
  const result = await props.dispatch('preview_source', request.value)
  if (!result) return
  if (!sameVersion.value || !matchesCitationPage(props.citation,result,version.value)) {
    failure.value = '当前原文与此处引用不一致，请重新检索或核对来源版本。'
    page.value = null
  } else {
    page.value = result
    priorOffsets.value = []
  }
}
async function movePage(forward:boolean) {
  if (!page.value || !sameVersion.value) return
  const offset = forward ? page.value.next_offset : priorOffsets.value.at(-1)
  if (typeof offset !== 'number') return
  const previous = Number(page.value.start)
  const result = await props.dispatch('preview_source',{source_id:props.citation.source_id,version:version.value,offset})
  if (!result) return
  if (result.start!==offset || (result.version!=null && String(result.version)!==version.value)) {
    failure.value = '原文位置或版本已经变化，请重新核对。'
    page.value = null
    return
  }
  if (forward) priorOffsets.value.push(previous)
  else priorOffsets.value.pop()
  page.value = result
}
</script>
<template>
  <blockquote class="t-citation"><p v-if="quote">{{ quote }}</p>
    <small>{{ citation.source_title || citation.title || source?.title || citation.source_id }} · 版本 {{ version || '未记录' }}<template v-if="citation.locator || clause?.locator"> · {{ citation.locator || clause?.locator }}</template><template v-else-if="citation.start != null"> · 字符 [{{citation.start}}, {{citation.end}})</template></small>
    <button type="button" v-if="sameVersion && request && !page" class="t-link" :disabled="busy" @click="preview">核对原文</button>
    <small v-if="source && !sameVersion" class="t-warn">来源已更新</small>
    <p v-if="failure" class="t-error" role="alert">{{ failure }}</p>
    <div v-if="page && sameVersion"><small>原文字符 [{{page.start}}, {{page.end}}) / {{page.total}}</small><pre class="t-source-text">{{ page.text }}</pre>
      <div class="t-actions"><button v-if="priorOffsets.length" type="button" class="t-link" :disabled="busy" @click="movePage(false)">上一段原文</button>
        <button v-if="typeof page.next_offset==='number'" type="button" class="t-link" :disabled="busy" @click="movePage(true)">下一段原文</button>
        <button type="button" class="t-link" @click="page=null">收起原文</button></div></div>
  </blockquote>
</template>
