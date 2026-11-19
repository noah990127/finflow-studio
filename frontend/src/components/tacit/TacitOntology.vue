<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { Download, RotateCw } from 'lucide-vue-next'
import { api } from '../../api/client'
import { buildBlockers, ontologyBundle, publishableDigest, publicationAction, publicationLabels, type OntologyBundle,
  type OntologyPublication, type OntologyTarget, type PublicationCommand } from '../../domain/ontology'
import { label, newRequestId, record, rows, type Dispatch, type Entry, type TacitState } from '../../domain/tacit'
import TacitFields from './TacitFields.vue'
import TacitResultPage from './TacitResultPage.vue'

const props = defineProps<{ projectId: string; state: TacitState; dispatch: Dispatch; busy: boolean }>()
const emit = defineEmits<{ refresh: [] }>()
const artifact = computed(() => props.state.artifacts.ontology_model)
const bundle = computed(() => ontologyBundle(artifact.value?.content))
const blockers = computed(() => buildBlockers(props.state))
const readyDigest = computed(() => publishableDigest(artifact.value?.status, bundle.value, blockers.value))
const validationLabels: Record<string, string> = {not_run:'尚未校核', stale:'校核已过期', incomplete:'校核未通过', path_verified:'路径校核已通过'}
const scope = computed(() => record(bundle.value?.manifest.scope ?? props.state.spec.scope))
const review = computed(() => record(bundle.value?.manifest.review))
const mappingLabels: Record<string, string> = {
  logical_object: '逻辑对象投影', physical_table: '物理表结构', manifest_only: '仅收录于清单',
}
const candidateRows = computed(() => rows(bundle.value?.manifest.candidates))
const candidateNames = computed(() => new Map(candidateRows.value.map(row => [String(row.candidate_id), String(row.name)])))
const relations = computed(() => rows(bundle.value?.manifest.relationships))
const tableNames = computed(() => {
  const names = new Map<string, string>()
  for (const database of rows(record(bundle.value?.model.data_layer).databases)) {
    for (const schema of rows(database.schemas)) {
      for (const table of rows(schema.tables)) names.set(String(table.id), schema.name + '.' + table.name)
    }
  }
  return names
})
const target = ref<OntologyTarget | null>(null)
const publications = ref<OntologyPublication[]>([])
const nextBefore = ref<string | null>(null)
const loading = ref(false), changing = ref(false)
const pastId = ref(''), past = shallowRef<OntologyBundle | null>(null), pastError = ref('')
const historyError = ref(''), actionError = ref('')
const current = computed(() => publications.value.find(row => row.model_digest === readyDigest.value && row.target_id === target.value?.target_id))
const pendingTarget = computed(() => publications.value.some(row => row.target_id === target.value?.target_id
  && ['PUBLISHING','UNCERTAIN'].includes(row.status)))
function targetName(id?: string) {
  if (!id) return ''
  const name = id === target.value?.target_id ? target.value.target_label : ''
  return name ? name + ' · ' + (id.length > 16 ? id.slice(0, 12) + '…' : id) : id
}
let generation = 0, mounted = true
const sameProject = (projectId: string) => mounted && projectId === props.projectId
let pendingCommand: PublicationCommand | null = null

async function load(older = false) {
  const project = props.projectId
  const seq = ++generation
  loading.value = true
  historyError.value = ''
  try {
    const page = await api.ontologyPublications(project, older ? nextBefore.value || undefined : undefined)
    if (seq !== generation || !sameProject(project)) return
    target.value = page.target
    publications.value = older ? [...publications.value, ...page.items.filter(row => !publications.value.some(p => p.id === row.id))] : page.items
    nextBefore.value = page.next_before
  } catch (error) {
    if (seq === generation && sameProject(project)) historyError.value = error instanceof Error ? error.message : '发布记录读取失败'
  } finally { if (seq === generation && sameProject(project)) loading.value = false }
}
watch(() => props.projectId, () => {
  publications.value = []; target.value = null; nextBefore.value = null
  actionError.value = ''; changing.value = false; pastId.value = ''; past.value = null; pastError.value = ''; pendingCommand = null
  void load()
}, { immediate: true })
onBeforeUnmount(() => { mounted = false; generation++ })

