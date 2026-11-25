import copy
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.browser_agent.dataflow import Values, TEXT, document, schema
from app.browser_agent.procedure import compile_procedure, bind_procedure
from app.browser_agent.flow_tools import register, StructuredPage


CONTRACT = {'type': 'object', 'properties': {'amount': {'type': 'number'}, 'unit': {'type': 'string'}},
            'required': ['amount', 'unit'], 'additionalProperties': False}


def test_generic_fields_and_template_use_new_values():
    for amount in [12, 39]:
        values = Values()
        values.put('record', CONTRACT, {'amount': amount, 'unit': 'units'})
        assert values.render('${n} ${u}', {'n': {'source':'record', 'path':['amount']},
                                         'u': {'source':'record', 'path':['unit']}}) == f'{amount} units'


@pytest.mark.parametrize('binding', [{'source':'future'}, {'source':'record', 'path':['missing']},
                                   {'source':'record', 'path':[0]}])
def test_invalid_references_rejected_before_execution(binding):
    values = Values(); values.declare('record', CONTRACT)
    with pytest.raises(ValueError): values.reference_schema(binding)


def test_contracts_reject_remote_schema_and_wrong_type_without_leaking_data():
    with pytest.raises(ValueError): schema({'type':'object', '$ref':'https://example.com/schema'})
    values = Values()
    with pytest.raises(ValueError) as error:
        values.put('record', CONTRACT, {'amount':'private-value', 'unit':'units'})
    assert 'private-value' not in str(error.value)
    with pytest.raises(ValueError): values.put('text', TEXT, 'a' * 10001)


def step(name, args, index=1):
    action = {name: args}
    return {'model_output': {'action': [action]}, 'state': {'url':'https://example.com/', 'tabs':[],
            'interacted_element':[{'attributes': {'type':'text'}} if 'index' in args else None]},
            'result':[{'metadata': {'executedAction': copy.deepcopy(action)}}]}


def history():
    return {'history': [
        step('workflow_input', {'name':'audience', 'value':'Team'}),
        step('workflow_time', {'name':'clock', 'timezone':'UTC'}),
        step('workflow_extract', {'name':'record', 'instruction':'Read the displayed amount and unit', 'output_schema_json':json.dumps(CONTRACT)}),
        step('workflow_render', {'name':'report', 'template':'${who}: ${n} ${u} at ${time}', 'bindings_json':json.dumps({
            'who':{'source':'audience'}, 'n':{'source':'record','path':['amount']},
            'u':{'source':'record','path':['unit']}, 'time':{'source':'clock','path':['time']}})}),
        step('workflow_fill', {'index':1, 'binding_json':'{"source":"report"}', 'clear':True}),
        step('workflow_artifact', {'name':'report','format':'md','binding_json':'{"source":"report"}'}),
        {'model_output': {'action':[{'done':{'success':True}}]}, 'state':{}, 'result':[{'success':True}]}
    ]}


def freeze(h=None):
    return compile_procedure(h or history(), SimpleNamespace(url='https://example.com/',goal='Produce a report',allowed_domains=['example.com'],approval_mode='AUTO'))


def test_rejected_unbound_approval_is_not_frozen_but_real_manual_input_is_blocked():
    h = history()
    approval = step('request_human', {'reason':'old preview', 'kind':'APPROVAL'})
    approval['result'][0]['error'] = 'Use workflow_review'
    approval['result'][0]['metadata']['preflightRejected'] = True
    h['history'].insert(4, approval)
    assert freeze(h)['version'] == 2
    approval['result'][0].pop('error')
    approval['result'][0]['metadata'].pop('preflightRejected')
    with pytest.raises(ValueError, match='尚未绑定'):
        freeze(h)


def test_bound_review_survives_freezing_with_current_run_reference():
    h = history()
    h['history'].insert(4, step('workflow_review', {'binding_json':'{"source":"report"}'}))
    bundle = freeze(h)
    actions = [item['model_output']['action'][0] for item in bundle['history']['history']]
    assert {'workflow_review': {'binding_json':'{"source":"report"}'}} in actions


def test_page_scroll_does_not_require_an_element_but_indexed_scroll_does():
    h = history()
    h['history'].insert(1, step('scroll', {'down':True, 'pages':1, 'index':None}))
    h['history'][1]['state']['interacted_element'] = [None]
    assert freeze(h)['version'] == 2
    h['history'][1]['model_output']['action'][0]['scroll']['index'] = 3
    h['history'][1]['result'][0]['metadata']['executedAction']['scroll']['index'] = 3
    with pytest.raises(ValueError, match='元素匹配'):
        freeze(h)


