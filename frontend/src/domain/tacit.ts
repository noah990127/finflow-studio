export type Entry = Record<string, unknown>
export const sectionIds = {
  sources: 'source_id', data_objects: 'object_id', data_tables: 'table_id',
  rules: 'rule_id', processes: 'process_id', gaps: 'gap_id', acceptance_cases: 'case_id',
} as const
export type Section = keyof typeof sectionIds
export type TacitCatalogEntry = {
  source_id: string; title: string; source_type: string; version: string; read_status?: string
  incomplete?: boolean; reason?: string; total_characters?: number; author?:string; created_at?:string
}
export type TacitReview = {revision:number; action:string; target_id:string; target_digest:string; decision:string; actor:string; comment:string; created_at:string}
export type TacitReviewPage = {items:TacitReview[];next_before_revision:number|null}
export type TacitArtifact = { status: string; content: unknown; review?: {by:string;at:string;decision:string;comment:string;digest:string} }
export type TacitState = {
  revision: number; source_ids: string[]; catalog: TacitCatalogEntry[]
  spec: { scope?: Entry; completion?: string } & Partial<Record<Section, Entry[]>>
  issues: { code: string; path: string; message: string }[]
  interviews?: Record<string,{turns:number;last_asked_at:string;no_increment_count:number;paused:boolean}>
  coverage?: Record<string, number[][]>
  extraction?: Record<string, { version: string; next_offset: number | null }>
  artifacts: Partial<Record<'business_report' | 'gap_report' | 'flows' | 'candidates' | 'cqs' | 'walkthrough', TacitArtifact>>
}
export type TacitCommand = { action: string; expected_revision: number; request_id: string; payload: Entry }
export type TacitReply = { workspace: TacitState; result: Entry }
export type Dispatch = (action: string, payload?: Entry, expectedRevision?: number) => Promise<Entry | null>
export function record(value: unknown): Entry {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Entry : {}
}
export function rows(value: unknown): Entry[] {
  return Array.isArray(value) ? value.filter(item => item && typeof item === 'object' && !Array.isArray(item)) : []
}
export function lines(value: unknown): string {
  return Array.isArray(value) ? value.join('\n') : String(value ?? '')
}
export function splitLines(value: string) {
  return value.split('\n').map(line => line.trim()).filter(Boolean)
}
export function newRequestId() {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('')
}
export function extractionOffset(state: TacitState, source: TacitCatalogEntry) {
  const checkpoint = state.extraction?.[source.source_id]
  return checkpoint && checkpoint.version === source.version ? checkpoint.next_offset : 0
}
export function firstUnreadOffset(ranges:number[][]|undefined) {
  let end=0
  for (const [left,right] of ranges ?? []) {
    if (left>end) break
    end=Math.max(end,right)
  }
  return end
}
export function nextExtractionOffset(result: Entry, current: number): number | null {
  if (result.next_offset === null) return null
  if (typeof result.next_offset !== 'number' || !Number.isSafeInteger(result.next_offset) || result.next_offset <= current)
    throw new Error('服务未返回有效的下一页位置，抽取已暂停。')
  return result.next_offset
}
export const labels: Record<string, string> = {
  scope:'业务范围', goal:'业务目标', in_scope:'纳入范围', out_of_scope:'排除范围', period:'适用期间', region:'地区',
  sources:'来源条款', data_objects:'业务对象', data_tables:'数据表', rules:'业务规则', processes:'业务流程', gaps:'业务缺口', acceptance_cases:'验收场景',
  source_id:'来源编号', title:'标题', version:'版本', source_type:'来源类型', content_kind:'资料类别', read_status:'阅读进度',
  disposition:'处置方式', scope_reason:'范围依据', clauses:'条款', clause_id:'条款编号', locator:'原文位置', quote:'原文引句', coverage:'覆盖情况',
  rule_ids:'关联规则', process_ids:'关联流程', gap_ids:'关联缺口', data_object_ids:'关联对象', data_table_ids:'关联表', reason:'原因',
  name:'名称', object_id:'对象编号', identity_basis:'识别依据', fields:'业务字段', relationships:'对象关系', target_object_id:'目标对象',
  cardinality:'关系基数', table_id:'表编号', columns:'表字段', data_type:'类型', business_name:'业务名称', default_value:'默认值',
  default_status:'默认值依据', primary_key:'主键', foreign_key:'外键', constraints:'约束', unit:'单位', rule_id:'规则编号',
  condition:'适用条件', operator:'条件类型', field:'字段', comparator:'运算符', value:'取值', expression:'表达式',
  boundary_notes:'边界说明', logical_notes:'逻辑说明', results:'业务结果', inputs:'输入', outputs:'输出', exceptions:'例外',
  process_disposition:'流程归属', process_reason:'归属说明', effective_from:'生效时间', effective_to:'结束时间', precedence:'优先级',
  evidence:'证据', status:'状态', process_id:'流程编号', trigger:'触发条件', roles:'参与角色', preconditions:'前置条件',
  steps:'步骤', branches:'分支', parent_process_id:'上级流程', step_id:'步骤编号', actor:'执行人', kind:'类型', reads:'读取', writes:'写入',
  branch_id:'分支编号', from_step_id:'起点', to_step_id:'终点', is_exception:'异常分支', description:'说明',
  gap_id:'缺口编号', question:'待确认问题', impact:'业务影响', blocking:'阻断完成', resolution:'确认口径', case_id:'场景编号',
  expected_outputs:'期望结果', expected_path:'期望路径', assumptions:'假设', process:'流程', path_id:'路径编号',
  cq_text:'能力问题', cq_type:'问题类型', business_goal:'业务目标', business_context:'业务情境', expected_business_answer:'预期业务答案',
  modeling_requirement:'建模要求', technical_validation_question:'技术验收问题', review_status:'审核状态', related_entities:'相关对象',
  related_attributes:'相关属性', related_relations:'相关关系', source:'材料依据', cq_id:'问题编号', candidate_id:'候选编号',
  key:'来源条目', definition:'候选定义', scenarios:'推演场景', paths:'路径', findings:'校核发现', truncated:'路径枚举达到上限',
  complete:'已通过完整性检查', draft:'草稿', ready:'可用', pending:'待审核', approved:'已审核', rejected:'已退回', stale:'上游已变更',
  accepted:'已采纳',confirmed:'已确认',supplemented:'已补充',no_increment:'答复重复', source_supported:'有材料支持', user_confirmed:'专家已确认', proposed:'建议待确认', unresolved:'待确认',
  conflicted:'存在冲突', open:'未解决', resolved:'已解决', needs_confirmation:'待确认', defined:'已定义', included:'纳入', excluded:'排除',
  read:'已读完', unread:'未读', partial:'部分已读', normal:'正常', boundary:'边界', exception:'例外', asset:'项目文件', user:'专家记录',
  all:'全部满足',any:'任一满足',predicate:'字段条件',always:'无条件',gt:'大于',gte:'大于或等于',
  lt:'小于',lte:'小于或等于',eq:'等于',neq:'不等于',not_in:'不在集合内',in:'属于集合',exists:'存在',not_exists:'不存在',
  independent:'独立规则',bound:'关联流程',subprocess:'子流程',extracted:'已提取',unreviewed:'待梳理',gap:'待补依据',
  not_provided:'材料未提供',declared:'原文已声明',reviewed_by:'审核人',reviewed_at:'审核时间',case_kind:'场景类型',
  start:'开始',end:'结束',step:'操作',decision:'判断',blocking_gap_ids:'阻断缺口',path:'路径',path_ids:'路径编号',
  Logic:'流程逻辑', Function:'判断函数', Action:'业务动作', Object:'业务对象', Table:'数据表', needs_review:'待复核', incomplete:'未完成',
}
export function label(value: unknown) {
  const key = String(value ?? '')
  return labels[key] ?? key
}

