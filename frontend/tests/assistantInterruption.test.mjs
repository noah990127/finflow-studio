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
    builder.onLoad({filter: /src\/stores\/projects\.ts$/}, () => ({ contents: 'export const useProjectsStore = () => ({refresh: async () => {}});', loader: 'js' }))
  }}],
})
const { createStore } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`)
const api = globalThis.__assistantTestApi
function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
function store() {
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

test('a late session list from the previous project cannot replace the current project conversation', async () => {
  localValues.clear()
  const state = createStore()
  const first = deferred()
  const second = deferred()
  api.listAssistantSessions = projectId => projectId === 'project-a' ? first.promise : second.promise
  api.listAssistantMessages = async () => []
  api.listAssistantEvents = async () => []
  api.createSession = async projectId => ({ id: `new-${projectId}`, projectId, title: '新对话', status: 'ACTIVE', createdAt: '', updatedAt: '' })

  const loadingA = state.ensureSession('project-a')
  await Promise.resolve()
  const loadingB = state.ensureSession('project-b')
  second.resolve([{ id: 'session-b', projectId: 'project-b', title: 'B 对话', status: 'ACTIVE', createdAt: '', updatedAt: '' }])
  await loadingB
  first.resolve([{ id: 'session-a', projectId: 'project-a', title: 'A 对话', status: 'ACTIVE', createdAt: '', updatedAt: '' }])
  await loadingA

  assert.equal(state.sessionProjectTarget, 'project-b')
  assert.equal(state.sessionProjectId, 'project-b')
  assert.equal(state.sessionId, 'session-b')
  assert.deepEqual(state.sessions.map(item => item.id), ['session-b'])
})

test('each project restores its own latest conversation and creates new conversations in the active project', async () => {
  localValues.clear()
  localValues.set('finflow.assistant.session.project-a', 'session-a-old')
  const state = createStore()
  const sessions = {
    'project-a': [
      { id: 'session-a-new', projectId: 'project-a', title: 'A 新', status: 'ACTIVE', createdAt: '', updatedAt: '' },
      { id: 'session-a-old', projectId: 'project-a', title: 'A 上次', status: 'ACTIVE', createdAt: '', updatedAt: '' },
    ],
    'project-b': [{ id: 'session-b', projectId: 'project-b', title: 'B 对话', status: 'ACTIVE', createdAt: '', updatedAt: '' }],
  }
  api.listAssistantSessions = async projectId => sessions[projectId]
  api.listAssistantMessages = async () => []
  api.listAssistantEvents = async () => []
  const createdIn = []
  api.createSession = async projectId => {
    createdIn.push(projectId)
    return { id: `created-${projectId}`, projectId, title: '新对话', status: 'ACTIVE', createdAt: '', updatedAt: '' }
  }

  await state.ensureSession('project-a')
  assert.equal(state.sessionId, 'session-a-old')
  await state.ensureSession('project-b')
  assert.equal(state.sessionId, 'session-b')
  await state.ensureSession('project-a')
  assert.equal(state.sessionId, 'session-a-old')
  await state.createNewSession('project-a')
  assert.equal(state.sessionId, 'created-project-a')
  assert.deepEqual(createdIn, ['project-a'])
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

test('agent-created project navigation waits for the active run instead of canceling itself', () => {
  const state = store()
  state.run = {id:'run', status:'RUNNING', planId:'plan'}
  const actions = []
  state.publishWorkbenchAction = action => actions.push(action)

  state.handleEvent({ sessionId:'session', runId:'run', eventSeq:1, type:'assistant.step.completed', createdAt:new Date().toISOString(),
    payload:{uiAction:{type:'OPEN_PROJECT', projectId:'created-project', refreshWorkspace:true}} })

  assert.deepEqual(actions, [])
  assert.equal(state.interrupted, false)
  assert.match(state.timeline.at(-1).detail, /任务完成后/)
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
