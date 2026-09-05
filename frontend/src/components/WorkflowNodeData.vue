<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Braces, Plus } from 'lucide-vue-next'
import { api, type WorkflowRun } from '../api/client'

type VariableNode = { id: string; data: { label: string; nodeType: string; config: Record<string, unknown> } }
const props = defineProps<{ node: VariableNode; ancestors: VariableNode[]; run: WorkflowRun | null }>()
const schema = ref<Record<string, Array<{ name: string; type: string; label: string }>>>({})
const error = ref(''), chosen = ref('')
onMounted(async () => { try { schema.value = await api.workflowVariables() } catch { error.value = '输出字段未加载，请重新打开节点' } })
const promptField = computed(() => ({ AI_ANALYSIS: 'prompt', AGENT_TASK: 'instruction', DELIVERABLE: 'generationPrompt', OUTPUT: 'generationPrompt', REF_SEARCH: 'query', DATA_TRANSFORM: 'requirements', PROCESS: 'requirements' } as Record<string, string>)[props.node.data.nodeType])
const contentInput = computed(() => ['AI_ANALYSIS', 'AGENT_TASK', 'DELIVERABLE', 'OUTPUT'].includes(props.node.data.nodeType))
const options = computed(() => props.ancestors.map(node => ({ node, fields: schema.value[node.data.nodeType] ?? [] })))
const contentOptions = computed(() => props.ancestors.map(node => ({
  value: `${node.id}.${['DELIVERABLE', 'OUTPUT'].includes(node.data.nodeType) ? 'text' : 'output'}`,
  label: ['FILE_INPUT', 'LINK_INPUT', 'DATASET_INPUT'].includes(node.data.nodeType) ? String(node.data.label) : `${node.data.label}的结果`,
})))
const references = computed(() => [...String(props.node.data.config[promptField.value ?? ''] ?? '').matchAll(/\{\{#([^#{}]+)#}}/g)].map(match => {
  const value = match[1]!, [nodeId, ...path] = value.split('.')
  const node = props.ancestors.find(item => item.id === nodeId)
  return { value, label: `${node?.data.label ?? '引用已失效'} · ${path.join('.')}` }
}))
const input = computed({
  get: () => { const source = props.node.data.config.inputSource as { nodeId: string; path: string[] } | undefined; return source ? `${source.nodeId}.${source.path.join('.')}` : '' },
  set: (value: string) => { if (!value) delete props.node.data.config.inputSource; else { const [nodeId, ...path] = value.split('.'); props.node.data.config.inputSource = { nodeId, path } } },
})
const selectedRun = computed(() => props.run?.nodes.find(node => node.nodeId === props.node.id))
const preview = computed(() => {
  const value = chosen.value || input.value
  if (!value) return undefined
  const [nodeId, ...path] = value.split('.')
  let result: unknown = props.run?.nodes.find(node => node.nodeId === nodeId)?.output
  for (const key of path) result = result && typeof result === 'object' ? (result as Record<string, unknown>)[key] : undefined
  return result
})
function display(value: unknown) { return typeof value === 'string' ? value : JSON.stringify(value, null, 2) }
function readable(value: unknown): string {
  if (typeof value === 'string') {
    try { return readable(JSON.parse(value)) } catch { return value }
  }
  if (!value || typeof value !== 'object') return value == null ? '尚未运行' : String(value)
  const item = value as Record<string, unknown>
  const body = item.analysis ?? item.text
  if (body !== undefined) return readable(body)
  const sections = item.slides ?? item.sections
  if (Array.isArray(sections)) return sections.map(section => [section.title ?? section.heading, section.summary, ...(section.bullets ?? []), ...(section.paragraphs ?? [])].filter(Boolean).join('\n')).join('\n\n')
  if (typeof item.output === 'string') return readable(item.output)
  if (Array.isArray(item.refs) && item.refs.length) return item.refs.map(ref => readable(ref.text)).join('\n\n')
  return [item.name ?? item.fileName, item.rowCount !== undefined ? `共 ${item.rowCount} 行数据` : '', item.count !== undefined ? `找到 ${item.count} 条内容` : ''].filter(Boolean).join('\n') || '处理已完成'
}
function insert() {
  if (!chosen.value || !promptField.value) return
  const config = props.node.data.config
  config[promptField.value] = `${String(config[promptField.value] ?? '')}\n{{#${chosen.value}#}}`
}
</script>

<template>
  <section class="node-data">
    <label v-if="contentInput"><span>使用哪些内容</span><select v-model="input" aria-label="使用哪些内容">
      <option value="">使用已连接步骤的结果</option>
      <option v-for="option in contentOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
      <option v-if="input && !contentOptions.some(option => option.value === input)" :value="input">已指定部分内容</option>
    </select></label>
    <details v-if="selectedRun"><summary>查看本步结果</summary><pre>{{ selectedRun.errorMessage || (selectedRun.status === 'PENDING' ? '等待前一步完成' : selectedRun.status === 'RUNNING' ? '正在处理' : readable(selectedRun.output)) }}</pre><small>第 {{ run?.workflowVersion }} 版 · {{ selectedRun.startedAt ? new Date(selectedRun.startedAt).toLocaleString() : '尚未执行' }}</small></details>
    <details class="advanced"><summary>高级设置</summary><div class="advanced-body">
    <p v-if="error" role="alert">{{ error }}</p>
    <label v-if="contentInput"><span>精确选择输入</span><select v-model="input" aria-label="精确选择输入">
      <option value="">使用已连接步骤的结果</option>
      <optgroup v-for="group in options" :key="group.node.id" :label="group.node.data.label">
        <option v-for="field in group.fields" :key="field.name" :value="`${group.node.id}.${field.name}`">{{ field.label }} · {{ field.name }} ({{ field.type }})</option>
      </optgroup>
      <option v-if="input && !options.some(group => group.fields.some(field => `${group.node.id}.${field.name}` === input))" :value="input">引用已失效</option>
    </select></label>
    <div v-if="promptField && ancestors.length" class="variable-insert">
      <Braces :size="15"/>
      <select v-model="chosen" aria-label="选择上游变量"><option value="">选择上游变量</option>
        <optgroup v-for="group in options" :key="group.node.id" :label="group.node.data.label">
          <option v-for="field in group.fields" :key="field.name" :value="`${group.node.id}.${field.name}`">{{ field.label }} · {{ field.name }} ({{ field.type }})</option>
        </optgroup>
      </select>
      <button class="icon-button" type="button" title="插入变量到要求" aria-label="插入变量到要求" :disabled="!chosen" @click="insert"><Plus :size="16"/></button>
    </div>
    <div v-if="references.length" class="variable-tags"><button v-for="(reference, index) in references" :key="index" type="button" :title="reference.label" @click="chosen = reference.value"><Braces :size="13"/>{{ reference.label }}</button></div>
    <details v-if="chosen || input"><summary>最近运行值</summary><pre>{{ preview === undefined ? '尚无运行值' : display(preview) }}</pre></details>
    <details><summary>输出变量</summary><dl><template v-for="field in schema[node.data.nodeType] ?? []" :key="field.name"><dt>{{ field.label }} <code>{{ field.name }}</code></dt><dd>{{ field.type }}</dd></template></dl></details>
    <template v-if="selectedRun">
      <details><summary>实际输入</summary><pre>{{ display(selectedRun.input) }}</pre></details>
      <details><summary>实际输出</summary><pre>{{ display(selectedRun.output) }}</pre></details>
      <details v-if="selectedRun.errorMessage"><summary>错误详情</summary><pre>{{ selectedRun.errorMessage }}</pre></details>
    </template>
    </div></details>
  </section>
</template>

<style scoped>
.node-data{border-bottom:1px solid #dce3ea;padding-bottom:14px;display:grid;gap:10px;min-width:0;font-size:12px;letter-spacing:0}
.node-data label{display:grid;gap:6px}.node-data select{width:100%;min-width:0;padding:7px;border:1px solid #d1dae5;border-radius:4px;background:white;color:#253344;font-size:12px}
.advanced>summary{color:#718092;font-size:11px}.advanced-body{display:grid;gap:10px;margin-top:10px}
.variable-insert{display:flex;gap:6px;align-items:center;min-width:0}.variable-insert select{flex:1}.variable-insert svg{flex-shrink:0}
.variable-tags{display:flex;gap:6px;flex-wrap:wrap}.variable-tags button{display:flex;align-items:center;gap:4px;max-width:100%;overflow-wrap:anywhere;border:0;border-bottom:1px solid #9cb3cf;background:#edf4fd;color:#315b8b;border-radius:3px;padding:4px 6px;font-size:12px;cursor:pointer}
summary{cursor:pointer;color:#415772}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:260px;overflow:auto;background:#f5f7fa;padding:8px;font-size:12px;margin:8px 0}dl{display:grid;grid-template-columns:1fr auto;gap:8px;margin:8px 0}dt{overflow-wrap:anywhere}dd{margin:0;color:#65768b}code{font-size:11px}small{color:#65768b}
</style>
