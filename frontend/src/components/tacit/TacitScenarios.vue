<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { label, rows, type Dispatch, type Entry, type TacitState } from '../../domain/tacit'
import TacitFields from './TacitFields.vue'
import TacitCitation from './TacitCitation.vue'
import TacitResultPage from './TacitResultPage.vue'
const props=defineProps<{state:TacitState;dispatch:Dispatch;busy:boolean}>()
const panel=ref<HTMLElement|null>(null)
const pager=ref<InstanceType<typeof TacitResultPage>|null>(null)
async function focusPending() {
  const index=rows(artifact.value?.content).findIndex(row=>row.review_status==='pending')
  if(index>=0) {
    pager.value?.goToIndex(index)
    await nextTick()
  }
  const row=panel.value?.querySelector<HTMLDetailsElement>('details[data-pending="true"]')
  if (row) {
    row.open=true
    row.querySelector<HTMLButtonElement>('.t-scenario-review')?.focus()
  } else panel.value?.focus()
}
defineExpose({focusPending})
const artifact=computed(()=>props.state.artifacts.scenarios)
const ready=computed(()=>props.state.artifacts.flows?.status==='approved')
const active=ref<Entry|null>(null), revision=ref(0), inputs=ref(''), expected=ref(''), comment=ref(''), error=ref('')
function edit(row:Entry) {
  active.value=row;revision.value=props.state.revision;inputs.value=JSON.stringify(row.inputs??{},null,2)
  expected.value=JSON.stringify(row.expected_outputs??{},null,2);comment.value='';error.value=''
}
function objectInput(text:string,field:string):Entry {
  try {
    const v=JSON.parse(text)
    if(v && typeof v==='object' && !Array.isArray(v)) return v
  } catch { /* Report the field name in the form. */ }
  throw new Error(field+'必须是有效的 JSON 对象。')
}
async function review(decision:string) {
  error.value=''
  const payload:Entry={scenario_id:active.value?.scenario_id,decision,comment:comment.value.trim()}
  if(decision==='approved') {
    try { payload.inputs=objectInput(inputs.value,'场景输入');payload.expected_outputs=objectInput(expected.value,'期望输出') } catch(e) { error.value=(e as Error).message;return }
  }
  if(await props.dispatch('review_scenario',payload,revision.value)) active.value=null
}
</script>
<template>
  <section ref="panel" tabindex="-1" class="t-section"><div class="t-section-head"><h2>场景提议 <small>{{label(artifact?.status)}}</small></h2>
    <button class="t-btn" :disabled="busy||!ready" @click="dispatch('propose_scenarios')">生成待审核场景</button></div>
    <p v-if="!ready" class="t-muted">通过流程审核后，可根据分支条件生成正常、异常与边界场景。</p>
    <p v-if="artifact" class="t-muted">路径推演检查分支是否可达。业务结果需用原文或专家确认的预期值另行核对。</p>
    <ul v-if="artifact?.diagnostics?.length" class="t-warn"><li v-for="(d,i) in artifact.diagnostics" :key="i">{{d}}</li></ul>
    <p v-if="artifact && !rows(artifact.content).length" class="t-muted">本版没有待补充的场景提议。</p>
    <TacitResultPage ref="pager" :items="rows(artifact?.content)" v-slot="{items}"><details v-for="row in items" :key="String(row.scenario_id)" class="t-detail" :data-pending="row.review_status==='pending'">
      <summary>{{row.name}} <small>{{label(row.kind)}} · {{label(row.review_status)}}</small></summary>
      <TacitFields :fields="Object.fromEntries(Object.entries(row).filter(([k])=>!['name','kind','review_status','evidence'].includes(k)))"/>
      <TacitCitation v-for="(e,i) in rows(row.evidence)" :key="i" :citation="e" v-bind="{state,dispatch,busy}"/>
      <button v-if="artifact?.status!=='stale' && !row.adopted" class="t-btn t-scenario-review" :disabled="busy||!ready" @click="edit(row)">核对场景与期望值</button>
      <p v-if="row.adopted" class="t-muted">此项已写入业务台账，可在验收场景中维护。</p>
    </details></TacitResultPage>
    <div v-if="active" class="t-box t-form"><h3>{{active.name}} · 人工审核</h3>
      <TacitFields :fields="{path_id:active.path_id,steps:active.steps,assumptions:active.assumptions,input_fields:active.input_fields,output_fields:active.output_fields}"/>
      <label>场景输入（JSON 对象）<textarea v-model="inputs" rows="4" spellcheck="false"/></label>
      <label>期望输出（JSON 对象）<textarea v-model="expected" rows="4" spellcheck="false"/></label>
      <label>场景审核依据<textarea v-model="comment" rows="2" placeholder="记录来源、适用条件或退回原因"/></label>
      <p v-if="state.spec.completion!=='complete'" class="t-muted">签署通过后，此场景将写入草稿台账的验收场景。</p>
      <p v-if="revision!==state.revision" class="t-warn">台账已更新，请重新打开场景。</p><p v-if="error" role="alert" class="t-error">{{error}}</p>
      <div class="t-actions"><button class="t-btn primary" :disabled="busy||revision!==state.revision" @click="review('approved')">签署场景与期望值</button>
        <button class="t-btn" :disabled="busy||revision!==state.revision||!comment.trim()" @click="review('rejected')">退回场景</button><button class="t-link" @click="active=null">取消</button></div></div>
  </section>
</template>