// A source citation can fail validation even when the rule itself has a plausible status.
export function entryIssueIndex(state: TacitState, section: Section) {
  const issues = new Map<string, Set<string>>()
  const bySource = new Map<string, Set<string>>()
  function add(index:Map<string,Set<string>>, key:string, message:string) {
    if (!index.has(key)) index.set(key,new Set())
    index.get(key)!.add(message)
  }
  for (const issue of state.issues) {
    const path = /^(\w+)\[(\d+)\](?:\.clauses\[(\d+)\])?/.exec(issue.path)
    if (!path) continue
    const target = path[1] as Section
    const row = state.spec[target]?.[Number(path[2])]
    if (!row) continue
    if (target === section) add(issues,String(row[sectionIds[target]]),issue.message)
    if (target === 'sources') {
      const clause = path[3] === undefined ? '' : String(rows(row.clauses)[Number(path[3])]?.clause_id ?? '')
      add(bySource,JSON.stringify([row.source_id,clause]),issue.message)
    }
  }
  for (const item of state.spec[section] ?? []) {
    for (const ref of rows(item.evidence)) {
      for (const clause of ['', String(ref.clause_id)]) {
        for (const message of bySource.get(JSON.stringify([ref.source_id,clause])) ?? []) {
          add(issues,String(item[sectionIds[section]]),`引用依据 · ${message}`)
        }
      }
    }
  }
  return issues
}
