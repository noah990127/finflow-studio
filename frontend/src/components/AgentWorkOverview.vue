<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowRight, Files, MessageSquare, Paperclip, RefreshCw, Pencil, Info, Send, LoaderCircle } from 'lucide-vue-next'
import { api, type AgentWork, type FixedWorkState, type ArtifactRef, type CitationSource, type Project, type WorkspaceResource } from '../api/client'
import { feedbackContext, resolveArtifact, workStages, workStatus, workStatusLabels, preferredExecutionStage, primaryArtifacts, supportingArtifacts } from '../domain/agentWork'
import { useAssistantStore } from '../stores/assistant'
import ResourceWorkbench from './ResourceWorkbench.vue'
import NativeDocument from './NativeDocument.vue'
import WorkExecution from './WorkExecution.vue'
import { withFixedExecution } from '../domain/fixedWork'
import { isNativeDocument } from '../domain/nativeDocument'
import { ElMessageBox } from 'element-plus'
import { businessText } from '../domain/workProgress'

const props = defineProps<{ project: Project; resources: WorkspaceResource[]; workId?: string }>()
const emit = defineEmits<{ works: [works: AgentWork[]]; started: [sessionId: string]; openResource: [id: string]; addInput: []; resourcesChanged: []; openSource: [citation: CitationSource]; manageData: []; useAsInput: [resource: WorkspaceResource]; deleteResource: [resource: WorkspaceResource] }>()
const assistant = useAssistantStore()
const works = ref<AgentWork[]>([]), stageId = ref(''), artifactId = ref('')
const tab = ref<'output' | 'input'>('output'), error = ref(''), loading = ref(true)
const goalOpen = ref(false)
const goal = ref(''), feedback = ref(''), feedbackOpen = ref(false), submitting = ref(false)
let generation = 0, timer: ReturnType<typeof setTimeout> | undefined
let pendingSessionId = ''
const baseWork = computed(() => props.workId === '__new__' ? undefined : works.value.find(w => w.sessionId === props.workId || w.id === props.workId) ?? works.value[0])
const fixedState = ref<{ sessionId: string; state: FixedWorkState } | null>(null)
const work = computed(() => baseWork.value ? withFixedExecution(baseWork.value, fixedState.value?.sessionId === baseWork.value.sessionId ? fixedState.value.state : null) : undefined)
function fixedUpdated(sessionId: string, state: FixedWorkState) { fixedState.value = { sessionId, state } }
function executionChanged() { emit('resourcesChanged'); clearTimeout(timer); void refresh() }
const workTitle = computed(() => work.value?.title || work.value?.stages.at(-1)?.title || '未命名工作')
async function renameWork() {
  if (!work.value) return
  const current = work.value
  try {
    const result = await ElMessageBox.prompt('概括这项工作的核心目标', '重命名工作', { inputValue: workTitle.value, inputValidator: value => Boolean(value?.trim()) && value.length <= 40 || '请输入1到40个字符', confirmButtonText: '保存', cancelButtonText: '取消' })
    await api.renameWork(props.project.id, current.sessionId, result.value.trim()); clearTimeout(timer); await refresh()
  } catch (e) { if (e instanceof Error) error.value = e.message }
}
const stages = computed(() => work.value ? workStages(work.value) : [])
const stage = computed(() => stages.value.find(s => s.id === stageId.value) ?? (work.value ? preferredExecutionStage(work.value) : undefined))
const outputRefs = computed(() => primaryArtifacts(stage.value?.outputs ?? []))
const supportingRefs = computed(() => supportingArtifacts(stage.value?.outputs ?? []))
const selectedRef = computed(() => outputRefs.value.find(r => r.resourceId === artifactId.value) ?? outputRefs.value[0])
const selected = computed(() => selectedRef.value ? resolveArtifact(selectedRef.value, props.resources) : null)
const status = computed(() => work.value ? workStatusLabels[workStatus(work.value)] ?? work.value.status : '')


