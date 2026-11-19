<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { RotateCw } from 'lucide-vue-next'
import { api, type WorkspaceResource } from '../api/client'
import { compactReads, label, newRequestId, type Dispatch, type RunBatch, type Entry, type TacitCommand, type TacitState } from '../domain/tacit'
import TacitEvidenceReview from '../components/tacit/TacitEvidenceReview.vue'
import TacitSources from '../components/tacit/TacitSources.vue'
import TacitLedger from '../components/tacit/TacitLedger.vue'
import TacitOntology from '../components/tacit/TacitOntology.vue'
import TacitArtifacts from '../components/tacit/TacitArtifacts.vue'

const props = defineProps<{ projectId: string; resources: WorkspaceResource[] }>()
defineEmits<{ upload: [] }>()
const views = ['来源与范围', '业务台账', '成果与审核', '检索与对齐', '本体模型'] as const
const view = ref<typeof views[number]>('来源与范围')
const state = shallowRef<TacitState | null>(null)
const busy = ref(false), error = ref(''), feedback = ref(''), extracting = ref(false)
const batching = ref(false)
const locked = computed(() => busy.value || batching.value)
type BatchCursor = { revision:number; needsRefresh:boolean }
let active = true
// Keep the exact request for a retry after a lost response.
let retry: { key: string; command: TacitCommand } | null = null
function install(value: TacitState) {
  state.value = { ...value, source_ids: value.source_ids ?? [], catalog: value.catalog ?? [],
    spec: value.spec ?? {}, issues: value.issues ?? [], artifacts: value.artifacts ?? {} }
}
async function loadWorkspace() {
  const fresh = await api.tacitWorkspace(props.projectId)
  if (active) install(fresh)
}
async function refresh() {
  if (locked.value || extracting.value) return
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await loadWorkspace()
  } catch (e) { if (active) error.value = e instanceof Error ? e.message : '加载失败' }
  finally { if (active) busy.value = false }
}
async function send(action:string,payload:Entry,expectedRevision:number|undefined,batch?:BatchCursor) {
  if (!active || busy.value || (!batch && batching.value) || expectedRevision === undefined) return null
  busy.value = true
  error.value = ''
  feedback.value = ''
  const compact = !!batch || compactReads.has(action)
  const key = JSON.stringify([action,payload,expectedRevision,compact])
  const command = retry?.key === key ? retry.command : {
    action,payload,expected_revision:expectedRevision,request_id:newRequestId(),...(compact ? {compact:true} : {})
  }
  try {
    const reply = await api.tacitCommand(props.projectId,command)
    if (!active) return null
    const snapshot = reply.workspace
    if (batch) batch.revision = snapshot.revision
    if ('spec' in snapshot) {
      install(snapshot)
      if (batch) batch.needsRefresh = false
    } else if (batch) {
      batch.needsRefresh = true
    } else if (state.value?.revision !== snapshot.revision) {
      await loadWorkspace()
      if (state.value?.revision !== snapshot.revision) {
        error.value = '资料在读取期间更新，请重新核对当前版本。'
        return null
      }
    }
    retry = null
    if (!compact && !['read_source','extract'].includes(action)) feedback.value = '已保存当前操作'
    return reply.result ?? {}
  } catch (e) {
    const status = (e as { status?: number })?.status
    if (!active) return null
    retry = !status || status >= 500 ? {key,command} : null
    error.value = e instanceof Error ? e.message : '操作失败'
    if (batch) batch.needsRefresh = true
    else if (status === 409) {
      try {
        await loadWorkspace()
        error.value += '。已载入最新版本，请重新核对后操作。'
      } catch { error.value += '。请刷新查看最新版本。' }
    }
    return null
  } finally { if (active) busy.value = false }
}
const dispatch: Dispatch = (action,payload={},revision=state.value?.revision) => send(action,payload,revision)
const runBatch: RunBatch = async run => {
  if (!active || locked.value || !state.value) return
  batching.value = true
  const cursor = {revision:state.value.revision,needsRefresh:false}
  try {
    await run((action,payload={},revision=cursor.revision) => send(action,payload,revision,cursor))
  } finally {
    try {
      if (active && cursor.needsRefresh) await loadWorkspace()
    } catch (e) {
      error.value += (error.value ? '。' : '') + '请刷新查看已保存的分页进度。' + (e instanceof Error ? ' '+e.message : '')
    } finally { batching.value = false }
  }
}
onMounted(refresh)
onBeforeUnmount(() => { active = false })
</script>
<template>
  <article class="tacit-panel" aria-label="业务知识梳理">
    <header class="tacit-head"><div><h1>业务知识梳理</h1><p v-if="state">版本 {{ state.revision }} · {{ label(state.spec.completion || 'draft') }}</p></div>
      <button type="button" class="icon-button" title="刷新知识台账" aria-label="刷新知识台账" :disabled="locked || extracting" @click="refresh"><RotateCw :size="16"/></button></header>
    <p v-if="error" class="t-error" role="alert">{{ error }}</p><p v-else-if="feedback" class="t-feedback" role="status">{{ feedback }}</p>
    <template v-if="state">
      <nav aria-label="知识梳理栏目" class="t-nav"><button v-for="name in views" :key="name" type="button" :aria-current="view === name ? 'page' : undefined" :disabled="locked || extracting" @click="view = name">{{ name }}</button></nav>
      <TacitSources v-if="view === '来源与范围'" :state="state" :resources="resources" :run-batch="runBatch" :dispatch="dispatch" :busy="locked" @upload="$emit('upload')" @extracting="extracting = $event" />
      <TacitLedger v-else-if="view === '业务台账'" :state="state" :dispatch="dispatch" :busy="locked"/>
      <TacitEvidenceReview v-else-if="view === '检索与对齐'" :state="state" :dispatch="dispatch" :busy="locked"/>
      <TacitOntology v-else-if="view === '本体模型'" :project-id="projectId" :state="state" :dispatch="dispatch" :busy="locked" @refresh="refresh"/>
      <TacitArtifacts v-else :run-batch="runBatch" :project-id="projectId" :state="state" :dispatch="dispatch" :busy="locked"/>
    </template>
    <p v-else-if="busy">正在读取项目台账…</p>
  </article>
</template>
<style src="../styles/tacit.css"></style>
