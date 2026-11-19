import test from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'

const localValues = new Map()
globalThis.localStorage = {
  getItem: key => localValues.get(key) ?? null,
  setItem: (key, value) => localValues.set(key, value),
  removeItem: key => localValues.delete(key),
}
globalThis.window = { setTimeout, dispatchEvent() {} }
const eventSourceUrls = []
globalThis.EventSource = class {
  constructor(url) { eventSourceUrls.push(url) }
  addEventListener() {}
  close() {}
}
globalThis.__assistantTestApi = {}
globalThis.__projectsStore = {
  current: { id: 'project' },
  refreshCalls: [],
  async refresh(projectId) {
    this.refreshCalls.push(projectId)
    this.current = { id: projectId }
  },
}

const bundle = await build({
  stdin: {
    contents: `import { createPinia, setActivePinia } from 'pinia';
      import { useAssistantStore } from './src/stores/assistant.ts';
      export function createStore() { setActivePinia(createPinia()); return useAssistantStore(); }`,
    resolveDir: fileURLToPath(new URL('..', import.meta.url)), loader: 'ts',
  },
  bundle: true, write: false, platform: 'node', format: 'esm',
  plugins: [{ name: 'mock-api', setup(builder) {
    builder.onLoad({filter: /src\/api\/client\.ts$/}, () => ({ contents: 'export const api = globalThis.__assistantTestApi;', loader: 'js' }))
    builder.onLoad({filter: /src\/stores\/projects\.ts$/}, () => ({ contents: 'export const useProjectsStore = () => globalThis.__projectsStore;', loader: 'js' }))
  }}],
})
const { createStore } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`)
const api = globalThis.__assistantTestApi
test('public replies stream as replaceable snapshots without finishing the run', () => {
  const current = createStore()
  current.sessionId = 'session'
  current.streaming = true
  for (const [index, message] of ['正在', '正在核对资料'].entries()) current.handleEvent({
    sessionId:'session', eventSeq:index+1, type:'agent.message', createdAt:'2026-11-17T03:00:00Z',
    payload:{message, activity_id:'reply-one', status:'running'},
  })
  assert.equal(current.timeline.length, 1)
  assert.equal(current.timeline[0].detail, '正在核对资料')
  assert.equal(current.streaming, true)
  assert.equal(current.assistantMessage, '')
  current.interrupted = true
  current.handleEvent({sessionId:'session',eventSeq:3,type:'agent.message',payload:{message:'迟到内容'}})
  assert.equal(current.timeline.length, 1)
})
test('a previous turn refresh cannot overwrite or stop the next turn', async () => {
  const current = createStore()
  current.sessionId = 'session'
  current.activeRequestId = 'old'
  const response = deferred()
  api.getRun = () => response.promise
  const pending = current.refreshRun('old-run')
  current.activeRequestId = 'new'
  current.streaming = true
  current.busy = true
  response.resolve({id:'old-run',status:'SUCCEEDED'})
  await pending
  assert.equal(current.run, null)
  assert.equal(current.streaming, true)
  assert.equal(current.busy, true)
})
function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
function store() {
  globalThis.__projectsStore.current = { id: 'project' }
  globalThis.__projectsStore.refreshCalls = []
  globalThis.__projectsStore.refresh = async function refresh(projectId) {
    this.refreshCalls.push(projectId)
    this.current = { id: projectId }
  }
  const value = createStore()
  value.sessionId = 'session'
  value.sessionProjectId = 'project'
  value.sessionProjectTarget = 'project'
  value.ensureSession = async () => {}
  value.loadHistory = async () => {}
  value.syncEvents = async () => {}
  value.refreshPlan = async () => {}
  return value
}

test('switching projects keeps the same global conversation and only updates its context', async () => {
  localValues.clear()
  const state = createStore()
  const globalSession = { id: 'session-global', projectId: 'project-a', title: '全局对话', status: 'ACTIVE', createdAt: '', updatedAt: '' }
  api.listAssistantSessions = async () => [globalSession]
  api.listAssistantMessages = async () => []
  api.listAssistantEvents = async () => []

  await state.ensureSession('project-a')
  await state.ensureSession('project-b')

  assert.equal(state.sessionProjectTarget, 'project-b')
  assert.equal(state.sessionProjectId, 'project-b')
  assert.equal(state.sessionId, 'session-global')
  assert.deepEqual(state.sessions.map(item => item.id), ['session-global'])
})

test('global history is shared while a new conversation starts with the visible project as context', async () => {
  localValues.clear()
  localValues.set('finflow.assistant.session.global', 'session-old')
  const state = createStore()
  api.listAssistantSessions = async () => [
    { id: 'session-new', projectId: 'project-a', title: '新对话', status: 'ACTIVE', createdAt: '', updatedAt: '' },
    { id: 'session-old', projectId: 'project-a', title: '上次对话', status: 'ACTIVE', createdAt: '', updatedAt: '' },
  ]
  api.listAssistantMessages = async () => []
  api.listAssistantEvents = async () => []
  const createdIn = []
  api.createSession = async projectId => {
    createdIn.push(projectId)
    return { id: `created-${projectId}`, projectId, title: '新对话', status: 'ACTIVE', createdAt: '', updatedAt: '' }
  }

  await state.ensureSession('project-a')
  assert.equal(state.sessionId, 'session-old')
  await state.ensureSession('project-b')
  assert.equal(state.sessionId, 'session-old')
  await state.createNewSession('project-b')
  assert.equal(state.sessionId, 'created-project-b')
  assert.deepEqual(createdIn, ['project-b'])
})

test('completed writes publish one workspace update and late writes still refresh after interruption', () => {
  const state = store()
  state.sessionProjectId = 'project'
  const actions = []
  state.publishWorkbenchAction = action => actions.push(action)
  state.handleEvent({ sessionId: 'session', runId: 'run', eventSeq: 1, type: 'assistant.step.completed', createdAt: new Date().toISOString(),
    payload: { uiAction: { type: 'REFRESH_WORKSPACE', projectId: 'project', refreshWorkspace: true } } })
  assert.equal(actions.length, 1)
  state.interruptedRuns = ['run']
  state.interrupted = true
  state.handleEvent({ sessionId: 'session', runId: 'run', eventSeq: 2, type: 'assistant.step.completed', createdAt: new Date().toISOString(), payload: {} })
  assert.equal(actions.length, 2)
  assert.equal(state.interrupted, true)
})

test('a terminal event refreshes the final run and releases the conversation immediately', async () => {
  const state = store()
  state.run = {id:'run', status:'RUNNING', planId:'plan'}
  state.busy = true
  state.streaming = true
  api.getRun = async () => ({id:'run', status:'SUCCEEDED', planId:'plan', resultSummary:'已完成', finishedAt:'2026-09-08T08:11:28Z'})

  state.handleEvent({sessionId:'session', runId:'run', eventSeq:1, type:'assistant.run.completed',
    createdAt:'2026-09-08T08:11:28Z', payload:{message:'已完成'}})
  await Promise.resolve()

  assert.equal(state.run.status, 'SUCCEEDED')
  assert.equal(state.streaming, false)
  assert.equal(state.busy, false)
  assert.equal(state.progress, 100)
})

test('late activity and heartbeat events cannot move overall progress backwards', () => {
  const state = store()

  state.handleEvent({sessionId:'session', runId:'run', eventSeq:1, type:'agent.executing',
    createdAt:new Date().toISOString(), payload:{message:'正在执行工作流', progress:78}})
  state.handleEvent({sessionId:'session', runId:'run', eventSeq:2, type:'agent.thinking_summary',
    createdAt:new Date().toISOString(), payload:{message:'正在选择下一步', progress:18}})
  state.handleEvent({sessionId:'session', runId:'run', eventSeq:3, type:'agent.thinking_summary',
    createdAt:new Date().toISOString(), payload:{message:'仍在处理'}})

  assert.equal(state.progress, 78)
})

test('agent-created project opens immediately while the global conversation keeps running', async () => {
  const state = store()
  state.run = {id:'run', status:'RUNNING', planId:'plan'}
  const actions = []
  state.publishWorkbenchAction = action => actions.push(action)

  state.handleEvent({ sessionId:'session', runId:'run', eventSeq:1, type:'assistant.step.completed', createdAt:new Date().toISOString(),
    payload:{uiAction:{type:'OPEN_PROJECT', projectId:'created-project', refreshWorkspace:true}} })
  await new Promise(resolve => setTimeout(resolve, 0))

  assert.deepEqual(actions, [{type:'OPEN_PROJECT', projectId:'created-project', refreshWorkspace:true}])
  assert.deepEqual(globalThis.__projectsStore.refreshCalls, ['created-project'])
  assert.equal(globalThis.__projectsStore.current.id, 'created-project')
  assert.equal(state.interrupted, false)
  assert.match(state.timeline.at(-1).detail, /正在打开并继续处理/)

  state.handleEvent({sessionId:'session', runId:'run', eventSeq:2, type:'assistant.step.completed', createdAt:new Date().toISOString(),
    payload:{uiAction:{type:'REFRESH_WORKSPACE', projectId:'created-project', refreshWorkspace:true}}})
  assert.deepEqual(actions.at(-1), {type:'REFRESH_WORKSPACE', projectId:'created-project', refreshWorkspace:true})
  assert.equal(state.pendingWorkbenchAction, null)

  state.handleEvent({sessionId:'session', runId:'run', eventSeq:3, type:'assistant.run.completed',
    createdAt:new Date().toISOString(), payload:{message:'已完成', progress:100}})
  assert.equal(actions.filter(action => action.type === 'OPEN_PROJECT').length, 1)
})

test('a stale project switch cannot publish over a newer agent-created project', async () => {
  const state = store()
  const oldRefresh = deferred()
  const newRefresh = deferred()
  const actions = []
  state.publishWorkbenchAction = action => actions.push(action)
  globalThis.__projectsStore.refresh = async function refresh(projectId) {
    this.refreshCalls.push(projectId)
    await (projectId === 'old-project' ? oldRefresh.promise : newRefresh.promise)
    this.current = { id: projectId }
  }

  const oldSwitch = state.synchronizeAgentProject({type:'OPEN_PROJECT', projectId:'old-project', refreshWorkspace:true})
  const newSwitch = state.synchronizeAgentProject({type:'OPEN_PROJECT', projectId:'new-project', refreshWorkspace:true})
  newRefresh.resolve()
  await newSwitch
  oldRefresh.resolve()
  await oldSwitch

  assert.deepEqual(actions, [{type:'OPEN_PROJECT', projectId:'new-project', refreshWorkspace:true}])
  assert.equal(state.activeTaskProjectId, 'new-project')
  assert.equal(state.synchronizedAgentProjectId, 'new-project')
})

test('run polling restores a project switch when the live workspace event was missed', async () => {
  const state = store()
  state.run = {id:'run', status:'RUNNING', planId:'plan'}
  state.activeRequestId = 'request'
  state.refreshPlan = async () => {}
  state.syncEvents = async () => {}
  api.getRun = async () => ({id:'run', status:'SUCCEEDED', planId:'plan', resultSummary:'已完成',
    result:{createdProjectId:'recovered-project'}})

  await state.watchRun()

  assert.equal(globalThis.__projectsStore.current.id, 'recovered-project')
  assert.deepEqual(globalThis.__projectsStore.refreshCalls, ['recovered-project'])
  assert.equal(state.sessionProjectTarget, 'recovered-project')
})

test('a later tool observation restores an immediately missed project-open event', async () => {
  const state = store()
  state.run = {id:'run', status:'RUNNING', planId:'plan'}
  const actions = []
  state.publishWorkbenchAction = action => actions.push(action)

  state.handleEvent({sessionId:'session', runId:'run', eventSeq:2, type:'agent.observation', createdAt:new Date().toISOString(),
    payload:{message:'已创建项目', output:{createdProjectId:'observed-project'}, provenance:{toolName:'project.create_workspace'}}})
  await new Promise(resolve => setTimeout(resolve, 0))

  assert.equal(globalThis.__projectsStore.current.id, 'observed-project')
  assert.deepEqual(globalThis.__projectsStore.refreshCalls, ['observed-project'])
  assert.deepEqual(actions, [{type:'OPEN_PROJECT', projectId:'observed-project', refreshWorkspace:true}])
})

test('SSE reconnect starts after restored history and cannot replay old workspace actions', () => {
  eventSourceUrls.length = 0
  const state = createStore()
  state.sessionId = 'session-history'
  state.lastEventSequence = 46

  state.connectEvents()

  assert.deepEqual(eventSourceUrls, ['/api/assistant/sessions/session-history/events?after=46'])
})

test('thinking can be interrupted without a run id and the next message remains usable', async () => {
  const state = store()
  const pending = deferred()
  api.sendMessage = () => pending.promise
  api.interruptAssistantRequest = async (session, id) => {
    assert.equal(session, 'session')
    assert.equal(id, state.activeRequestId)
    return {status: 'CANCELED'}
  }
  const sending = state.send('project', '第一条要求')
  await Promise.resolve()
  assert.equal(state.canInterrupt, true)
  assert.equal(state.run, null)
  await state.cancel()
  pending.resolve({sessionId:'session', assistantMessage:'已停止', context:{}, plan:null})
  await sending
  assert.equal(state.interrupted, true)
  assert.equal(state.busy, false)
  assert.equal(state.error, '')
  api.sendMessage = async () => ({sessionId:'session', assistantMessage:'新的回答', context:{}, plan:{id:'new', steps:[], status:'COMPLETED'}})
  await state.send('project', '新的要求')
  assert.equal(state.interrupted, false)
  assert.equal(state.assistantMessage, '新的回答')
})

test('late polling and events cannot revive an interrupted run', async () => {
  const state = store()
  state.run = {id:'old', status:'RUNNING', planId:'plan'}
  state.activeRequestId = 'old-request'
  const pending = deferred()
  api.getRun = () => pending.promise
  const watching = state.watchRun()
  api.cancelAssistantRun = async () => ({id:'old', status:'CANCELED', planId:'plan'})
  await state.cancel()
  state.activeRequestId = 'new-request'
  state.run = {id:'new', status:'RUNNING', planId:'new-plan'}
  state.interrupted = false
  pending.resolve({id:'old', status:'SUCCEEDED', resultSummary:'迟到结果'})
  await watching
  state.handleEvent({sessionId:'session', runId:'old', eventSeq:10, type:'assistant.run.completed', payload:{message:'迟到结果'}, createdAt:new Date().toISOString()})
  assert.equal(state.run.id, 'new')
  assert.notEqual(state.assistantMessage, '迟到结果')
})

test('approval can be canceled without executing it', async () => {
  const state = store()
  state.plan = {id:'approval', status:'WAITING_CONFIRMATION', steps:[{requiresConfirmation:true, status:'PENDING'}]}
  api.cancelAssistantPlan = async () => ({status:'CANCELED'})
  api.getAssistantPlan = async () => ({id:'approval', status:'CANCELED', steps:[]})
  assert.equal(state.canInterrupt, true)
  await state.cancel()
  assert.equal(state.needsConfirmation, false)
  assert.equal(state.canInterrupt, false)
})
