"""Typed values and explicit bindings; independent of sites and browser drivers."""
import copy
import json
import re
from string import Template

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

NAME = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]{0,63}$")
TEXT = {"type": "string", "maxLength": 10000}


def document(text):
    if not isinstance(text, str) or len(text) > 50000:
        raise ValueError("结构化参数必须是最多50000字符的JSON")
    return json.loads(text)


def schema(value):
    if not isinstance(value, dict) or len(json.dumps(value)) > 20000:
        raise ValueError("输出契约必须是有界JSON Schema对象")
    def inspect(node, depth=0):
        if depth > 20:
            raise ValueError("输出契约嵌套过深")
        if isinstance(node, dict):
            if any(key in node for key in ("$ref", "$dynamicRef")):
                raise ValueError("输出契约不能引用外部或递归Schema")
            for child in node.values():
                inspect(child, depth + 1)
        elif isinstance(node, list):
            for child in node:
                inspect(child, depth + 1)
    inspect(value)
    try:
        Draft202012Validator.check_schema(value)
    except SchemaError:
        raise ValueError('输出JSON Schema不合法') from None
    if value.get("type") not in {"object", "array", "string", "number", "integer", "boolean", "null"}:
        raise ValueError("输出契约必须声明明确类型")
    return copy.deepcopy(value)


def validate(value, contract):
    if len(json.dumps(value, ensure_ascii=False)) > 100000:
        raise ValueError("工具输出超过大小限制")
    if not Draft202012Validator(contract).is_valid(value):
        # jsonschema exceptions include instances: do not disclose their values.
        raise ValueError("数据与声明的类型、必填字段或长度约束不兼容")


class Values:
    """Per-run data, never shared between users or replay executions."""
    def __init__(self):
        self.contracts = {}
        self.values = {}

    def declare(self, name, contract):
        if not NAME.fullmatch(name) or name in self.contracts:
            raise ValueError("数据名称不合法或重复；每个输出必须使用独立名称")
        self.contracts[name] = schema(contract)

    def put(self, name, contract, value):
        validate(value, contract)
        self.declare(name, contract)
        self.values[name] = copy.deepcopy(value)
        return value

    def reference_schema(self, ref):
        if not isinstance(ref, dict) or set(ref) - {"source", "path"}:
            raise ValueError('绑定格式必须是 {"source":"输出名称","path":["字段"]}，不要再包一层对象')
        if ref.get("source") not in self.contracts:
            raise ValueError('绑定必须引用已声明的前序输出；当前可用名称：' + ', '.join(self.contracts))
        path = ref.get("path", [])
        if not isinstance(path, list) or len(path) > 20:
            raise ValueError("字段路径必须是最多20项的字段名或数组索引")
        selected = self.contracts[ref["source"]]
        for part in path:
            if selected.get("type") == "object" and isinstance(part, str):
                if part not in selected.get("required", []) or part not in selected.get("properties", {}):
                    raise ValueError('绑定引用了未声明或非必填字段；可用字段：' + ', '.join(selected.get('required', [])))
                selected = selected["properties"][part]
            elif selected.get("type") == "array" and type(part) is int and part >= 0:
                if selected.get("minItems", 0) <= part:
                    raise ValueError("数组绑定必须由minItems保证索引存在")
                selected = selected.get("items", {})
            else:
                raise ValueError("字段路径与输出类型不兼容")
        return selected

    def resolve(self, ref):
        self.reference_schema(ref)
        selected = self.values[ref["source"]]
        for part in ref.get("path", []):
            selected = selected[part]
        return copy.deepcopy(selected)

    def check_template(self, template, bindings):
        if not isinstance(template, str) or len(template) > 10000 or not isinstance(bindings, dict):
            raise ValueError("模板或绑定格式不正确")
        parsed = Template(template)
        if not parsed.is_valid() or set(parsed.get_identifiers()) != set(bindings):
            raise ValueError("模板占位符与绑定字段不一致；使用${name}")
        for ref in bindings.values():
            if self.reference_schema(ref).get("type") not in {"string", "number", "integer", "boolean"}:
                raise ValueError("模板只能引用标量字段，不能拼接整份对象")

    def render(self, template, bindings):
        self.check_template(template, bindings)
        result = Template(template).substitute({k: self.resolve(ref) for k, ref in bindings.items()})
        validate(result, TEXT)
        return result


FLOW_ACTIONS = {"workflow_input", "workflow_time", "workflow_extract", "workflow_render",
                "workflow_fill", "workflow_review", "workflow_artifact", "workflow_click", "workflow_keys", "workflow_tab", "workflow_transform", "workflow_navigate"}


def check_action(values, name, args):
    """Static publication check uses the same contracts as runtime execution."""
    if name == 'workflow_input':
        validate(args['value'], TEXT)
        values.put(args['name'], TEXT, args['value'])
    elif name == 'workflow_time':
        from zoneinfo import ZoneInfo
        ZoneInfo(args.get('timezone', 'Asia/Shanghai'))
        values.declare(args['name'], CLOCK)
    elif name in {'workflow_extract', 'workflow_transform'}:
        if not isinstance(args['instruction'], str) or not 1 <= len(args['instruction']) <= 4000:
            raise ValueError("提取提示词不能为空或超过4000字符")
        if name == 'workflow_transform':
            bindings = document(args['bindings_json'])
            if not isinstance(bindings, dict) or not bindings:
                raise ValueError('模型处理步骤必须绑定前序输入')
            for ref in bindings.values():
                values.reference_schema(ref)
        values.declare(args['name'], schema(document(args['output_schema_json'])))
    elif name == 'workflow_render':
        values.check_template(args['template'], document(args['bindings_json']))
        values.declare(args['name'], TEXT)
    elif name in {'workflow_fill', 'workflow_review', 'workflow_artifact', 'workflow_navigate'}:
        contract = values.reference_schema(document(args['binding_json']))
        if name != 'workflow_artifact' and contract.get('type') != 'string':
            raise ValueError("填写或确认内容必须绑定字符串输出")
        if name == 'workflow_artifact':
            if args['format'] not in {'md', 'txt', 'json'} or not NAME.fullmatch(args['name']):
                raise ValueError("产物名称或格式不受支持")
            if args['format'] != 'json' and contract.get('type') != 'string':
                raise ValueError("文本产物必须引用字符串输出")
    elif name in {'workflow_click', 'workflow_keys'}:
        if args.get('effect') not in {'read', 'write'} or not NAME.fullmatch(args['operation']):
            raise ValueError("页面操作必须声明作用及稳定操作名称")
        if name == 'workflow_keys' and (not isinstance(args['keys'], str) or not 1 <= len(args['keys']) <= 100):
            raise ValueError('按键名称长度不合法')
    elif name == 'workflow_tab':
        from urllib.parse import urlsplit
        parsed = urlsplit(args['url'])
        if parsed.scheme not in {'http', 'https'} or not parsed.hostname or parsed.query or parsed.username or parsed.password:
            raise ValueError("标签页定位必须使用不含会话参数的固定网址")


CLOCK = {"type": "object", "properties": {k: {"type": "string"} for k in ('time', 'date', 'timezone')},
         "required": ['time', 'date', 'timezone'], "additionalProperties": False}
