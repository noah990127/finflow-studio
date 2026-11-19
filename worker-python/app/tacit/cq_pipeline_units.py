"""Evidence-anchored semantic units and question strategies shared by both generators."""
import re
from dataclasses import dataclass

ALLOWED_TYPES = {
    "domain_concept": ("查询类", "判断类", "推理类"),
    "rule": ("查询类", "判断类", "推理类", "触发类"),
    "process": ("查询类", "判断类", "触发类", "执行类"),
    "table_schema": ("查询类", "判断类", "推理类"),
    "interface_event": ("查询类", "判断类", "触发类", "执行类"),
    "terminology": ("查询类", "判断类", "推理类"),
}
HIGH_VALUE = {"rule", "relationship", "metric", "exception", "time_constraint",
              "action", "state_transition", "event_contract"}


@dataclass(frozen=True)
class Unit:
    strategy: str
    match: str
    topic: str
    intent: tuple[str, str]
    followup: tuple[str, str]
    model: str
    validate: str


UNITS = {
    "rule": Unit("rule", r"规则|规定|应当|必须|不得|禁止|如果|若|须|仅当|超过|低于|不低于|\b(must|shall|if|unless|policy|rule)\b",
                 "这项规定", ("判断类", "在什么条件下适用这项规定"), ("推理类", "满足条件后应得出什么结果"),
                 "表达适用条件、边界和结果", "用原文的边界值和不满足条件的样例验证规则。"),
    "relationship": Unit("domain_concept", r"关系|对应|关联|隶属|属于|一对[多一]|多对[多一]|\b(relates?|belongs?|relationship|references?)\b",
                         "这组业务关系", ("查询类", "涉及哪些业务对象，各自承担什么作用"), ("推理类", "一个对象能对应几个相关对象"),
                         "记录关系两端、方向和基数", "按两端业务标识检查关联基数。"),
    "metric": Unit("rule", r"指标|比率|比例|合计|计量|余额|平均|公式|占比|金额|统计|\b(metric|total|average|ratio|amount)\b",
                   "这项指标", ("查询类", "业务上按什么口径计算这项数值"), ("判断类", "哪些业务记录应纳入计算"),
                   "定义统计对象、口径、单位和计量期间", "使用原始记录复算指标并比对单位。"),
    "exception": Unit("rule", r"例外|除外|除非|异常|豁免|失败|拒绝|特殊情况|\b(exception|except|exempt|failure)\b",
                      "这类例外", ("判断类", "哪些情况应进入例外处理"), ("推理类", "适用例外时应得到什么结果"),
                      "表示例外条件、优先级和处置结果", "分别用正常与例外样例核对分支。"),
    "time_constraint": Unit("rule", r"截止|期限|时限|工作日|自然日|起算|到期|小时|分钟|日内|天内|\b(deadline|within|hours?|days?|expires?)\b",
                            "这项时限", ("查询类", "这个期限从何时开始，到何时结束"), ("判断类", "如何判定一笔业务已逾期"),
                            "表示起算事件、日历和截止条件", "核对起止时刻、临界点与跨日样例。"),
    "action": Unit("process", r"提交|审批|复核|冻结|解冻|冲销|通知|撤销|退回|执行|登记|支付|\b(approve|notify|submit|cancel|execute|register)\b",
                   "这项操作", ("执行类", "办理人要完成什么操作"), ("触发类", "何时应启动这项操作"),
                   "关联执行主体、前置条件、动作及结果", "验证操作的前置、执行和结果记录。"),
    "state_transition": Unit("process", r"转为|转入|流转|切换至|状态|变更为|由.{1,15}变为|\b(state|transition|status)\b",
                             "这次状态变化", ("判断类", "怎样判断业务已进入下一状态"), ("触发类", "什么业务事件会触发这次变化"),
                             "记录前态、后态、触发条件和守卫条件", "核对允许与禁止的状态迁移样例。"),
    "event_contract": Unit("interface_event", r"回调|接口|事件|订阅|推送|报文|幂等|webhook|\b(api|event|callback|payload|endpoint)\b",
                           "这项业务事件", ("触发类", "发生什么情况时要通知相关方"), ("执行类", "收到通知后，接收方应完成什么业务处理"),
                           "表示事件、发布者、接收者和业务结果", "核对消息载荷、重复投递和处理结果。"),
    "field_group": Unit("table_schema", r"字段|列名|主键|外键|数据表|属性|VARCHAR|CREATE TABLE|\b(column|schema|fields?)\b",
                        "这组业务信息", ("查询类", "这组信息分别描述哪些业务含义"), ("判断类", "怎样判断必需的业务信息已经齐全"),
                        "关联业务属性和物理字段及约束", "核对物理表列、数据类型、必填约束与原文。"),
    "process": Unit("process", r"流程|步骤|环节|阶段|办理|第一步|然后|\b(process|workflow|stage|step)\b",
                    "这项流程", ("查询类", "办理这项业务有哪些环节"), ("执行类", "每个环节如何交接结果"),
                    "表示流程节点、顺序、交接和结束条件", "验证从起点到终点的路径和交接数据。"),
    "role": Unit("domain_concept", r"角色|负责|经办人|办理人|审批人|管理员|岗位|\b(role|owner|clerk|reviewer|responsible)\b",
                 "这个岗位", ("查询类", "哪个角色负责这项工作"), ("判断类", "如何判断该角色有权办理"),
                 "明确角色、职责和授权边界", "按授权范围核对岗位和职责。"),
    "data_quality": Unit("table_schema", r"质量|缺失|重复|为空|必填|完整性|唯一|校验|\b(null|missing|duplicate|unique|mandatory)\b",
                         "这项质量要求", ("判断类", "怎样识别不合格的业务记录"), ("推理类", "信息不完整将影响哪些业务判断"),
                         "定义完整性、唯一性及问题处置规则", "用缺失、重复和边界样例核对质量约束。"),
    "input_output": Unit("process", r"输入|输出|入参|出参|产出|交付物|凭证|\b(inputs?|outputs?|deliverables?)\b",
                         "本环节的交付", ("查询类", "开始时要取得什么信息，结束时要交付什么"), ("判断类", "怎样判断产出满足下游需要"),
                         "关联输入、输出、业务环节和验收条件", "核对输入输出字段和下游验收样例。"),
    "taxonomy": Unit("terminology", r"术语|定义|称为|指的是|分类|分为|类型|类别|\b(taxonomy|classif\w*|defined|category|glossary)\b",
                     "这项业务分类", ("查询类", "业务人员怎样定义这些概念"), ("判断类", "如何区分相邻类别"),
                     "定义术语、分类层级和归类标准", "用典型与相邻类别样例核对归类结果。"),
    "sample_pattern": Unit("domain_concept", r"例如|示例|举例|样例|案例|模式|\b(example|sample|pattern)\b",
                           "这个业务样例", ("查询类", "该样例中有哪些业务要素"), ("推理类", "相似案例可采用什么判断依据"),
                           "把样例映射到对象、属性与预期结果", "按样例重放事实和判断。"),
    "object": Unit("domain_concept", r"对象|客户|订单|账户|合同|机构|产品|交易|业务主体|\b(object|customer|order|account|contract|entity)\b",
                   "这项业务内容", ("查询类", "这里涉及哪些业务对象"), ("判断类", "业务上怎样区分或识别这些对象"),
                   "记录业务对象、标识和边界", "用原文样例和业务标识核对对象。"),
}

