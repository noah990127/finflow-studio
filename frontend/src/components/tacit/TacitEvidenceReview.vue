<script setup lang="ts">
import { computed, ref } from 'vue'
import { label, record, rows, type Dispatch, type Entry, type TacitState } from '../../domain/tacit'
import TacitCitation from './TacitCitation.vue'
import TacitFields from './TacitFields.vue'
import TacitResultPage from './TacitResultPage.vue'
const props=defineProps<{state:TacitState;dispatch:Dispatch;busy:boolean}>()
const sections:Record<string,string>={all:'全部范围',assets:'项目资料',cqs:'能力问题',terminology:'术语',ontology:'本体',contract:'契约',decisions:'业务决策'}
const query=ref(''),section=ref('all'), offset=ref(0), last=ref({query:'',section:'all',revision:0})
const result=ref<Entry|null>(null)
async function search(position=0,fresh=true) {
  const request=fresh?{query:query.value.trim(),section:section.value}:last.value
  if(!request.query)return
  const page=await props.dispatch('search_context',{query:request.query,section:request.section,offset:position,limit:20},fresh?undefined:last.value.revision)
  if(page){result.value=page;offset.value=position;last.value={query:request.query,section:request.section,revision:props.state.revision}}
}
const artifact=computed(()=>props.state.artifacts.alignments)
const batch=ref<Entry|null>(null),active=ref<Entry|null>(null),vote=ref('distinct'),comment=ref(''),merged=ref(''),error=ref(''),revision=ref(0)
const nextPair=computed(()=>artifact.value?.next_offset ?? batch.value?.next_offset)
async function align(next=false) {
  const page=await props.dispatch('align_policies',next && typeof nextPair.value==='number'?{offset:nextPair.value}:{})
  if(page){batch.value=page;active.value=null}
}
function rule(id:unknown){ return props.state.spec.rules?.find(r=>r.rule_id===id) }
function hasRuleDetails(item:Entry) {
  return item.status==='pending' || (!!item.from_rule && !!item.to_rule)
}
function compare(item:Entry,side:string) {
  const id=item[side+'_id']
  const snap=record(item[side+'_rule'])
  const row=Object.keys(snap).length?snap:rule(id)
  if(!row)return {rule_id:id}
  return Object.fromEntries(Object.entries(row).filter(([k])=>!['rule_id','name','evidence'].includes(k)))
}
function review(item:Entry){
  active.value=item;vote.value='distinct';comment.value='';revision.value=props.state.revision;error.value=''
  merged.value=JSON.stringify(rule(item.to_id)??{},null,2)
}
async function sign(){
  const item=active.value
  if(!item)return
  const payload:Entry={alignment_id:item.alignment_id,decision:vote.value,comment:comment.value.trim()}
  if(vote.value==='merge' && item.requires_reconciliation) {
    try {
      const draft=JSON.parse(merged.value)
      if(!draft || typeof draft!=='object' || Array.isArray(draft) || draft.rule_id!==item.to_id) throw new Error()
      payload.merged_rule=draft
    } catch {error.value='请提供完整的目标规则 JSON 对象，并保留原目标规则编号。';return}
  }
  if(await props.dispatch('review_alignment',payload,revision.value)){active.value=null;batch.value=null}
}
</script>
<template>
  <section class="t-section"><div class="t-section-head"><h2>证据检索</h2></div>
    <form class="t-filter" @submit.prevent="search()"><label class="t-grow">查找业务依据<input v-model="query" required maxlength="500" placeholder="输入术语、条款或业务问题"></label>
      <label>检索范围<select v-model="section"><option v-for="(v,k) in sections" :key="k" :value="k">{{v}}</option></select></label>
      <button class="t-btn primary" :disabled="busy||!query.trim()">检索</button></form>
    <template v-if="result"><p class="t-muted" aria-live="polite">「{{last.query}}」 · {{sections[last.section]}} · {{result.total}} 条匹配</p>
      <p v-if="last.revision!==state.revision" class="t-warn">这些结果来自第 {{last.revision}} 版，重新检索可核对当前台账。</p>
      <p v-if="record(result.providers).lightrag==='unavailable'" class="t-warn">图谱服务暂不可用，以下为项目内可核对的结果。</p>
      <details v-for="hit in rows(result.items)" :key="JSON.stringify([hit.section,hit.id,hit.source_id,hit.start,hit.end])" class="t-detail">
        <summary>{{hit.title}} <small>{{sections[String(hit.section)]}} · {{hit.source_title || hit.source_id}} · 版本 {{hit.version}}</small></summary>
        <p v-if="hit.summary && hit.summary!==hit.text">{{hit.summary}}</p>
        <TacitCitation :citation="hit" v-bind="{state,dispatch,busy}"/>
      </details>
      <div v-if="Number(result.total)>20" class="t-actions t-pager"><button class="t-btn" :disabled="busy||!offset||last.revision!==state.revision" @click="search(Math.max(0,offset-20),false)">上一页结果</button>
        <small>{{offset+1}}–{{offset+rows(result.items).length}} / {{result.total}}</small>
        <button class="t-btn" :disabled="busy||typeof result.next_offset!=='number'||last.revision!==state.revision" @click="search(Number(result.next_offset),false)">下一页结果</button></div></template>
  </section>
  <section class="t-section"><div class="t-section-head"><h2>政策口径对齐 <small>{{label(artifact?.status)}}</small></h2><button class="t-btn" :disabled="busy" @click="align()">比对规则</button></div>
    <p v-if="!artifact" class="t-muted">比对当前业务规则的适用条件、结果和来源，并签署处理决定。</p>
    <p v-if="artifact?.status==='stale'" class="t-warn">规则或材料已更新，请重新比对后处理。</p>
    <p v-if="artifact && !rows(artifact.content).length" class="t-muted">已检查的规则对中没有需要处理的口径。</p>
    <TacitResultPage :items="rows(artifact?.content)" unit="组规则" v-slot="{items}"><details v-for="item in items" class="t-detail" :key="String(item.alignment_id)">
      <summary>{{item.from_name || item.from_id}} / {{item.to_name || item.to_id}} <small>{{label(item.kind)}} · {{label(item.status)}}</small></summary>
      <p>{{item.reason}}</p><p v-if="Array.isArray(item.different_fields) && item.different_fields.length" class="t-muted">差异字段 · {{item.different_fields.map(label).join('、')}}</p><div class="t-compare" v-if="hasRuleDetails(item)"><article v-for="side in ['from','to']" :key="side"><h3>{{side==='from'?'来源规则':'目标规则'}} · {{item[side+'_name'] || item[side+'_id']}}</h3>
        <TacitFields :fields="compare(item,side)"/>
        <TacitCitation v-for="(e,i) in rows(item.evidence).filter(e=>e.side===side || e.side===item[side+'_id'])" :key="i" :citation="e" v-bind="{state,dispatch,busy}"/></article></div>
      <TacitCitation v-for="(e,i) in rows(item.evidence).filter(e=>!hasRuleDetails(item) || !['from','to',item.from_id,item.to_id].includes(String(e.side)))" :key="i" :citation="e" v-bind="{state,dispatch,busy}"/>
      <TacitFields :fields="{review:item.review,comment:item.comment,reviewed_by:item.reviewed_by,reviewed_at:item.reviewed_at,gap_id:item.gap_id}"/>
      <button v-if="item.status==='pending' && artifact?.status!=='stale'" class="t-btn" :disabled="busy" @click="review(item)">处理这组口径</button>
    </details></TacitResultPage>
    <div v-if="typeof nextPair==='number' && artifact?.status!=='stale'" class="t-actions t-pager"><small>已扫描 {{nextPair}} / {{artifact?.total_pairs ?? batch?.total_pairs}} 组规则</small><button class="t-btn" :disabled="busy" @click="align(true)">继续比对</button></div>
    <form v-if="active" class="t-box t-form" @submit.prevent="sign"><h3>{{active.from_name}} / {{active.to_name}}</h3>
      <label>处理决定<select v-model="vote"><option value="distinct">分别保留</option><option value="conflict">标记冲突</option><option value="merge">合并到目标规则</option></select></label>
      <template v-if="vote==='merge'"><p class="t-warn">签署合并将更新目标规则及相关引用。</p>
        <label v-if="active.requires_reconciliation">合并后的完整目标规则（JSON 对象）<textarea v-model="merged" rows="10" spellcheck="false" required/></label></template>
      <label>决定依据<textarea v-model="comment" rows="2" required placeholder="说明适用范围、两份材料的关系和采用此决定的依据"/></label>
      <p v-if="revision!==state.revision" class="t-warn">台账版本已变化，请重新打开此规则对核对后签署。</p><p v-if="error" class="t-error" role="alert">{{error}}</p>
      <div class="t-actions"><button class="t-btn primary" :disabled="busy||!comment.trim()||revision!==state.revision">签署对齐决定</button><button type="button" class="t-link" @click="active=null">取消</button></div>
    </form>
  </section>
</template>