async function refresh(initial = false) {
  const current = ++generation, projectId = props.project.id
  if (initial) loading.value = true
  try {
    const nextWorks = await api.listWorks(projectId)
    if (generation !== current || props.project.id !== projectId) return
    works.value = nextWorks; emit('works', nextWorks); error.value = ''
    if (pendingSessionId && nextWorks.some(item => item.sessionId === pendingSessionId)) {
      const startedSession = pendingSessionId
      pendingSessionId = ''
      if (props.workId === '__new__') { goal.value = ''; emit('started', startedSession) }
    }
  } catch (e) { if (generation === current) error.value = e instanceof Error ? e.message : '工作没有加载' }
  finally { if (generation === current) { loading.value = false; timer = setTimeout(() => void refresh(), 4000) } }
}
watch(() => props.project.id, () => {
  clearTimeout(timer); works.value = []; stageId.value = ''; artifactId.value = ''; goal.value = ''; feedback.value = ''
  pendingSessionId = ''
  void refresh(true)
}, { immediate: true })
watch(() => work.value?.id, () => { stageId.value = ''; artifactId.value = ''; feedback.value = ''; feedbackOpen.value = false; tab.value = 'output' })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })


async function start() {
  if (!goal.value.trim() || assistant.canInterrupt || submitting.value) return
  const projectId = props.project.id, text = goal.value.trim()
  submitting.value = true
  try {
    await assistant.createNewSession(projectId)
    if (props.project.id !== projectId) return
    assistant.open = true
    assistant.setWorkbenchContext('project-home', props.project.name)
    // send() waits for the whole run; polling selects the work as soon as it exists.
    pendingSessionId = assistant.sessionId
    await assistant.send(projectId, text)
    if (props.project.id !== projectId) return
    if (!assistant.error && pendingSessionId && props.workId === '__new__') {
      goal.value = ''; emit('started', pendingSessionId)
    }
    clearTimeout(timer); await refresh()
  } finally { pendingSessionId = ''; submitting.value = false }
}
async function respond() {
  if (!work.value || !stage.value || !feedback.value.trim() || assistant.canInterrupt || submitting.value) return
  const projectId = props.project.id, sessionId = work.value.sessionId
  const text = feedbackContext(work.value, stage.value, outputRefs.value, feedback.value)
  submitting.value = true
  try {
    await assistant.ensureSession(projectId)
    if (props.project.id !== projectId) return
    await assistant.switchSession(sessionId)
    if (props.project.id !== projectId) return
    if (assistant.sessionId !== sessionId) throw new Error('原工作会话未打开，请稍后重试')
    assistant.open = true
    await assistant.send(projectId, text)
    if (!assistant.error) { feedback.value = ''; feedbackOpen.value = false }
    clearTimeout(timer); await refresh()
  } catch (e) { error.value = e instanceof Error ? e.message : '反馈没有发送' }
  finally { submitting.value = false }
}
function title(ref: ArtifactRef) { return props.resources.find(r => r.id === ref.resourceId)?.name ?? '资料尚未同步或已删除' }
function readable(text: string) {
  let value = businessText(text).replace(/\bREADY\b/g, '可用')
  for (const resource of props.resources) value = value.replaceAll(resource.id, resource.name)
  return value
}
</script>