TECHNICAL = re.compile(
    r"(?i:https?://)\S+|(?:^|\s)/[a-zA-Z][\w/-]+|\b(?:[a-zA-Z]\w*_[a-zA-Z0-9_]\w*|[a-z]+[A-Z][a-zA-Z0-9]+)\b"
    r"|[a-zA-Z_]+\.[a-zA-Z_]+|(?i:\b(?:HTTP|STATUS|VARCHAR|SELECT|CREATE TABLE|API|JSON|SQL)\b)"
    r"|`[A-Za-z][A-Za-z0-9_]*`|状态码|字段名|表名|(?<!\w)[1-5]\d\d(?![\w元天%])", re.ASCII)
SENTENCE = re.compile(r"[^\r\n]+?(?:[。！？；;]|[.!?](?=\s|$)|(?=[\r\n]|$))")


def is_business(text, physical_names):
    return bool(text.strip()) and not TECHNICAL.search(text) and not any(
        re.search(r"(?<![A-Za-z0-9_])" + re.escape(name) + r"(?![A-Za-z0-9_])", text, re.I)
        for name in physical_names)


def detect(text, content_kind, source_type):
    """Extract each grounded occurrence; interleave categories to avoid crowding out rarer units."""
    units = {}
    for match in SENTENCE.finditer(text):
        quote = match.group().strip()
        if not quote:
            continue
        start = match.start() + len(match.group()) - len(match.group().lstrip())
        for name, unit in UNITS.items():
            found = re.search(unit.match, quote, re.I)
            if found:
                # Keep the match inside the citation even on long, unpunctuated paragraphs.
                offset = max(0, found.start() - 80)
                units.setdefault(name, []).append(dict(type=name, quote=quote[offset:offset + 240], start=start + offset))
    if not units and text.strip():
        kind = "field_group" if content_kind == "table_structure" else (
            "taxonomy" if source_type == "terminology" else "object")
        units[kind] = [dict(type=kind, quote=text.strip()[:240], start=len(text) - len(text.lstrip()))]
    return [units[kind][i] for i in range(max(map(len, units.values()), default=0))
            for kind in UNITS if i < len(units.get(kind, []))]


def rule_question(unit, second, goal, physical_names):
    definition = UNITS[unit["type"]]
    intent, question = definition.followup if second else definition.intent
    quote = unit["quote"].strip("。！？；;，,！!？?")
    topic = quote[:58] if is_business(quote, physical_names) else definition.topic
    answer_form = re.sub(r"^(?:表达|记录|表示|关联|定义|明确|建立|把样例映射到)", "", definition.model)
    related_entities = re.findall(r"客户|订单|账户|合同|机构|产品|交易|经办人|审批人|办理人|管理员", quote)
    related_attributes = re.findall(r"金额|余额|期限|比例|比率|编号|利率|截止日期|起算日|时间", quote)
    related_relations = re.findall(r"一对多|多对一|一对一|多对多|隶属|属于|对应", quote)
    prompt = f"关于“{topic}”，{question}？"
    if not is_business(prompt, physical_names):
        prompt = question + "？"
    return dict(cq_text=prompt, cq_type=intent, business_goal=goal or "明确业务范围与判断依据",
                business_context=f"专家核对{definition.topic}时",
                expected_business_answer=f"待专家说明{answer_form}，并给出一个适用实例。",
                modeling_requirement=definition.model,
                technical_validation_question=definition.validate,
                related_entities=list(dict.fromkeys(related_entities)), related_attributes=list(dict.fromkeys(related_attributes)),
                related_relations=list(dict.fromkeys(related_relations)))
