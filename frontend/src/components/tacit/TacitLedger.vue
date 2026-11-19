<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { citationPreviewRequest, entryIssueIndex, label, newRequestId, record, rows, sectionIds, type Dispatch, type Entry, type Section, type TacitState } from '../../domain/tacit'
import TacitEntryEditor from './TacitEntryEditor.vue'
import TacitFields from './TacitFields.vue'
const props = defineProps<{ state: TacitState; dispatch: Dispatch; busy:boolean }>()
const section = ref<Section>('rules'), editor = ref<Entry|null>(null), confirmation = ref(''), confirmId = ref(''), removeId = ref('')
const answerId = ref(''), answerText = ref(''), answerRevision = ref(0)
const preview = ref<Entry|null>(null), previewTitle = ref(''), ownerRevision = ref(0)
const selected = computed(()=>props.state.spec[section.value] ?? [])
const sectionKey = computed(()=>sectionIds[section.value])
const sections = Object.keys(sectionIds) as Section[]
const confirmable = computed(()=>['data_objects','data_tables','rules','processes'].includes(section.value))
const viewingIssues = ref(false), search = ref('')
const filtered = computed(()=> selected.value.filter(it => [title(it), it[sectionKey.value]].some(v=>String(v).includes(search.value.trim()))))
const pageNumber = ref(1), expanded = ref<Set<string>>(new Set())
const pageCount = computed(()=>Math.max(1, Math.ceil(filtered.value.length/20)))
const visible = computed(()=>filtered.value.slice((pageNumber.value-1)*20,pageNumber.value*20))
const rowIssues = computed(()=>entryIssueIndex(props.state,section.value))
watch([section,search],()=>{ pageNumber.value=1; expanded.value=new Set() })
watch(pageCount, value=>{ pageNumber.value=Math.min(pageNumber.value,value) })
function toggleDetails(e:Event,id:string) {
  if ((e.currentTarget as HTMLDetailsElement).open) expanded.value.add(id)
  else expanded.value.delete(id)
}
function title(item:Entry) { return String(item.name || item.title || item.question || item[sectionKey.value] || '未命名条目') }
function choose(s:Section) { section.value=s; editor.value=null; confirmId.value=''; removeId.value=''; answerId.value=''; preview.value=null; search.value='' }
function create() {
  const fields: Record<Section, Entry> = {
    rules:{name:'',scope:'',condition:{operator:'unresolved'},results:[],inputs:[],outputs:[],exceptions:[],process_ids:[],process_disposition:'independent',process_reason:'',status:'proposed',evidence:[]},
    data_objects:{name:'',identity_basis:'',fields:[],relationships:[],status:'proposed',evidence:[]},
    data_tables:{name:'',locator:'',columns:[],read_status:'unread',status:'proposed',evidence:[]},
    processes:{name:'',scope:'',trigger:'',roles:[],inputs:[],outputs:[],steps:[],branches:[],rule_ids:[],disposition:'included',status:'proposed',evidence:[]},
    gaps:{question:'',impact:'',blocking:true,status:'open',resolution:'',rule_ids:[],process_ids:[],data_table_ids:[],evidence:[]},
    acceptance_cases:{name:'',kind:'normal',rule_ids:[],process_ids:[],inputs:{},expected_outputs:{},expected_path:[],status:'needs_confirmation',assumptions:[]},
    sources:{},
  }
  editor.value = { [sectionKey.value]:newRequestId(), ...fields[section.value] }
}
function showIssue(path:string) {
  const found = sections.find(s=>path.includes(s))
  if (found) { choose(found); viewingIssues.value=false }
}
function startAnswer(id:string) {
  answerId.value=id; answerText.value=''; answerRevision.value=props.state.revision
}
async function answerGap(resolve:boolean) {
  const result=await props.dispatch('answer_gap',{gap_id:answerId.value,text:answerText.value.trim(),resolve},answerRevision.value)
  if (result) { answerId.value=''; answerText.value='' }
}
function beginReview(id:string, remove=false) {
  removeId.value = remove ? id : ''; confirmId.value = remove ? '' : id; confirmation.value=''; ownerRevision.value=props.state.revision
}
async function review(remove:boolean) {
  const result = remove
    ? await props.dispatch('remove_item',{section:section.value,item_id:removeId.value},ownerRevision.value)
    : await props.dispatch('confirm_item',{section:section.value,item_id:confirmId.value,text:confirmation.value.trim()},ownerRevision.value)
  if (result) { removeId.value=''; confirmId.value=''; confirmation.value='' }
}
function citation(e:Entry) {
  const source = props.state.spec.sources?.find(s=>s.source_id===e.source_id)
  const clause = rows(source?.clauses).find(c=>c.clause_id===e.clause_id)
  return { name:String(source?.title || e.source_id), locator:String(clause?.locator ?? '') }
}
async function showQuote(e:Entry) {
  const ref = citation(e)
  const current = props.state.catalog.find(s => s.source_id === e.source_id)
  const request = citationPreviewRequest(e,current?.version)
  if (!request) return
  preview.value=null
  const result = await props.dispatch('preview_source',request)
  if (result) { preview.value=result; previewTitle.value=`${ref.name} · ${ref.locator}` }
}
</script>
<template>
  <section class="t-section">
    <div class="t-section-head"><h2>业务台账</h2><button class="t-btn" :aria-expanded="viewingIssues" @click="viewingIssues=!viewingIssues">校验问题（{{ state.issues.length }}）</button></div>
    <div v-if="viewingIssues" class="t-box"><p v-if="!state.issues.length">当前没有已记录的校验问题。</p>
      <ul class="t-issues"><li v-for="(issue,i) in state.issues" :key="i"><strong>{{ issue.message }}</strong> <button type="button" class="t-link" @click="showIssue(issue.path)">{{ issue.path }}</button><small>{{ issue.code }}</small></li></ul></div>
    <div class="t-filter"><label>条目类型<select :value="section" @change="choose(($event.target as HTMLSelectElement).value as Section)">
      <option v-for="s in sections" :key="s" :value="s">{{ label(s) }}（{{ state.spec[s]?.length || 0 }}）</option></select></label>
      <label>查找条目<input v-model="search" placeholder="名称或编号"></label>
      <button v-if="section !== 'sources'" class="t-btn" :disabled="busy" @click="create">新增条目</button></div>
    <TacitEntryEditor v-if="editor" :key="String(editor[sectionKey])" :original="editor" :id-key="sectionKey" :section="section" :state="state" :busy="busy" :dispatch="dispatch" @close="editor=null"/>
    <p v-if="!filtered.length" class="t-muted">{{ selected.length ? '没有符合条件的条目。' : '这一节还没有条目。抽取资料后可在这里核对。' }}</p>
    <details v-for="item in visible" :key="String(item[sectionKey])" class="t-detail" @toggle="toggleDetails($event,String(item[sectionKey]))">
      <summary>{{ title(item) }} <small>{{ rowIssues.has(String(item[sectionKey])) ? '待核对 · '+rowIssues.get(String(item[sectionKey]))?.size+' 项' : label(item.status || item.read_status) }}</small></summary>
      <template v-if="expanded.has(String(item[sectionKey]))">
      <ul v-if="rowIssues.has(String(item[sectionKey]))" class="t-warn"><li v-for="message in rowIssues.get(String(item[sectionKey]))" :key="message">{{ message }}</li></ul>
      <TacitFields :fields="Object.fromEntries(Object.entries(item).filter(([k])=> ![sectionKey,'evidence','name','title','question','status'].includes(k)))"/>
      <blockquote v-for="(e,i) in rows(item.evidence)" :key="i"><p>{{ e.quote }}</p><button class="t-link" :disabled="busy||!citationPreviewRequest(e)" @click="showQuote(e)">{{ citation(e).name }} · {{ citation(e).locator || '查看原文' }}</button></blockquote>
      <div class="t-actions"><button class="t-btn" :disabled="busy" @click="editor=record(item)">编辑条目</button>
        <button v-if="confirmable" class="t-btn" :disabled="busy" @click="beginReview(String(item[sectionKey]))">确认业务口径</button>
        <button v-if="section === 'gaps' && item.status === 'open'" class="t-btn" :disabled="busy" @click="startAnswer(String(item.gap_id))">答复此问题</button>
        <button v-if="section !== 'sources'" class="t-link" :disabled="busy" @click="beginReview(String(item[sectionKey]),true)">移除</button></div>
      <div v-if="section==='gaps' && state.interviews?.[String(item.gap_id)]" class="t-interview">
        <small>已记录 {{state.interviews[String(item.gap_id)].turns}} 轮 · 最近一次 {{new Date(state.interviews[String(item.gap_id)].last_asked_at).toLocaleString('zh-CN')}}</small>
        <p v-if="state.interviews[String(item.gap_id)].paused" class="t-warn">连续重复内容，等待补充新依据。</p>
      </div>
      <form v-if="answerId && answerId === item.gap_id" class="t-box t-form" @submit.prevent="answerGap(false)">
        <label>对本问题的答复<textarea v-model="answerText" required rows="3" placeholder="记录业务依据、适用条件或仍需核对的内容"/></label>
        <div class="t-actions"><button class="t-btn primary" :disabled="busy || answerRevision !== state.revision || !answerText.trim()">记录补充（保留待确认）</button>
          <button type="button" class="t-btn" :disabled="busy || answerRevision !== state.revision || !answerText.trim()" @click="answerGap(true)">确认解决</button>
          <button type="button" class="t-link" @click="answerId=''">取消</button></div>
      </form>
      <form v-if="confirmId === item[sectionKey]" class="t-box t-form" @submit.prevent="review(false)">
        <label>确认依据<textarea v-model="confirmation" required rows="2" placeholder="请明确写出您认可的业务口径"/></label><small>本次确认绑定当前登录人和条目版本。</small>
        <div class="t-actions"><button class="t-btn primary" :disabled="busy || ownerRevision !== state.revision || !confirmation.trim()">签署确认</button><button type="button" class="t-btn" @click="confirmId=''">取消</button></div></form>
      <div v-if="removeId === item[sectionKey]" class="t-box">移除后，引用此条目的规则和流程需要重新核对。<div class="t-actions">
        <button class="t-btn" :disabled="busy || ownerRevision !== state.revision" @click="review(true)">确认移除</button><button class="t-btn" @click="removeId=''">取消</button></div></div>
      </template>
    </details>
    <div v-if="pageCount>1" class="t-actions"><button class="t-btn" :disabled="pageNumber<=1" @click="pageNumber--">上一页</button><small>{{pageNumber}} / {{pageCount}}</small>
      <button class="t-btn" :disabled="pageNumber>=pageCount" @click="pageNumber++">下一页</button></div>
    <aside v-if="preview" class="t-box"><div class="t-section-head"><h3>{{ previewTitle }}</h3><button class="t-link" @click="preview=null">收起原文</button></div><small>原文字符 [{{preview.start}}, {{preview.end}}) / {{preview.total}}</small><pre class="t-source-text">{{ preview.text }}</pre></aside>
    <div class="t-footer t-actions"><button class="t-btn" :disabled="busy" @click="dispatch('finalize',{completion:'draft'})">生成草稿报告</button>
      <button class="t-btn primary" :disabled="busy" @click="dispatch('finalize',{completion:'complete'})">校验并完成台账</button></div>
  </section>
</template>