<template>
  <section class="agent-work-overview">
    <p v-if="error" role="alert" class="work-error">{{ error }}</p>
    <div v-if="loading" class="resource-state">正在读取工作记录</div>
    <template v-else-if="work">
      <WorkExecution :work="baseWork || work" :project-id="project.id" :resources="resources" @executed="executionChanged" @state="fixedUpdated">
        <template #heading>
      <header class="agent-work-heading compact-heading"><div class="work-title-line"><h1>{{ workTitle }}</h1><button class="text-button compact-icon" title="重命名工作" aria-label="重命名工作" @click="renameWork"><Pencil :size="14"/></button><span class="work-status">{{ status }}</span><button class="text-button compact-icon" title="原始要求" aria-label="原始要求" :aria-expanded="goalOpen" @click="goalOpen = !goalOpen"><Info :size="15"/></button></div></header>
        </template>
      </WorkExecution>
      <p v-if="goalOpen" class="stage-summary">{{ work.goal }}</p>
      <nav class="business-stages" aria-label="业务工序"><button v-for="(item, index) in stages" :key="item.id" :class="{ selected: stage?.id === item.id, done: item.status === 'SUCCEEDED' }" :aria-current="stage?.id === item.id ? 'step' : undefined" @click="stageId = item.id; artifactId = ''; tab = 'output'"><span class="stage-number">{{ index + 1 }}</span><strong>{{ item.title }}</strong><small>{{ workStatusLabels[item.status] ?? item.status }}</small></button></nav>
      <template v-if="stage">

        <nav class="business-tabs" aria-label="工序内容"><button :class="{ active: tab === 'output' }" @click="tab = 'output'"><Files :size="15"/>产物</button><button :class="{ active: tab === 'input' }" @click="tab = 'input'"><Paperclip :size="15"/>输入</button><button class="stage-feedback" @click="feedbackOpen = !feedbackOpen"><MessageSquare :size="15"/>提出修改</button></nav>
        <div v-if="stage.question && stage.status === 'WAITING_INPUT'" class="work-question"><strong>需要你的输入</strong><p>{{ stage.question }}</p><button class="text-button" @click="feedbackOpen = true">补充输入<ArrowRight :size="14"/></button></div>
        <form v-if="feedbackOpen" class="work-feedback" @submit.prevent="respond">
          <p class="feedback-scope">修改 {{ stage.title }}<span v-for="item in outputRefs" :key="item.resourceId"> · {{ title(item) }} 第 {{ item.version }} 版</span></p>
          <textarea v-model="feedback" aria-label="修改要求" placeholder="补充判断或修改要求…" rows="3" required :disabled="submitting" />
          <div class="feedback-footer">
            <span v-if="assistant.canInterrupt" class="feedback-wait" role="status">当前任务仍在执行，请等待结束或先停止。</span>
            <div class="feedback-actions">
              <button type="button" class="feedback-cancel" :disabled="submitting" @click="feedbackOpen = false">取消</button>
              <button type="submit" class="feedback-send" :disabled="!feedback.trim() || assistant.canInterrupt || submitting"><LoaderCircle v-if="submitting" class="feedback-spinner" :size="14"/><Send v-else :size="14"/>{{ submitting ? '发送中' : '发送' }}</button>
            </div>
          </div>
        </form>
        <template v-if="tab === 'output'">
          <details v-if="supportingRefs.length" class="supporting-artifacts"><summary>辅助材料 · {{ supportingRefs.length }}</summary><button v-for="item in supportingRefs" :key="item.resourceId" class="stage-input" @click="emit('openResource', item.resourceId)"><Paperclip :size="14"/>{{ title(item) }} · v{{ item.version }}</button></details>
          <details v-if="stage.summary && outputRefs.length" class="stage-summary-disclosure"><summary>工序摘要</summary><p class="stage-summary">{{ readable(stage.summary) }}</p></details><p v-else-if="stage.summary" class="stage-summary">{{ readable(stage.summary) }}</p>
          <div v-if="!outputRefs.length" class="work-empty">{{ stage.status === 'SUCCEEDED' ? '此步结果已记录在上方摘要。' : '此步尚未登记产物。' }}</div>
          <div v-else class="artifact-bar"><label class="artifact-select"><Files :size="14"/><select :value="selectedRef?.resourceId" aria-label="工序产物" @change="artifactId = ($event.target as HTMLSelectElement).value"><option v-for="item in outputRefs" :key="item.resourceId" :value="item.resourceId">{{ title(item) }} · v{{ item.version }}</option></select></label><template v-if="selectedRef && selected && !selected.stale && !selected.missing"><button class="text-button compact-icon" title="展开阅读" aria-label="展开阅读" @click="emit('openResource', selectedRef.resourceId)"><ArrowRight :size="15"/></button></template></div>
          <template v-if="selectedRef && selected">
            <section v-if="selected.stale" class="work-question">产物已更新为第 {{ selected.resource?.currentVersion }} 版。此工序引用第 {{ selectedRef.version }} 版，需重新核对。<button class="text-button" @click="emit('openResource', selectedRef.resourceId)">查看当前版本</button><button class="text-button" @click="feedbackOpen = true; feedback = `请核对${title(selectedRef)}的最新版本，确认其输入与后续工序仍然有效后更新工序引用。保留旧版本和检查记录。`">核对新版并更新工序</button><details v-if="selected.resource && isNativeDocument(selected.resource.name, selected.resource.mediaType)"><summary>查看引用的第 {{ selectedRef.version }} 版</summary><NativeDocument :key="`${selectedRef.resourceId}-${selectedRef.version}`" :resource-id="selectedRef.resourceId" :requested-version="selectedRef.version" :kind="selected.resource.resourceType === 'DELIVERABLE' ? 'deliverables' : 'files'" /></details></section>
            <p v-else-if="selected.missing" class="work-empty">资料尚未同步或已删除。<button class="text-button" @click="emit('resourcesChanged')"><RefreshCw :size="14"/>同步资料</button></p>
            <template v-else-if="selected.resource">
              <div class="stage-resource-preview"><ResourceWorkbench compact :key="`${selected.resource.id}-${selected.resource.currentVersion}`" :resource="selected.resource" :location="`第 ${selected.resource.currentVersion} 版`" @open-source="emit('openSource', $event)" @resources-changed="emit('resourcesChanged')" @manage-data="emit('manageData')" @use-as-input="emit('useAsInput', $event)" @delete-resource="emit('deleteResource', $event)" /></div>
            </template>
          </template>
        </template>
        <template v-else-if="tab === 'input'"><div v-for="item in stage.inputs" :key="item.resourceId" class="stage-input"><Paperclip :size="16"/><span>{{ title(item) }}<small>第 {{ item.version }} 版{{ resolveArtifact(item, resources).stale ? ' · 来源已变化' : '' }}</small></span><button class="text-button" :disabled="resolveArtifact(item, resources).missing" @click="emit('openResource', item.resourceId)">查看</button></div><p v-if="!stage.inputs.length" class="work-empty">此步尚未登记输入，可从左侧资料选择补充。</p><details><summary>从项目资料选择</summary><button v-for="resource in resources" :key="resource.id" class="stage-input" @click="emit('useAsInput', resource)"><Paperclip :size="15"/>{{ resource.name }} · 第 {{ resource.currentVersion }} 版<ArrowRight :size="14"/></button></details><button class="text-button" @click="emit('addInput')"><Paperclip :size="15"/>上传新资料到项目</button></template>
      </template>
    </template>
    <template v-else><header class="agent-work-heading"><div><small>{{ project.name }}</small><h1>开始一项工作</h1></div></header><form class="new-agent-work" @submit.prevent="start"><label>希望完成什么？<textarea v-model="goal" rows="4" placeholder="例如：核对本月销售与预算差异，生成分析底稿和经营汇报。" /></label><div><button type="button" class="text-button" @click="emit('addInput')"><Paperclip :size="16"/>添加资料</button><button class="primary-button" :disabled="!goal.trim() || assistant.canInterrupt || submitting">{{ submitting ? '正在开始' : '交给 Agent' }}<ArrowRight :size="15"/></button></div></form><section class="existing-resources"><h2>项目资料与成果</h2><button v-for="resource in resources.slice(0, 12)" :key="resource.id" class="stage-input" @click="emit('openResource', resource.id)"><Files :size="16"/><span>{{ resource.name }}<small>第 {{ resource.currentVersion }} 版</small></span><ArrowRight :size="14"/></button></section></template>
  </section>