def test_version_two_keeps_logic_immutable_and_user_inputs_editable():
    bundle = freeze()
    assert bundle['version'] == 2 and bundle['parameters'] == {'audience':'Team'}
    assert bundle['validation']['replay'] == 'not_tested'
    assert 'executedAction' not in json.dumps(bundle)
    changed = bind_procedure(bundle, {'audience':'Operations'})
    assert changed['history'][0]['model_output']['action'][0]['workflow_input']['value'] == 'Operations'
    assert changed['history'][2]['model_output'] == bundle['history']['history'][2]['model_output']
    bundle['history']['history'][2]['model_output']['action'][0]['workflow_extract']['instruction'] = 'changed'
    with pytest.raises(ValueError): bind_procedure(bundle, {'audience':'Team'})


def test_partial_batch_requires_actual_dispatch_receipt():
    h = history()
    h['history'][0]['model_output']['action'].append({'click':{'index':99}})
    assert freeze(h)['version'] == 2  # The undispatched tail is not replayed.
    h['history'][0]['result'][0] = {}
    with pytest.raises(ValueError, match='凭据'): freeze(h)


def test_unbound_values_and_unknown_write_are_not_published():
    h = history(); h['history'].insert(1, step('input', {'index':2, 'text':'old page value'}))
    with pytest.raises(ValueError, match='来源'): freeze(h)
    h = history(); h['history'][4]['result'][0]['error'] = 'uncertain'
    with pytest.raises(ValueError, match='结果不明'): freeze(h)


class Registry:
    """Small dependency-injection fixture; browser matching tested separately."""
    def __init__(self): self.actions = {}
    def action(self, description):
        def add(fn): self.actions[fn.__name__] = fn; return fn
        return add


@pytest.mark.asyncio
async def test_fixed_report_runs_twice_with_fresh_page_data():
    bundle = freeze()
    outputs = []
    for amount, audience in [(12, 'Team'), (39, 'Operations')]:
        tools = Registry(); files = []
        browser = SimpleNamespace(get_browser_state_summary=AsyncMock(return_value=SimpleNamespace(
            dom_state=SimpleNamespace(llm_representation=lambda: 'current page'))), take_screenshot=AsyncMock(return_value=None))
        llm = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(completion=StructuredPage(
            json_value=json.dumps({'amount':amount, 'unit':'units'})))))
        register(tools, browser, llm, AsyncMock(), files, 'AUTO')
        bound = bind_procedure(bundle, {'audience':audience})
        for s in bound['history']:
            name, args = next(iter(s['model_output']['action'][0].items()))
            if name != 'workflow_fill':
                await tools.actions[name](**args)
        import base64
        outputs.append(base64.b64decode(files[0]['bytes']).decode())
        assert llm.ainvoke.await_count == 1
    assert outputs[0].startswith('Team: 12 units')
    assert outputs[1].startswith('Operations: 39 units')
    assert 'Team: 12' not in outputs[1]


@pytest.mark.asyncio
async def test_failed_extraction_never_produces_a_value():
    tools = Registry(); files = []
    browser = SimpleNamespace(get_browser_state_summary=AsyncMock(return_value=SimpleNamespace(
        dom_state=SimpleNamespace(llm_representation=lambda: 'missing page fields'))), take_screenshot=AsyncMock(return_value=None))
    llm = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(completion=StructuredPage(json_value='{}'))))
    values = register(tools, browser, llm, AsyncMock(), files, 'AUTO')
    with pytest.raises(ValueError):
        await tools.actions['workflow_extract']('record', 'Read amount', json.dumps(CONTRACT))
    assert not values.values and not files


@pytest.mark.asyncio
async def test_write_requires_current_approval_and_is_never_retried():
    tools = Registry()
    tools.registry = SimpleNamespace(create_action_model=lambda: lambda **kwargs: kwargs)
    tools.act = AsyncMock(return_value=SimpleNamespace(error=None))
    human = AsyncMock(return_value={'decision':'approved'})
    register(tools, object(), object(), human, [], 'CONFIRM')
    await tools.actions['workflow_input']('preview', 'User-approved full preview')
    assert '确认' in (await tools.actions['workflow_click'](1, 'submit', 'write')).error
    assert tools.act.await_count == 0
    await tools.actions['workflow_review']('{"source":"preview"}')
    await tools.actions['workflow_click'](1, 'submit', 'write')
    assert '重复提交' in (await tools.actions['workflow_click'](1, 'submit', 'write')).error
    assert tools.act.await_count == 1
    await tools.actions['workflow_input']('new_data', 'changed')
    assert '变更' in (await tools.actions['workflow_click'](1, 'second_operation', 'write')).error


@pytest.mark.asyncio
async def test_model_transform_uses_bound_inputs_and_fixed_prompt():
    tools = Registry(); files = []
    llm = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(completion=StructuredPage(json_value='"Summary"'))))
    values = register(tools, object(), llm, AsyncMock(), files, 'AUTO')
    await tools.actions['workflow_input']('source', 'source material')
    await tools.actions['workflow_transform']('summary','Summarise input without adding facts',
        '{"document":{"source":"source"}}', json.dumps(TEXT))
    assert values.values['summary'] == 'Summary'
    prompt = str(llm.ainvoke.call_args)
    assert 'source material' in prompt and 'Summarise input without adding facts' in prompt
