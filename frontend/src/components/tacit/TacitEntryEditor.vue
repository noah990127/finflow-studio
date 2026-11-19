<script setup lang="ts">
import { computed, ref } from 'vue'
import { label, lines, rows, splitLines, type Dispatch, type Entry, type Section, type TacitState } from '../../domain/tacit'
const props = defineProps<{ original: Entry; section: Section; idKey: string; state: TacitState; dispatch: Dispatch; busy: boolean }>()
const emit = defineEmits<{ close: [] }>()
const clone = (obj: Entry) => JSON.parse(JSON.stringify(obj)) as Entry
const fields = ref(clone(props.original)), expected = ref(props.state.revision), error = ref('')
const choices: Record<string,string[]> = {
  status: props.section==='gaps' ? ['open','resolved'] : props.section==='acceptance_cases' ? ['defined','needs_confirmation'] : ['source_supported','proposed','unresolved','conflicted','user_confirmed'],
  process_disposition:['bound','independent','excluded'], disposition:props.section==='processes'?['included','subprocess','excluded']:['included','excluded'],
  kind:['normal','exception','boundary'], content_kind:['unspecified','business_text','table_structure','mixed'],
}
const primary = computed(() => Object.keys(fields.value).filter(key => key !== props.idKey && key !== 'evidence' &&
  typeof fields.value[key] !== 'object' && !['read_status','version','source_type'].includes(key)))
const listFields = computed(() => Object.keys(fields.value).filter(k=>['results','exceptions','inputs','outputs','roles','preconditions','assumptions','rule_ids','process_ids','data_table_ids','expected_path'].includes(k) && Array.isArray(fields.value[k])))
const other = Object.fromEntries(Object.entries(props.original).filter(([key]) => ![props.idKey,'evidence',...primary.value,...listFields.value].includes(key)))
const advanced = ref(JSON.stringify(other,null,2))
const evidence = ref(rows(props.original.evidence).map(e => ({source_id:String(e.source_id ?? ''),clause_id:String(e.clause_id ?? ''),quote:String(e.quote ?? '')})))
function clauses(sourceId: string) { return rows(props.state.spec.sources?.find(s=>s.source_id===sourceId)?.clauses) }
function markClause(index: number) {
  const row = evidence.value[index]
  const clause = clauses(row.source_id).find(c=>c.clause_id===row.clause_id)
  if (clause && !row.quote) row.quote = String(clause.quote ?? '')
}
async function save() {
  error.value = ''
  let extra: Entry
  try {
    extra = JSON.parse(advanced.value)
    if (!extra || typeof extra !== 'object' || Array.isArray(extra)) throw new Error()
    if (Object.keys(extra).some(key => key === props.idKey || key === 'evidence' || primary.value.includes(key) || listFields.value.includes(key)))
      throw new Error()
  } catch { error.value = '请检查结构化字段中的 JSON 对象，并移除已单独列出的字段。'; return }
  const item:Entry = { [props.idKey]:props.original[props.idKey], ...extra }
  for (const key of [...primary.value,...listFields.value]) item[key] = fields.value[key]
  if ('evidence' in fields.value || evidence.value.length) item.evidence = evidence.value
  if (await props.dispatch('save_batch',{section:props.section,items:[item]},expected.value)) emit('close')
  else if (props.state.revision !== expected.value) error.value = '版本已更新。请关闭此编辑器，再对最新条目进行修改。'
}
</script>
<template>
  <form class="t-box t-form" @submit.prevent="save">
    <div class="t-section-head"><h3>编辑{{ label(section) }}</h3><small>{{ original[idKey] }}</small></div>
    <template v-for="key in primary" :key="key">
      <label v-if="typeof fields[key] === 'boolean'" class="t-check"><input type="checkbox" v-model="fields[key]">{{ label(key) }}</label>
      <label v-else-if="choices[key]">{{ label(key) }}<select v-model="fields[key]"><option v-for="option in choices[key]" :value="option" :key="option" :disabled="option === 'user_confirmed'">{{ label(option) }}</option></select></label>
      <label v-else>{{ label(key) }}<input :value="String(fields[key] ?? '')" @input="fields[key] = ($event.target as HTMLInputElement).value"></label>
    </template>
    <label v-for="key in listFields" :key="key">{{ label(key) }}（每行一项）<textarea rows="2" :value="lines(fields[key])" @input="fields[key] = splitLines(($event.target as HTMLTextAreaElement).value)"/></label>
    <fieldset v-if="'evidence' in fields"><legend>材料依据</legend>
      <div v-for="(entry,i) in evidence" :key="i" class="t-form t-evidence">
        <label>来源<select v-model="entry.source_id" @change="entry.clause_id=''; entry.quote=''"><option value="">选择来源</option><option v-for="src in state.catalog" :key="src.source_id" :value="src.source_id">{{ src.title }}</option></select></label>
        <label>条款<select v-model="entry.clause_id" @change="markClause(i)"><option value="">选择条款</option><option v-for="clause in clauses(entry.source_id)" :key="String(clause.clause_id)" :value="clause.clause_id">{{ clause.locator }} · {{ String(clause.quote).slice(0,65) }}</option></select></label>
        <label>原文引句<textarea v-model="entry.quote" rows="2"/></label><button type="button" class="t-link" @click="evidence.splice(i,1)">移除此依据</button>
      </div><button type="button" class="t-link" @click="evidence.push({source_id:'',clause_id:'',quote:''})">添加依据</button>
    </fieldset>
    <details><summary>结构化字段</summary><label class="t-form">条件、条款、步骤等结构（JSON）<textarea v-model="advanced" rows="9" spellcheck="false"/></label></details>
    <p v-if="error" class="t-error" role="alert">{{ error }}</p>
    <div class="t-actions"><button class="t-btn primary" :disabled="busy || expected !== state.revision">保存条目</button><button type="button" class="t-btn" @click="emit('close')">取消编辑</button></div>
  </form>
</template>