</template>

<style scoped>
.agent-work-overview .work-feedback{padding:12px 0 16px}
.agent-work-overview .feedback-scope{margin:0 0 8px;color:#7b8794;font-size:12px;line-height:1.6;overflow-wrap:anywhere}
.agent-work-overview .work-feedback textarea{margin:0;min-height:88px;max-height:240px;padding:10px 12px;font-size:13px;line-height:1.6;border-radius:6px;background:#fff;color:#35465c}
.work-feedback textarea::placeholder{color:#8b97a7}
.work-feedback textarea:focus-visible{outline:2px solid #b8d3f5;outline-offset:1px;border-color:#6d9edb}
.feedback-footer{display:flex;align-items:center;justify-content:flex-end;gap:12px;margin-top:8px;flex-wrap:wrap}
.feedback-wait{flex:1;min-width:180px;font-size:12px;color:#7b8794;line-height:1.6}
.feedback-actions{display:flex;align-items:center;gap:8px;margin-left:auto;flex-shrink:0}
.feedback-actions button{display:inline-flex;align-items:center;justify-content:center;gap:6px;height:32px;padding:0 12px;border:1px solid transparent;border-radius:4px;font:inherit;font-size:12px;font-weight:500;cursor:pointer;white-space:nowrap}
.feedback-cancel{background:transparent;color:#69788c}
.feedback-cancel:hover:not(:disabled){background:#f3f5f8}
.feedback-send{min-width:72px;background:#236dcc;color:#fff}
.feedback-send:hover:not(:disabled){background:#195eb5}
.feedback-send:disabled{background:#edf1f5;color:#99a4b2;cursor:default}
.feedback-cancel:disabled{opacity:.5;cursor:default}
.feedback-actions button:focus-visible{outline:2px solid #236dcc;outline-offset:2px}
.feedback-spinner{animation:feedback-spin 1s linear infinite}
@keyframes feedback-spin{to{transform:rotate(360deg)}}
@media(prefers-reduced-motion:reduce){.feedback-spinner{animation:none}}
.agent-work-overview .compact-heading .work-title-line{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.agent-work-overview .compact-heading h1{margin:4px 0;font-size:20px}
.work-status{font-size:11px;color:#7b8794}
.compact-icon{flex-shrink:0;justify-content:center;width:28px;height:28px}
.agent-work-overview .business-tabs{align-items:center;margin:0;gap:20px}
.business-tabs .stage-feedback{margin-left:auto}
.agent-work-overview .stage-summary-disclosure{margin:8px 0;font-size:11px}
.artifact-bar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:8px 0}
.artifact-select{display:flex;align-items:center;gap:6px;flex:1;min-width:0}
.artifact-select select{max-width:100%;min-width:0;width:100%;border:0;background:transparent;padding:6px 0;color:#536273;font:inherit;font-size:12px;text-overflow:ellipsis}
.artifact-bar>.text-button{flex-shrink:0}

.business-stages > button {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: flex-start;
  padding: 0;
}
.business-stages > button:not(:last-child)::before {
  content: '';
  position: absolute;
  top: 14px;
  left: 35px;
  right: -6px;
  border-top: 1px solid #dce4ed;
  pointer-events: none;
}
.business-stages > button .stage-number {
  position: relative;
  box-sizing: border-box;
  flex-shrink: 0;
  border: 1px solid #e0e6ee;
}
.business-stages > button.selected .stage-number {
  border-color: #236dcc;
  box-shadow: 0 0 0 4px #edf3fe;
}
.business-stages > button.selected strong { color: #236dcc; }
.business-stages > button:focus-visible { outline: 2px solid #236dcc; outline-offset: 4px; border-radius: 4px; }
.agent-work-overview{background:white;min-height:100%;padding:28px;letter-spacing:0}.agent-work-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.agent-work-heading>div{min-width:0}.agent-work-heading small{color:#8491a3;font-size:11px}.agent-work-heading h1{font-size:21px;line-height:1.5;margin:8px 0;overflow-wrap:anywhere;white-space:pre-wrap}.agent-work-heading p{color:#947135;font-size:12px}.business-stages{display:flex;gap:12px;overflow:auto;border-bottom:1px solid #e4eaf1;padding:22px 0;scrollbar-width:thin}.business-stages button{flex:1 0 100px;min-width:0;max-width:180px;background:none;border:0;text-align:left;color:#607087;cursor:pointer}.stage-number{display:flex;align-items:center;justify-content:center;width:29px;height:29px;border-radius:50%;background:#f0f3f7;margin-bottom:10px;font-size:12px}.selected .stage-number{background:#236dcc;color:white}.done:not(.selected) .stage-number{background:#eaf5ee;color:#2c8065}.business-stages strong{display:block;font-size:12px;overflow-wrap:anywhere}.business-stages small{display:block;font-size:10px;margin-top:5px;color:#8b97a7}.business-stage-title{display:flex;align-items:center;justify-content:space-between;gap:14px;margin:22px 0 8px}h2{font-size:16px;margin:0;overflow-wrap:anywhere}.business-tabs{display:flex;gap:24px;border-bottom:1px solid #e4eaf1;margin-bottom:20px}.business-tabs button{display:flex;align-items:center;gap:6px;padding:12px 0;background:none;border:0;border-bottom:2px solid transparent;color:#8090a3;cursor:pointer;font-size:12px}.business-tabs .active{color:#236dcc;border-color:#236dcc}.text-button{display:inline-flex;gap:6px;align-items:center;background:none;border:0;color:#236dcc;font-size:12px;cursor:pointer;padding:6px 0}.text-button:disabled{opacity:.5;cursor:default}.work-question{border-left:3px solid #c5a363;background:#fdf9f1;padding:14px 16px;font-size:12px;margin:16px 0;line-height:1.8}.stage-summary{font-size:13px;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere}.work-empty{padding:25px 0;color:#8390a2;font-size:12px}.artifact-switcher{display:flex;gap:16px;overflow:auto;border-bottom:1px solid #e5eaf1;margin-top:18px}.artifact-switcher button{display:flex;gap:7px;align-items:center;background:none;border:0;border-bottom:2px solid transparent;padding:12px 0;color:#77869a;white-space:nowrap;cursor:pointer;font-size:12px}.artifact-switcher button.active{color:#236dcc;border-color:#236dcc}.artifact-actions{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:12px 0}.review-success{display:flex;gap:6px;align-items:center;color:#29816b;font-size:12px}.stage-resource-preview{height:650px;min-height:420px;border-top:1px solid #e4eaf1;overflow:hidden}.stage-resource-preview :deep(.resource-workbench){height:100%;min-width:0}.stage-input{display:flex;align-items:center;gap:10px;padding:14px 0;border:0;border-bottom:1px solid #e4eaf1;background:none;width:100%;text-align:left;font-size:12px}.stage-input span{flex:1;min-width:0;overflow-wrap:anywhere}.stage-input small{display:block;color:#8190a2;font-size:10px;margin-top:4px}.work-execution{display:grid;grid-template-columns:80px minmax(0,1fr);font-size:12px;gap:12px}.work-execution dt{color:#8794a4}.work-execution dd{margin:0;overflow-wrap:anywhere}details{margin:18px 0;font-size:12px}summary{cursor:pointer;color:#627895}pre{padding:15px;background:#f6f8fb;white-space:pre-wrap;overflow-wrap:anywhere;max-height:420px;overflow:auto;font-size:11px}.new-agent-work{margin:22px 0 36px}.new-agent-work label,.work-feedback label{display:block;font-size:13px}.new-agent-work textarea,.work-feedback textarea{display:block;box-sizing:border-box;width:100%;resize:vertical;padding:12px;border:1px solid #dce4ee;border-radius:6px;margin:10px 0;font:inherit;line-height:1.8}.new-agent-work>div{display:flex;justify-content:space-between;gap:12px}.work-feedback{padding:15px 0;border-bottom:1px solid #e4eaf1}.work-feedback p,.work-error{font-size:12px;color:#b65858}.existing-resources{border-top:1px solid #e4eaf1;padding-top:24px}.existing-resources button{cursor:pointer}@media(max-width:620px){.agent-work-overview{padding:20px 16px}.business-stages button{flex-basis:90px}.agent-work-heading h1{font-size:19px}.stage-resource-preview{height:560px}.business-tabs{gap:18px}}
</style>