function requestFor(project: string, digest: string, targetId: string): PublicationCommand {
  const key = 'finflow:ontology:publish:' + project
  let previous = pendingCommand
  try { previous ??= JSON.parse(sessionStorage.getItem(key) || 'null') } catch { /* In-memory retry remains available. */ }
  if (previous?.model_digest === digest && previous.target_id === targetId &&
      previous.expected_revision === props.state.revision && typeof previous.request_id === 'string') return previous
  const command = { request_id: newRequestId(), expected_revision: props.state.revision, model_digest: digest, target_id: targetId }
  pendingCommand = command
  try { sessionStorage.setItem(key, JSON.stringify(command)) } catch { /* Keep the same key on the current page. */ }
  return command
}

async function change(row?: OntologyPublication) {
  const project = props.projectId
  const digest = readyDigest.value, targetId = target.value?.target_id
  if (changing.value || props.busy || !target.value?.configured || !targetId) return
  if (row ? !publicationAction(row, target.value, digest) : !digest || current.value || pendingTarget.value) return
  changing.value = true; actionError.value = ''
  try {
    const result = row ? await api.retryOntologyPublication(project, row.id, props.state.revision)
      : await api.publishOntology(project, requestFor(project, digest!, targetId))
    if (!sameProject(project)) return
    publications.value = [result, ...publications.value.filter(item => item.id !== result.id)]
    await load()
  } catch (error) {
    if (!sameProject(project)) return
    actionError.value = error instanceof Error ? error.message : '发布操作未完成'
    if ((error as { status?: number })?.status === 409) emit('refresh')
    await load()
  } finally { if (sameProject(project)) changing.value = false }
}

