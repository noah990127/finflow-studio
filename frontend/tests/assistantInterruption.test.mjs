import test from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'

globalThis.localStorage = { getItem: () => null, setItem() {} }
globalThis.window = { setTimeout, dispatchEvent() {} }
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
  value.ensureSession = async () => {}
  value.loadHistory = async () => {}
  value.syncEvents = async () => {}
  value.refreshPlan = async () => {}
  return value
}

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
