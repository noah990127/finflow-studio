<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { label, rows, record, type Dispatch, type RunBatch, type Entry, type TacitState } from '../../domain/tacit'
import TacitFields from './TacitFields.vue'
import TacitCitation from './TacitCitation.vue'
import TacitResultPage from './TacitResultPage.vue'
const props=defineProps<{state:TacitState;dispatch:Dispatch;runBatch:RunBatch;busy:boolean}>()
const artifact=computed(()=>props.state.artifacts.cqs)
const batchProgress=ref<Entry|null>(null)
const progress=computed(()=>batchProgress.value ?? artifact.value?.progress)
const restart=ref(false), revision=ref(0)
const running=ref(false), stop=ref(false), local=ref('')
let alive=true
onBeforeUnmount(()=>{alive=false; stop.value=true})
function confirmRestart(){revision.value=props.state.revision;restart.value=true}
async function generate(auto:boolean,reset=false){
  restart.value=false;local.value='';stop.value=false;running.value=true
  let previous=reset ? 0 : Number(progress.value?.processed_chunks??0)
  try {
    await props.runBatch(async send=>{
      do {
        const result=await send('generate_cqs',reset?{restart:true}:{},reset?revision.value:undefined)
        reset=false
        if(!result||!alive) break
        if(result.already_applied) {local.value='本页已提交，已重新读取保存的进度。';break}
        const p=typeof result.processed_chunks==='number' ? result : props.state.artifacts.cqs?.progress
        if(p) batchProgress.value=p
        if(!p || p.done===true) break
        const processed=Number(p.processed_chunks)
        if(!Number.isFinite(processed) || processed<=previous){local.value='本页未推进生成进度，已暂停。请查看来源提示。';break}
        previous=processed
      } while(auto && !stop.value && alive)
    })
  } finally {batchProgress.value=null;running.value=false}
}
</script>
<template>
  <section class="t-section"><div class="t-section-head"><h2>能力问题 <small>{{label(artifact?.status)}}</small></h2><div class="t-actions">
    <button v-if="!running && (!progress?.done || artifact?.status==='stale')" class="t-btn" :disabled="busy" @click="artifact?.status==='stale' ? confirmRestart() : generate(true)">{{progress && artifact?.status!=='stale'?'继续生成':'生成问题'}}</button>
    <button v-if="running" class="t-btn" :disabled="stop" @click="stop=true">{{stop?'正在保存本页…':'本页完成后暂停'}}</button>
    <button v-if="artifact && !running && artifact.status!=='stale'" class="t-link" :disabled="busy" @click="confirmRestart">重新生成</button></div></div>
    <div v-if="restart" class="t-box"><p>重新分析原文后，范围、证据或问题含义发生变化的条目需要重新审核。</p><div class="t-actions"><button class="t-btn" :disabled="busy||revision!==state.revision" @click="generate(true,true)">确认重建问题</button><button class="t-link" @click="restart=false">取消</button></div></div>
    <div v-if="progress" class="t-progress" aria-live="polite"><span>{{progress.done?'分块处理完成':'分块处理中'}} · {{progress.processed_chunks}} / {{progress.total_chunks}} 块 · {{progress.generated_questions}} 个问题</span>
      <progress v-if="Number(progress.total_chunks)>0" :value="Number(progress.processed_chunks)" :max="Number(progress.total_chunks)" aria-label="资料分块生成进度"/>
      <small v-if="Number(progress.fallback_chunks)>0">{{progress.fallback_chunks}} 块使用回退策略</small>
      <TacitFields :fields="{fallback_reasons:progress.fallback_reasons,limited_sources:progress.limited_sources,source_warnings:progress.source_warnings}"/></div>
    <p v-if="local" role="alert" class="t-warn">{{local}}</p>
    <TacitResultPage :items="rows(artifact?.content)" unit="个问题" v-slot="{items}"><details class="t-detail" v-for="cq in items" :key="String(cq.cq_id)">
      <summary>{{cq.cq_text}} <small>{{label(cq.review_status)}}</small></summary>
      <TacitFields :fields="Object.fromEntries(Object.entries(cq).filter(([k])=>!['cq_text','review_status','source','scope_stamp'].includes(k)))"/>
      <TacitFields :fields="Object.fromEntries(Object.entries(record(cq.source)).filter(([k])=>k!=='evidence'))"/>
      <TacitCitation v-for="(e,i) in rows(record(cq.source).evidence)" :key="i" :citation="e" v-bind="{state,dispatch,busy}"/>
      <div class="t-actions"><button class="t-btn" :disabled="busy||running||artifact?.status==='stale'||cq.review_status==='accepted'" @click="dispatch('review_cq',{cq_id:cq.cq_id,decision:'accepted'})">采纳问题</button>
        <button class="t-btn" :disabled="busy||running||artifact?.status==='stale'||cq.review_status==='rejected'" @click="dispatch('review_cq',{cq_id:cq.cq_id,decision:'rejected'})">驳回问题</button></div>
    </details></TacitResultPage>
  </section>
</template>