const fileTypes = {
  model: { name: 'model.yaml', mime: 'application/yaml' },
  manifest: { name: 'manifest.json', mime: 'application/json' },
  ontology_turtle: { name: 'ontology.ttl', mime: 'text/turtle' },
} as const
function download(value: OntologyBundle, part: keyof typeof fileTypes) {
  const content = part === 'ontology_turtle' ? value.ontology_turtle : JSON.stringify(value[part], null, 2) + '\n'
  if (!content) return
  const file = fileTypes[part]
  const url = URL.createObjectURL(new Blob([content], { type: file.mime + ';charset=utf-8' }))
  const link = document.createElement('a')
  link.download = file.name
  link.href = url; link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function openPast(row: OntologyPublication, retry = false) {
  const project = props.projectId
  if (pastId.value === row.id && !retry) { pastId.value = ''; past.value = null; return }
  pastId.value = row.id; past.value = null; pastError.value = ''
  try {
    const saved = await api.ontologyPublicationSnapshot(project, row.id)
    if (!sameProject(project) || pastId.value !== row.id) return
    if (!ontologyBundle(saved) || saved.digest !== row.model_digest) throw new Error('历史快照与发布记录的模型指纹不一致')
    past.value = saved
  } catch (error) {
    if (sameProject(project) && pastId.value === row.id) pastError.value = error instanceof Error ? error.message : '历史快照读取失败'
  }
}
function stamp(value: unknown) {
  const date = new Date(String(value ?? ''))
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN')
}
function reviewStamp(value: unknown) {
  const signed = record(value)
  return signed.by ? signed.by + ' · ' + stamp(signed.at) : '尚无签署'
}
function mapping(row: Entry) { return record(row.mapping) }
</script>

<template>
  <section class="t-section">
    <div class="t-section-head"><h2>本体模型 <small v-if="artifact">{{ artifact.status === 'ready' ? '已构建' : label(artifact.status) }}</small></h2>
      <button type="button" class="t-btn" :disabled="busy || changing || !!blockers.length" @click="dispatch('build_ontology_model')">{{ bundle ? '重新构建模型' : '构建模型' }}</button></div>
    <p v-if="blockers.length" class="t-muted">构建前请{{ blockers.join('、') }}。</p>
    <p v-if="artifact?.status === 'stale'" class="t-warn">台账或审核依据已变更。下方文件来自上次构建，重新构建后可发布当前版本。</p>
    <dl v-if="scope.goal" class="t-fields"><dt>业务目标</dt><dd>{{ scope.goal }}</dd>
      <template v-if="scope.in_scope"><dt>纳入范围</dt><dd>{{ Array.isArray(scope.in_scope) ? scope.in_scope.join('、') : scope.in_scope }}</dd></template>
      <template v-if="Array.isArray(scope.out_of_scope) && scope.out_of_scope.length"><dt>范围之外</dt><dd>{{ scope.out_of_scope.join('、') }}</dd></template></dl>
    <template v-if="bundle">
      <dl class="t-fields"><dt>流程签署</dt><dd>{{ reviewStamp(review.flows) }}</dd><dt>候选签署</dt><dd>{{ reviewStamp(review.candidates) }}</dd>
        <dt>模型指纹</dt><dd><code>{{ bundle.digest }}</code></dd></dl>
      <p v-if="bundle.quality" class="t-muted">场景校核 · {{ validationLabels[bundle.quality.scenario_validation] || bundle.quality.scenario_validation }}。实际业务结果需结合独立执行记录核对。</p>
      <div class="t-actions"><button class="t-btn ontology-download" @click="download(bundle, 'model')"><Download :size="14"/>下载 model.yaml</button>
        <button class="t-btn ontology-download" @click="download(bundle, 'manifest')"><Download :size="14"/>下载 manifest.json</button>
        <button v-if="bundle.ontology_turtle" class="t-btn ontology-download" @click="download(bundle, 'ontology_turtle')"><Download :size="14"/>下载 ontology.ttl</button></div>
      <p class="t-muted">model.yaml 可导入 NVIDIA Auto Ontology。清单保存审核记录、材料版本和候选映射。<template v-if="bundle.ontology_turtle">Turtle 文件提供 OWL 本体及对象关系。</template></p>
    </template><p v-else-if="!blockers.length" class="t-muted">构建后可查看映射并下载模型文件，离线使用无需配置发布目标。</p>
  </section>
  <section v-if="bundle" class="t-section">
    <div class="t-section-head"><h2>候选映射</h2><small>逻辑对象 {{ bundle.counts.logical_objects }} · 物理表 {{ bundle.counts.physical_tables }} · 仅收录于清单 {{ bundle.counts.manifest_only }}</small></div>
    <TacitResultPage :items="candidateRows" unit="项候选" v-slot="{items}">
      <details v-for="row in items" :key="String(row.candidate_id)" class="t-detail"><summary>{{ row.name }} <small>{{ mappingLabels[String(mapping(row).kind)] || mapping(row).kind }}</small></summary>
        <dl class="t-fields"><dt>候选编号</dt><dd>{{ row.candidate_id }}</dd>
          <template v-if="mapping(row).table_id"><dt>目标结构</dt><dd>{{ tableNames.get(String(mapping(row).table_id)) }}</dd>
            <dt>语义术语 ID</dt><dd>{{ mapping(row).term_id }}</dd></template>
          <template v-if="mapping(row).reason"><dt>映射说明</dt><dd>{{ mapping(row).reason }}</dd></template></dl>
        <details v-if="rows(mapping(row).fields).length"><summary>字段映射 · {{ rows(mapping(row).fields).length }}</summary>
          <TacitResultPage :items="rows(mapping(row).fields)" unit="个字段" v-slot="{items:fields}"><dl class="t-fields"><template v-for="field in fields" :key="String(field.column_id)"><dt>{{ field.name }}</dt><dd>列 {{ field.column_id }}<br/>属性 {{ field.attribute_id }}</dd></template></dl></TacitResultPage></details>
        <details v-if="rows(row.evidence).length"><summary>审核依据 · {{ rows(row.evidence).length }}</summary><TacitFields :fields="{evidence:row.evidence}"/></details>
      </details>
    </TacitResultPage>
    <details v-if="relations.length" class="t-detail"><summary>业务对象关系 · {{ bundle.counts.object_properties }} 项映射至 OWL / 共 {{ relations.length }} 项声明</summary>
      <TacitResultPage :items="relations" unit="项关系" v-slot="{items}">
        <details v-for="(relation,index) in items" :key="String(relation.property_iri ?? index)" class="t-detail"><summary>
          {{ relation.name }} <small>{{ relation.destination === 'ontology_turtle' ? 'OWL 对象属性' : '仅收录于清单' }}</small></summary>
          <TacitFields :fields="{'起点对象':candidateNames.get(String(relation.source_candidate_id)) || relation.source_candidate_id,
            '目标对象':candidateNames.get(String(relation.target_candidate_id)) || relation.target_object_id,
            cardinality:relation.cardinality,'事实状态':label(relation.fact_status), '属性 IRI':relation.property_iri,
            reason:relation.reason,evidence:relation.evidence}"/>
        </details>
      </TacitResultPage>
    </details>
    <details v-if="rows(bundle.manifest.warnings).length"><summary>映射提示 · {{ rows(bundle.manifest.warnings).length }}</summary><TacitFields :fields="{'需核对的映射':bundle.manifest.warnings}"/></details>
  </section>
  <section class="t-section" aria-label="NVIDIA Auto Ontology 发布">
    <div class="t-section-head"><h2>发布到 NVIDIA Auto Ontology</h2><button class="t-btn ontology-download" :disabled="loading || changing" @click="load()"><RotateCw :size="14"/>刷新记录</button></div>
    <p v-if="historyError" role="alert" class="t-error">{{ historyError }}</p>
    <p v-if="actionError" role="alert" class="t-error">{{ actionError }}</p>
    <template v-if="target?.configured"><div class="t-actions"><span>目标 · <strong>{{ targetName(target.target_id) }}</strong></span>
      <button v-if="!current" class="t-btn primary" :disabled="busy || loading || changing || !readyDigest || pendingTarget" @click="change()">发布当前模型</button>
      <span v-else>{{ publicationLabels[current.status] || current.status }}</span></div></template>
    <p v-else-if="target" class="t-muted">此项目未配置可用的发布目标。{{ target.reason }}</p>
    <template v-if="bundle && artifact?.status === 'ready' && !bundle.quality?.publication_ready">
      <ul v-if="bundle.quality?.reasons?.length" class="t-warn"><li v-for="reason in bundle.quality.reasons" :key="reason">{{ reason }}</li></ul>
      <p v-else class="t-warn">当前构建缺少可发布的校核记录，请完成场景校核后重新构建。</p>
    </template>
    <p v-if="pendingTarget && !current" class="t-muted">请先核验此目标未完成的发布记录，再发布新模型。</p>
    <p v-if="changing" class="t-muted" role="status">正在处理发布请求…</p>
    <ol class="t-list"><li v-for="row in publications" :key="row.id"><div>
      <strong>{{ publicationLabels[row.status] || row.status }}</strong> · {{ targetName(row.target_id) }}
      <small>{{ row.actor_id }} · {{ stamp(row.updated_at) }} · 台账 v{{ row.source_revision }} · 第 {{ row.attempt }} 次尝试</small>
      <p v-if="row.message">{{ row.message }}</p>
      <button v-if="publicationAction(row, target, readyDigest)" class="t-btn" :disabled="busy || changing || loading" @click="change(row)">{{ publicationAction(row, target, readyDigest) === 'verify' ? '核验目标状态' : '重试发布' }}</button>
      <div><button class="t-link" :aria-expanded="pastId === row.id" @click="openPast(row)">发布快照与回执</button></div>
      <div v-if="pastId === row.id"><dl class="t-fields"><dt>发布记录</dt><dd>{{ row.id }}</dd><dt>目标标识</dt><dd>{{ row.target_id }}</dd>
          <dt>模型指纹</dt><dd>{{ row.model_digest }}</dd><dt>创建时间</dt><dd>{{ stamp(row.created_at) }}</dd></dl>
        <div v-if="past" class="t-actions"><button class="t-btn" @click="download(past,'model')">下载历史模型</button>
          <button class="t-btn" @click="download(past,'manifest')">下载历史清单</button>
          <button v-if="past.ontology_turtle" class="t-btn" @click="download(past,'ontology_turtle')">下载历史 OWL 本体</button></div>
        <p v-else-if="pastError" role="alert" class="t-error">{{ pastError }} <button class="t-link" @click="openPast(row,true)">重试读取</button></p>
        <p v-else class="t-muted" role="status">正在读取此版快照…</p>
        <details v-if="row.receipt && Object.keys(row.receipt).length"><summary>目标服务回执</summary><TacitFields :fields="row.receipt" literal/></details>
      </div>
    </div></li></ol>
    <p v-if="loading" class="t-muted" role="status">正在读取发布记录…</p><p v-else-if="!historyError && !publications.length" class="t-muted">暂无发布记录。</p>
    <button v-if="nextBefore" class="t-link" :disabled="loading || changing" @click="load(true)">查看更早发布</button>
  </section>
</template>

<style scoped>
.ontology-download {display:inline-flex;align-items:center;gap:6px}
</style>
