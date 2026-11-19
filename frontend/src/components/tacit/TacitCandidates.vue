<script setup lang="ts">
import { computed, ref } from 'vue'
import { label, rows, type Dispatch, type Entry, type TacitState } from '../../domain/tacit'
import TacitResultPage from './TacitResultPage.vue'
import TacitFields from './TacitFields.vue'
import TacitCitation from './TacitCitation.vue'
const props=defineProps<{ state:TacitState; dispatch:Dispatch; busy:boolean }>()
const artifact = computed(()=>props.state.artifacts.candidates)
const canGenerate = computed(()=>props.state.artifacts.flows?.status==='approved' && props.state.spec.completion==='complete' && !props.state.issues.length)
const current = computed(()=>canGenerate.value && !!artifact.value && artifact.value.status!=='stale')
const items = computed(()=>rows(artifact.value?.content))
const action=ref(''), id=ref(''), revision=ref(0), message=ref(''), comment=ref('')
const name=ref(''), kind=ref('Object'), processId=ref(''), key=ref(''), definition=ref('{}'), evidence=ref<Entry[]>([])
const check=ref<{valid:boolean;issues:Entry[];revision:number}|null>(null)
const selected=ref(''), stale= computed(()=>revision.value!==props.state.revision)
const clauses=computed(()=>(props.state.spec.sources??[]).flatMap(s=>rows(s.clauses).filter(c=>c.quote).map(c=>({
  token:JSON.stringify([s.source_id,c.clause_id]), display:String(s.title)+' · '+String(c.locator??'')+' · '+String(c.quote).slice(0,75),
  evidence:{source_id:s.source_id,clause_id:c.clause_id,quote:c.quote}
}))))
function begin(mode:string,item:Entry={}) {
  action.value=mode; id.value=String(item.candidate_id??''); revision.value=props.state.revision
  message.value=''; comment.value=''; selected.value=''; name.value=String(item.name??''); kind.value=String(item.kind??'Object')
  processId.value=String(item.process_id??''); key.value=String(item.key??''); definition.value=JSON.stringify(item.definition??{},null,2)
  evidence.value=rows(item.evidence).map(e=>({...e}))
}
function attach() {
  const e=clauses.value.find(c=>c.token===selected.value)?.evidence
  if(e && !evidence.value.some(r=>r.source_id===e.source_id && r.clause_id===e.clause_id)) evidence.value.push(e)
  selected.value=''
}
async function saveEdit() {
  let value:unknown
  try { value=JSON.parse(definition.value) } catch { message.value='候选定义必须是有效的 JSON 对象。'; return }
  if(!value || typeof value!=='object' || Array.isArray(value)) { message.value='候选定义必须是 JSON 对象。';return }
  const fields={name:name.value.trim(),definition:value,evidence:evidence.value}
  const payload=action.value==='add' ? {extensions:[{...fields,kind:kind.value,process_id:processId.value,key:key.value.trim()}]} : {patches:[{...fields,candidate_id:id.value}]}
  if(await props.dispatch('edit_candidates',payload,revision.value)) action.value=''
}
async function validate() {
  const result=await props.dispatch('check_candidates')
  if(result) check.value={valid:result.valid===true,issues:rows(result.issues),revision:props.state.revision}
}
async function operate(op:string,payload:Entry={}) {
  if(await props.dispatch(op,payload,revision.value)) action.value=''
}
</script>
<template>
  <section class="t-section">
    <div class="t-section-head"><h2>本体候选 <small>{{label(artifact?.status)}}<template v-if="artifact"> · {{items.length}} 项</template></small></h2>
      <button class="t-btn" :disabled="busy || !canGenerate" @click="artifact ? begin('rebuild') : dispatch('build_candidate')">{{artifact ? '重新生成候选' : '生成候选'}}</button></div>
    <p v-if="!canGenerate" class="t-muted">完成台账校验并通过当前版本的流程审核后，可生成本体候选。</p>
    <div v-if="action==='rebuild'" class="t-box"><p>重新生成将以当前流程替换候选集合及其审核状态。</p><div class="t-actions"><button class="t-btn" :disabled="busy||stale" @click="operate('build_candidate')">确认重新生成</button><button class="t-link" @click="action=''">取消</button></div></div>
    <TacitResultPage :items="items" v-slot="{items: pageItems}"><details class="t-detail" v-for="item in pageItems" :key="String(item.candidate_id)">
      <summary>{{item.name}} <small>{{label(item.kind)}} · {{label(item.review_status||'pending')}}</small></summary>
      <TacitFields :fields="Object.fromEntries(Object.entries(item).filter(([k])=>!['name','kind','evidence','review_status'].includes(k)))"/>
      <TacitCitation v-for="(e,i) in rows(item.evidence)" :key="i" :citation="e" v-bind="{state,dispatch,busy}"/>
      <div class="t-actions" v-if="current"><button class="t-btn" :disabled="busy" @click="begin('edit',item)">编辑候选</button>
        <button v-if="['pending','approved','rejected'].includes(String(artifact?.status))" class="t-btn" :disabled="busy" @click="begin('review',item)">审核此项</button>
        <button class="t-link" :disabled="busy" @click="begin('remove',item)">删除候选</button></div>
    </details></TacitResultPage>
    <div v-if="current" class="t-actions t-footer"><button class="t-btn" :disabled="busy" @click="begin('add')">新增扩展候选</button>
      <button class="t-btn" :disabled="busy" @click="validate">检查候选结构</button>
      <button v-if="artifact?.status==='draft'" class="t-btn primary" :disabled="busy" @click="dispatch('submit_candidates')">提交逐项审核</button><button v-if="artifact?.status==='pending'" class="t-link" :disabled="busy" @click="begin('bulk')">审核候选集合</button></div>
    <div v-if="check && check.revision===state.revision" class="t-box" role="status"><strong>{{check.valid?'候选结构检查通过':'候选结构需要修改'}}</strong>
      <ul v-if="check.issues.length"><li v-for="(issue,i) in check.issues" :key="i">{{issue.message}} <small>{{issue.path}}</small></li></ul></div>
    <form v-if="action==='edit'||action==='add'" class="t-box t-form" @submit.prevent="saveEdit">
      <h3>{{action==='add'?'新增扩展候选':'编辑候选 · '+id}}</h3>
      <label>候选名称<input v-model="name" required></label>
      <template v-if="action==='add'"><label>候选种类<select v-model="kind"><option v-for="k in ['Object','Table','Action','Function','Logic']" :key="k" :value="k">{{label(k)}}</option></select></label>
        <label>所属流程<select v-model="processId"><option value="">跨流程共用</option><option v-for="p in state.spec.processes?.filter(p=>p.disposition!=='excluded')" :key="String(p.process_id)" :value="p.process_id">{{p.name || p.process_id}}</option></select></label>
        <label>来源条目键<input v-model="key" required placeholder="能够在流程中唯一识别此候选的键"></label></template>
      <label>候选定义（JSON 对象）<textarea v-model="definition" rows="6" spellcheck="false" required/></label>
      <fieldset><legend>材料依据</legend><div v-for="(e,i) in evidence" :key="i" class="t-evidence"><TacitCitation :citation="e" v-bind="{state,dispatch,busy}"/><button type="button" class="t-link" @click="evidence.splice(i,1)">移除此依据</button></div>
        <label>引用台账条款<select v-model="selected"><option value="">选择材料依据</option><option v-for="c in clauses" :key="c.token" :value="c.token">{{c.display}}</option></select></label>
        <button type="button" class="t-link" :disabled="!selected" @click="attach">添加此依据</button></fieldset>
      <p v-if="message" role="alert" class="t-error">{{message}}</p>
      <div class="t-actions"><button class="t-btn primary" :disabled="busy || stale || !name.trim() || !evidence.length">保存候选</button><button type="button" class="t-link" @click="action=''">取消</button></div>
    </form>
    <div v-if="action==='remove'" class="t-box"><p>删除「{{name}}」后，相关引用需重新检查。</p><div class="t-actions"><button class="t-btn" :disabled="busy||stale" @click="operate('edit_candidates',{remove_ids:[id]})">确认删除候选</button><button class="t-link" @click="action=''">取消</button></div></div>
    <div v-if="action==='bulk'" class="t-box t-form"><label>候选集合审核意见<textarea v-model="comment" rows="2"/></label><p class="t-muted">此签名适用于当前集合中的全部 {{items.length}} 项候选。</p><div class="t-actions">
      <button class="t-btn primary" :disabled="busy||stale" @click="operate('review_candidate',{decision:'approved',comment:comment.trim()})">通过此版候选</button>
      <button class="t-btn" :disabled="busy||stale||!comment.trim()" @click="operate('review_candidate',{decision:'rejected',comment:comment.trim()})">退回此版候选</button><button class="t-link" @click="action=''">取消</button></div></div>
    <div v-if="action==='review'" class="t-box t-form"><h3>审核候选 · {{name}}</h3><label>逐项审核意见<textarea v-model="comment" rows="2"/></label><div class="t-actions">
      <button class="t-btn primary" :disabled="busy||stale" @click="operate('review_candidate_item',{candidate_id:id,decision:'approved',comment:comment.trim()})">签署通过此项</button>
      <button class="t-btn" :disabled="busy||stale||!comment.trim()" @click="operate('review_candidate_item',{candidate_id:id,decision:'rejected',comment:comment.trim()})">退回此项</button><button class="t-link" @click="action=''">取消</button></div></div>
    <p v-if="action && stale" class="t-warn">台账版本已更新，请重新打开此操作并核对最新内容。</p>
  </section>
</template>
