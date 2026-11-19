<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { WorkspaceResource } from '../../api/client'
import { extractionOffset, firstUnreadOffset, label, lines, nextExtractionOffset, splitLines, type Dispatch, type Entry, type TacitCatalogEntry, type TacitState } from '../../domain/tacit'
const props = defineProps<{ state: TacitState; resources: WorkspaceResource[]; busy: boolean; dispatch: Dispatch }>()
const emit = defineEmits<{ upload: []; extracting: [boolean] }>()
const selected = ref<string[]>([]), choose = ref(false), addingNote = ref(false)
const note = ref(''), noteTitle = ref(''), goal = ref(''), included = ref(''), excluded = ref(''), period = ref(''), region = ref('')
const savingScope = ref(false), scopeRevision = ref(0)
const files = computed(() => props.resources.filter(item => ['KNOWLEDGE_FILE','OFFICE_FILE','DATA_FILE'].includes(item.resourceType)))
const scopeReady = computed(() => Boolean(String(props.state.spec.scope?.goal ?? '').trim() && splitLines(lines(props.state.spec.scope?.in_scope)).length))
function sourceRow(id:string) { return props.state.spec.sources?.find(s=>s.source_id === id) }
function eligible(source:TacitCatalogEntry) {
  return !source.incomplete && sourceRow(source.source_id)?.disposition !== 'excluded' && extractionOffset(props.state,source) !== null
}
function resumeOffset(source:TacitCatalogEntry) {
  const offset=firstUnreadOffset(props.state.coverage?.[source.source_id])
  return source.total_characters !== undefined && offset>=source.total_characters ? 0 : offset
}
const noteOrFile = (source: TacitCatalogEntry) => source.source_type === 'user' ? '专家记录' : '项目文件'
watch(() => JSON.stringify(props.state.spec.scope ?? {}), () => {
  const scope = props.state.spec.scope ?? {}
  goal.value = lines(scope.goal); included.value = lines(scope.in_scope); excluded.value = lines(scope.out_of_scope)
  region.value = lines(scope.region); period.value = lines(scope.period); savingScope.value = false; scopeRevision.value = props.state.revision
}, { immediate: true })
function editScope() {
  if (!savingScope.value) scopeRevision.value = props.state.revision
  savingScope.value = true
}
async function saveScope() {
  const scope = { goal: goal.value.trim(), in_scope: splitLines(included.value), out_of_scope: splitLines(excluded.value), period: period.value.trim(), region: region.value.trim() }
  if (await props.dispatch('save_batch', {section:'scope', scope}, scopeRevision.value)) savingScope.value = false
  else scopeRevision.value = props.state.revision
}
const selectionRevision = ref(0)
function select() {
  selected.value = [...props.state.source_ids]
  selectionRevision.value = props.state.revision
  choose.value = true
}
async function saveSelection() {
  if (await props.dispatch('select_sources', {source_ids:selected.value}, selectionRevision.value)) choose.value = false
  else { selected.value = [...props.state.source_ids]; selectionRevision.value = props.state.revision }
}
async function addNote() {
  if (await props.dispatch('add_note', {title:noteTitle.value.trim(), text:note.value.trim()})) {
    addingNote.value = false; noteTitle.value = ''; note.value = ''
  }
}
const page = ref<Entry | null>(null), pageSource = ref(''), running = ref(false), stopRequested = ref(false), progress = ref(''), localError = ref('')
let alive = true
async function read(sourceId: string, offset = 0) {
  const result = await props.dispatch('read_source', {source_id:sourceId,offset})
  if (result) { pageSource.value = sourceId; page.value = result }
}
const pending = computed(() => props.state.catalog.filter(eligible))
async function extract(sources: TacitCatalogEntry[]) {
  if (running.value || props.busy || !scopeReady.value) return
  stopRequested.value = false; running.value = true; localError.value = ''; emit('extracting', true)
  try {
    for (const src of sources) {
      let offset = extractionOffset(props.state, src)
      while (alive && !stopRequested.value && offset !== null) {
        progress.value = `正在处理《${src.title}》，从第 ${offset + 1} 个字符继续`
        const result = await props.dispatch('extract', { source_id:src.source_id, offset })
        if (!result) { progress.value = '抽取已暂停，可从上次保存的位置继续。'; return }
        if (result.already_applied) { progress.value = '此页已提交，台账已刷新。请从最新位置继续。'; return }
        offset = nextExtractionOffset(result, offset)
      }
      if (!alive || stopRequested.value) break
    }
    progress.value = stopRequested.value ? '已暂停，已处理的分页已保存。' : '本次抽取已完成，请到业务台账核对草稿。'
  } catch (reason) { localError.value = reason instanceof Error ? reason.message : '抽取失败' }
  finally { running.value = false; if (alive) emit('extracting', false) }
}
onBeforeUnmount(() => { alive = false; stopRequested.value = true; emit('extracting', false) })
</script>
<template>
  <section class="t-section">
    <div class="t-section-head"><h2>梳理范围</h2></div>
    <form class="t-form" @submit.prevent="saveScope" @input="editScope">
      <label>业务目标<input v-model="goal" placeholder="例如，梳理采购申请的审批条件和例外" required></label>
      <label>纳入范围<textarea v-model="included" rows="2" placeholder="每行填写一项业务范围" required/></label>
      <details><summary>地区、期间和排除范围</summary><div class="t-form">
        <label>地区<input v-model="region"></label><label>适用期间<input v-model="period"></label>
        <label>排除范围<textarea v-model="excluded" rows="2" placeholder="每行一项"/></label>
      </div></details>
      <div><button class="t-btn" :disabled="busy || running || !savingScope || !goal.trim() || !included.trim()">保存范围</button></div>
    </form>
  </section>
  <section class="t-section">
    <div class="t-section-head"><h2>梳理来源 <small>{{ state.catalog.length }} 份</small></h2>
      <div class="t-actions"><button class="t-btn" :disabled="busy || running" @click="select">选择项目文件</button><button class="t-btn" :disabled="busy || running" @click="addingNote = !addingNote">记录专家口述</button></div></div>
    <form v-if="choose" class="t-box t-form" @submit.prevent="saveSelection">
      <div v-if="!files.length">项目中还没有文件。<button type="button" class="t-link" @click="$emit('upload')">上传资料</button></div>
      <label v-for="file in files" :key="file.id" class="t-check"><input type="checkbox" v-model="selected" :value="file.id"> {{ file.name }} <small>第 {{ file.currentVersion }} 版</small></label>
      <label v-for="id in state.source_ids.filter(i => !files.some(f => f.id === i))" :key="id" class="t-check"><input type="checkbox" v-model="selected" :value="id"> {{ state.catalog.find(s=>s.source_id === id)?.title || id }}</label>
      <div class="t-actions"><button class="t-btn primary" :disabled="busy">保存来源范围</button><button type="button" class="t-btn" @click="choose = false">取消</button></div>
    </form>
    <form v-if="addingNote" class="t-box t-form" @submit.prevent="addNote"><label>记录标题<input v-model="noteTitle" required></label>
      <label>专家原话<textarea v-model="note" required rows="4"/></label>
      <div class="t-actions"><button class="t-btn primary" :disabled="busy || !note.trim() || !noteTitle.trim()">保存口述</button>
      <button type="button" class="t-btn" @click="addingNote = false">取消</button></div></form>
    <p v-if="!state.catalog.length && !choose" class="t-muted">选择需要梳理的政策、业务文档，或记录专家口述。</p>
    <ul class="t-list"><li v-for="source in state.catalog" :key="source.source_id">
      <div><strong>{{ source.title }}</strong><small>{{ noteOrFile(source) }}<template v-if="source.author"> · {{ source.author }}</template> · 版本 {{ source.version }} · {{ label(sourceRow(source.source_id)?.read_status || source.read_status || 'unread') }}
        · {{ sourceRow(source.source_id)?.disposition === 'excluded' ? '已排除' : extractionOffset(state, source) === null ? '抽取完成' : extractionOffset(state, source) ? '已保存部分抽取结果' : '待抽取' }}</small>
        <p class="t-warn" v-if="source.incomplete">{{ source.reason || '解析内容不完整，请核对来源。' }}</p></div>
      <div class="t-actions"><button class="t-link" :disabled="busy || running" @click="read(source.source_id,resumeOffset(source))">{{resumeOffset(source) ? '继续阅读' : '阅读原文'}}</button>
        <button v-if="eligible(source)" class="t-link" :disabled="busy || running || !scopeReady" @click="extract([source])">抽取</button></div></li></ul>
    <div class="t-actions" v-if="state.catalog.length"><button v-if="!running" class="t-btn primary" :disabled="busy || !scopeReady || !pending.length" @click="extract(pending)">抽取剩余资料</button>
      <button v-else class="t-btn" :disabled="stopRequested" @click="stopRequested=true">{{ stopRequested ? '正在保存本页…' : '处理完本页后暂停' }}</button>
      <small v-if="!scopeReady">先保存业务目标与纳入范围</small></div>
    <p v-if="progress" role="status">{{ progress }}</p><p v-if="localError" role="alert" class="t-error">{{ localError }}</p>
    <article v-if="page" class="t-box"><div class="t-section-head"><h3>{{ state.catalog.find(s => s.source_id === pageSource)?.title }} · 原文</h3><button class="t-link" @click="page=null">收起</button></div>
      <small>字符 {{ page.start }} – {{ page.end }} / {{ page.total }}</small>
      <details v-if="state.coverage?.[pageSource]?.length"><summary>已返回的字符区间</summary><p>{{state.coverage[pageSource].map(([start,end])=>`[${start}, ${end})`).join('、')}}</p></details><pre class="t-source-text">{{ page.text }}</pre>
      <div class="t-actions"><button class="t-btn" :disabled="busy || running || page.start === 0" @click="read(pageSource)">回到开头</button>
        <button class="t-btn" :disabled="busy || running || typeof page.next_offset !== 'number'" @click="read(pageSource, Number(page.next_offset))">读下一页</button></div></article>
  </section>
</template>
