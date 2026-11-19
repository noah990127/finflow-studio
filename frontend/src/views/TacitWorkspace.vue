<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { RotateCw } from 'lucide-vue-next'
import { api, type WorkspaceResource } from '../api/client'
import { label, newRequestId, type Dispatch, type Entry, type TacitCommand, type TacitState } from '../domain/tacit'
import TacitSources from '../components/tacit/TacitSources.vue'
import TacitLedger from '../components/tacit/TacitLedger.vue'
import TacitArtifacts from '../components/tacit/TacitArtifacts.vue'

const props = defineProps<{ projectId: string; resources: WorkspaceResource[] }>()
defineEmits<{ upload: [] }>()
const views = ['来源与范围', '业务台账', '成果与审核'] as const
const view = ref<typeof views[number]>('来源与范围')
const state = shallowRef<TacitState | null>(null)
const busy = ref(false), error = ref(''), feedback = ref(''), extracting = ref(false)
let active = true
// Keep the exact request for a retry after a lost response.
let retry: { key: string; command: TacitCommand } | null = null
function install(value: TacitState) {
  state.value = { ...value, source_ids: value.source_ids ?? [], catalog: value.catalog ?? [],
    spec: value.spec ?? {}, issues: value.issues ?? [], artifacts: value.artifacts ?? {} }
}
async function refresh() {
  if (busy.value || extracting.value) return
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const fresh = await api.tacitWorkspace(props.projectId)
    if (active) install(fresh)
  } catch (e) { if (active) error.value = e instanceof Error ? e.message : '加载失败' }
  finally { if (active) busy.value = false }
}
const dispatch: Dispatch = async (action, payload: Entry = {}, expectedRevision = state.value?.revision) => {
  if (!active || busy.value || expectedRevision === undefined) return null
  busy.value = true
  error.value = ''
  feedback.value = ''
  const key = JSON.stringify([action, payload, expectedRevision])
  const command = retry?.key === key ? retry.command : { action, payload, expected_revision: expectedRevision, request_id: newRequestId() }
  try {
    const reply = await api.tacitCommand(props.projectId, command)
    if (!active) return null
    install(reply.workspace)
    retry = null
    feedback.value = action === 'preview_source' || action === 'read_source' || action === 'extract'
      ? '' : '已保存当前操作'
    return reply.result ?? {}
  } catch (e) {
    const status = (e as { status?: number })?.status
    if (!active) return null
    retry = !status || status >= 500 ? { key, command } : null
    error.value = e instanceof Error ? e.message : '操作失败'
    if (status === 409) {
      try {
        install(await api.tacitWorkspace(props.projectId))
        error.value += '。已载入最新版本，请重新核对后操作。'
      } catch { error.value += '。请刷新查看最新版本。' }
    }
    return null
  } finally { if (active) busy.value = false }
}
onMounted(refresh)
onBeforeUnmount(() => { active = false })
</script>
<template>
  <article class="tacit-panel" aria-label="业务知识梳理">
    <header class="tacit-head"><div><h1>业务知识梳理</h1><p v-if="state">版本 {{ state.revision }} · {{ label(state.spec.completion || 'draft') }}</p></div>
      <button type="button" class="icon-button" title="刷新知识台账" aria-label="刷新知识台账" :disabled="busy || extracting" @click="refresh"><RotateCw :size="16"/></button></header>
    <p v-if="error" class="t-error" role="alert">{{ error }}</p><p v-else-if="feedback" class="t-feedback" role="status">{{ feedback }}</p>
    <template v-if="state">
      <nav aria-label="知识梳理栏目" class="t-nav"><button v-for="name in views" :key="name" type="button" :aria-current="view === name ? 'page' : undefined" :disabled="busy || extracting" @click="view = name">{{ name }}</button></nav>
      <TacitSources v-if="view === '来源与范围'" :state="state" :resources="resources" :dispatch="dispatch" :busy="busy" @upload="$emit('upload')" @extracting="extracting = $event" />
      <TacitLedger v-else-if="view === '业务台账'" :state="state" :dispatch="dispatch" :busy="busy"/>
      <TacitArtifacts v-else :project-id="projectId" :state="state" :dispatch="dispatch" :busy="busy"/>
    </template>
    <p v-else-if="busy">正在读取项目台账…</p>
  </article>
</template>
<style src="../styles/tacit.css"></style>
