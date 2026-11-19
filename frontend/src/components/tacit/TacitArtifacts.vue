<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { label, rows, record, type Dispatch, type RunBatch, type Entry, type TacitState, type TacitReview } from '../../domain/tacit'
import AssistantRichText from '../AssistantRichText.vue'
import DiagramPreview from '../DiagramPreview.vue'
import TacitQuestions from './TacitQuestions.vue'
import TacitCandidates from './TacitCandidates.vue'
import TacitScenarios from './TacitScenarios.vue'
import TacitFields from './TacitFields.vue'
import { api } from '../../api/client'
const props=defineProps<{ projectId:string; state:TacitState; dispatch:Dispatch; runBatch:RunBatch; busy:boolean }>()
const a = computed(()=>props.state.artifacts)
const scenarioPanel = ref<InstanceType<typeof TacitScenarios>|null>(null)
const pendingScenarios = computed(()=>a.value.scenarios
  ? rows(a.value.scenarios.content).filter(row=>row.review_status==='pending').length
  : Number(record(a.value.walkthrough?.content).pending_scenario_count??0))
const reports = {business_report:'业务梳理报告',gap_report:'业务缺口报告'} as const
const activeReport = ref<keyof typeof reports>('business_report')
const comment = ref(''), reviewingFlow = ref(false), reviewRevision = ref(0)
const flowReady = computed(()=>a.value.flows?.status === 'approved')
const candidateReady = computed(()=>a.value.candidates?.status === 'approved')
const historyOpen = ref(false), reviews = ref<TacitReview[]>([]), before = ref<number|null>(null), historyBusy = ref(false), historyError = ref('')
const historyLabels:Record<string,string> = {review_flow:'流程审核',review_candidate:'候选审核',review_candidate_item:'逐项候选审核',review_scenario:'场景审核',review_alignment:'政策口径签署',review_cq:'能力问题审核',confirm_item:'业务口径确认',answer_gap:'专家答复'}
const snapshot = ref<{revision:number;fields:Entry}|null>(null), snapshotBusy = ref(false)
async function openSnapshot(revision:number) {
  snapshotBusy.value=true; historyError.value=''
  try {
    const reply=await api.tacitReviewSnapshot(props.projectId,revision)
    snapshot.value={revision,fields:{'当时的审核对象':reply.target,'当时的来源版本':reply.sources}}
  } catch(e) {historyError.value=e instanceof Error ? e.message : '审核快照读取失败'}
  finally {snapshotBusy.value=false}
}
let sequence = 0
async function history(older=false) {
  const generation = ++sequence
  historyBusy.value=true; historyError.value=''
  try {
    const response = await api.tacitReviewHistory(props.projectId, older && before.value !== null ? before.value : undefined)
    if (generation !== sequence) return
    reviews.value = older ? [...reviews.value,...response.items] : response.items; before.value = response.next_before_revision
  } catch (e) { if(generation===sequence) historyError.value=e instanceof Error ? e.message : '审核记录读取失败' }
  finally { if(generation===sequence) historyBusy.value=false }
}
watch([historyOpen,()=>props.state.revision],()=>{ if(historyOpen.value) void history() })
function allowReview(state?:string) { return ['draft','pending','ready'].includes(state ?? '') }
function startReview() {
  reviewingFlow.value = true; reviewRevision.value=props.state.revision; comment.value=''
}
async function review(decision:'approved'|'rejected') {
  if (await props.dispatch('review_flow',{decision,comment:comment.value.trim()},reviewRevision.value)) reviewingFlow.value=false
}
function omit(item:Entry, keys:string[]) { return Object.fromEntries(Object.entries(item).filter(([key])=>!keys.includes(key))) }
function downloadReport(title:string, content:string) {
  const url=URL.createObjectURL(new Blob([content],{type:'text/markdown;charset=utf-8'}))
  const a=document.createElement('a'); a.href=url; a.download=title+'.md'; a.click()
  setTimeout(()=>URL.revokeObjectURL(url),1000)
}
</script>
<template>
  <section class="t-section">
    <div class="t-section-head"><h2>业务报告</h2><button class="t-btn" :disabled="busy" @click="dispatch('finalize',{completion:'draft'})">生成草稿报告</button></div>
    <template v-if="a.business_report || a.gap_report">
      <nav class="t-nav" aria-label="报告种类"><button v-for="(name,key) in reports" :key="key" :aria-current="key === activeReport ? 'page' : undefined" @click="activeReport=key">{{ name }}</button></nav>
      <template v-if="a[activeReport]"><small>{{ label(a[activeReport]?.status) }}</small>
        <AssistantRichText v-if="typeof a[activeReport]?.content === 'string'" :content="String(a[activeReport]?.content)" />
        <button class="t-link" @click="downloadReport(reports[activeReport],String(a[activeReport]?.content))">下载 Markdown</button></template>
    </template><p v-else class="t-muted">保存台账后可生成业务报告和缺口报告。</p>
  </section>
  <section class="t-section">
    <div class="t-section-head"><h2>业务流程 <small>{{ label(a.flows?.status) }}</small></h2><button class="t-btn" :disabled="busy" @click="dispatch('generate_flow')">生成流程图</button></div>
    <template v-for="flow in rows(a.flows?.content)" :key="String(flow.process_id)">
      <h3>{{ flow.name }}</h3><p v-if="Array.isArray(flow.gap_ids) && flow.gap_ids.length" class="t-warn">关联待核对事项 · {{ flow.gap_ids.join('、') }}</p>
      <DiagramPreview v-if="typeof flow.content === 'string'" kind="mermaid" :source="flow.content" />
      <details><summary>流程输入、输出与步骤</summary><TacitFields :fields="omit(flow,['name','content','process_id','status'])"/></details>
    </template>
    <p v-if="a.flows?.status === 'stale'" class="t-warn">上游依据已更新，请重新生成并审核流程图。</p>
    <button v-if="allowReview(a.flows?.status)" class="t-btn" :disabled="busy" @click="startReview()">审核当前流程</button>
    <p v-else-if="!a.flows" class="t-muted">生成后可逐项核对流程步骤、分支和待确认事项。</p>
    <div v-if="reviewingFlow" class="t-box t-form"><label>流程审核意见<textarea v-model="comment" rows="2" placeholder="请记录审核口径或需修改之处"/></label>
      <div class="t-actions"><button class="t-btn primary" :disabled="busy || state.revision !== reviewRevision" @click="review('approved')">通过此版流程</button>
        <button class="t-btn" :disabled="busy || state.revision !== reviewRevision || !comment.trim()" @click="review('rejected')">退回修改</button><button class="t-link" @click="reviewingFlow=false">取消</button></div></div>
  </section>
  <TacitCandidates v-bind="{state,dispatch,busy}"/>
  <TacitQuestions v-bind="{state,dispatch,busy,runBatch}"/>
  <TacitScenarios ref="scenarioPanel" v-bind="{state,dispatch,busy}"/>
  <section class="t-section"><div class="t-section-head"><h2>场景校核 <small>{{ label(a.walkthrough?.status) }}</small></h2><button class="t-btn" title="校核现有路径并提议待补充的场景" :disabled="busy || !flowReady || !candidateReady" @click="dispatch('validate_ontology')">按路径校核</button></div>
    <p class="t-muted" v-if="!a.walkthrough && !candidateReady">按验收场景核对流程路径，查看分支偏离和证据缺口。</p>
    <template v-if="a.walkthrough"><button v-if="pendingScenarios && a.scenarios?.status!=='stale'" class="t-link" @click="scenarioPanel?.focusPending()">核对 {{pendingScenarios}} 个待审核场景</button>
      <details v-if="Array.isArray(record(a.walkthrough.content).scenario_diagnostics) && (record(a.walkthrough.content).scenario_diagnostics as unknown[]).length"><summary>场景提议说明</summary><TacitFields :fields="{scenario_diagnostics:record(a.walkthrough.content).scenario_diagnostics}"/></details><p class="t-muted">路径通过表示输入满足已审核模型的条件。预期输出来自验收场景，实际业务结果需与执行记录核对。</p>
      <AssistantRichText v-if="typeof record(a.walkthrough.content).modeling_gap_report === 'string'" :content="String(record(a.walkthrough.content).modeling_gap_report)" />
      <details v-for="p in rows(record(a.walkthrough.content).processes)" class="t-detail" :key="String(p.process_id)"><summary>{{ p.name }} · 路径与场景</summary>
        <TacitFields :fields="omit(p,['name'])"/></details>
    </template>
  </section>
  <section class="t-section"><div class="t-section-head"><h2>审核记录</h2><button class="t-btn" :aria-expanded="historyOpen" @click="historyOpen=!historyOpen">{{historyOpen ? '收起' : '查看记录'}}</button></div>
    <div v-if="historyOpen"><p v-if="historyError" class="t-error" role="alert">{{ historyError }} <button class="t-link" @click="history()">重试</button></p>
      <ol class="t-list"><li v-for="r in reviews" :key="r.revision"><div><strong>{{historyLabels[r.action] || r.action}} · {{label(r.decision)}}</strong>
        <small>{{r.actor}} · {{new Date(r.created_at).toLocaleString('zh-CN')}} · 第 {{r.revision}} 版</small><p v-if="r.comment">{{r.comment}}</p>
        <details><summary>审核对象和版本指纹</summary><p>{{r.target_id}} · {{r.target_digest}}</p><button v-if="snapshot?.revision!==r.revision" class="t-link" :disabled="snapshotBusy" @click="openSnapshot(r.revision)">查看签署时的快照</button>
          <TacitFields v-else :fields="snapshot.fields"/></details></div></li></ol>
      <p v-if="!historyBusy && !historyError && !reviews.length" class="t-muted">还没有人工确认或审核记录。</p><p v-if="historyBusy">正在读取审核记录…</p>
      <button v-if="before !== null" class="t-link" :disabled="historyBusy" @click="history(true)">查看更早审核</button></div>
  </section>
</template>
