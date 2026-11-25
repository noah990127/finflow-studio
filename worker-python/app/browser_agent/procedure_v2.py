"""Compile observed, typed dataflow actions into native browser-use replay."""
import copy

from .dataflow import FLOW_ACTIONS, Values, check_action


def compile_flow(history, goal):
    from .procedure import ENGINE, digest, entry_url, REPLAY_ACTIONS
    steps, bindings, parameters = [], [], {}
    contracts = Values()
    writes = set()
    user_inputs, consumed = set(), set()
    dependencies = {}
    def lineage(name):
        return {name} | set().union(*(lineage(parent) for parent in dependencies.get(name, set())))
    # Planned tails are not evidence. Only recorded dispatched actions may survive.
    for number, original in enumerate(history['history'], 1):
        planned = (original.get('model_output') or {}).get('action', [])
        results = original.get('result', [])
        if not planned:
            continue
        if len(results) > len(planned):
            raise ValueError('执行记录与动作数量不匹配')
        if len(results) != len(planned) and not all((r.get('metadata') or {}).get('executedAction') for r in results):
            raise ValueError('缺少实际动作凭据，不能把计划中的动作当作已执行')
        for index, record in enumerate(results):
            action = planned[index]
            observed = (record.get('metadata') or {}).get('executedAction')
            if observed:
                if set(observed) != set(action):
                    raise ValueError('实际动作顺序与计划不一致，需整理后发布')
                key = next(iter(observed))
                if any(action[key].get(field) != value for field, value in observed[key].items()):
                    raise ValueError('实际参数与计划不一致，需整理后发布')
                action = observed
            if len(action) != 1:
                raise ValueError('每个技术步骤必须是一个明确的工具调用')
            name, args = next(iter(action.items()))
            if record.get('error'):
                if (name in {'navigate', 'workflow_input', 'workflow_time', 'workflow_extract', 'workflow_transform', 'workflow_render', 'workflow_artifact'}
                        or (name in {'workflow_fill', 'workflow_click', 'workflow_keys', 'request_human'} and (record.get('metadata') or {}).get('preflightRejected') is True)):
                    continue
                raise ValueError('存在失败或结果不明的操作，不能自动发布重放')
            if name in {'done', 'workflow_outputs'}:
                continue
            if name == 'request_human':
                if args.get('kind') == 'LOGIN':
                    continue
                raise ValueError('人工输入或确认尚未绑定本轮数据；请使用固定确认步骤')
            if name not in FLOW_ACTIONS and name not in REPLAY_ACTIONS:
                raise ValueError('动作缺少固定执行适配器：' + name)
            if name in {'input', 'click', 'send_keys'}:
                raise ValueError('填写、点击或键盘操作缺少数据来源/副作用声明；请使用固定绑定工具')
            if name == 'navigate':
                from urllib.parse import urlsplit
                if urlsplit(args['url']).query:
                    raise ValueError('导航含会话参数，不能存入固定工序')
            if name in FLOW_ACTIONS:
                check_action(contracts, name, args)
                from .dataflow import document
                if name == 'workflow_input':
                    user_inputs.add(args['name'])
                if name in {'workflow_render', 'workflow_transform'}:
                    dependencies[args['name']] = {ref['source'] for ref in document(args['bindings_json']).values()}
                if name in {'workflow_fill','workflow_review','workflow_artifact','workflow_navigate'}:
                    consumed.update(lineage(document(args['binding_json'])['source']))
            if name in {'workflow_click', 'workflow_keys'} and args['effect'] == 'write':
                if args['operation'] in writes:
                    raise ValueError('对外操作标识重复，必须整理后发布')
                writes.add(args['operation'])
            state = copy.deepcopy(original['state'])
            elements = state.get('interacted_element') or []
            element = elements[index] if index < len(elements) else None
            if args.get('index') is not None and element is None:
                raise ValueError('缺少原生元素匹配信息，不能固化位置操作')
            if element and (element.get('attributes') or {}).get('type') == 'password':
                raise ValueError('登录凭证不能存入固定工序')
            state.update(url=entry_url(state.get('url', '')), tabs=[], screenshot_path=None, interacted_element=[element])
            action = copy.deepcopy(action)
            if name == 'workflow_input':
                key = args['name']
                parameters[key] = args['value']
                bindings.append({'parameter': key, 'step': len(steps), 'action': 0, 'tool': name, 'field': 'value'})
                action[name]['value'] = ''
            steps.append({'model_output': {'action': [action]}, 'state': state, 'result': [],
                          'metadata': None})
    if not steps:
        raise ValueError('没有可发布的固定步骤')
    if user_inputs - consumed:
        raise ValueError('声明的用户输入未连接到实际操作或产物：' + ', '.join(sorted(user_inputs - consumed)))
    body = {'version': 2, 'engine': ENGINE, 'url': entry_url(goal.url), 'goal': goal.goal,
            'allowed_domains': goal.allowed_domains, 'approval_mode': goal.approval_mode,
            'history': {'history': steps}, 'bindings': bindings,
            'contracts': contracts.contracts,
            'toolVersions': {next(iter(s['model_output']['action'][0])): 1 for s in steps},
            'validation': {'static': 'passed', 'firstExecution': 'succeeded', 'replay': 'not_tested'},
            'effects': {'writeOperations': sorted(writes), 'automaticRetry': False}}
    return {**body, 'hash': digest(body), 'parameters': parameters}


def candidate(history, reason):
    """A diagnostic artifact, never executable; omit page data and input values."""
    return {'version': 2, 'status': 'NEEDS_REVIEW', 'reason': reason,
            'steps': [{'step': i, 'tools': [next(iter(a), 'unknown') for a in (s.get('model_output') or {}).get('action', [])],
                       'planned': len((s.get('model_output') or {}).get('action', [])),
                       'returned': len(s.get('result', [])), 'hasError': any(r.get('error') for r in s.get('result', []))}
                      for i, s in enumerate(history.get('history', []), 1)]}
