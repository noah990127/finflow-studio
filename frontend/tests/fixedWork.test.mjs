import test from 'node:test'
import assert from 'node:assert/strict'
import { withFixedExecution } from '../src/domain/fixedWork.ts'
import { primaryArtifacts, supportingArtifacts } from '../src/domain/agentWork.ts'

test('business contract wins over technical steps; outcomes retain explicit roles', () => {
  const outputs = [{resourceId:'raw',version:1,role:'INTERNAL'}, {resourceId:'report',version:2,role:'PRIMARY'}, {resourceId:'appendix',version:1,role:'SUPPORTING'}]
  const businessStages = [{id:'business',title:'核对账目',outputs,inputs:[],status:'SUCCEEDED'}]
  const shown = withFixedExecution(work, {runs:[{...run,businessStages}]})
  assert.deepEqual(shown.stages, businessStages)
  assert.deepEqual(primaryArtifacts(outputs).map(r=>r.resourceId), ['report'])
  assert.deepEqual(supportingArtifacts(outputs).map(r=>r.resourceId), ['appendix'])
  assert.equal(primaryArtifacts([{resourceId:'legacy',version:1}]).length, 1)
})

const work = { id: 'agent-run', createdAt: '2026-11-16T01:00:00Z', stages: [], status: 'SUCCEEDED' }
const run = { id: 'fixed-run', createdAt: '2026-11-16T02:00:00Z', status: 'RUNNING', error: '', stages: [{ id: 's1', title: '计算', status: 'RUNNING', inputs: [{ resourceId: 'in', version: 2 }], output: null, error: '' }] }
test('fixed execution stages use actual bound input and output versions', () => {
  const displayed = withFixedExecution(work, { runs: [run] })
  assert.equal(displayed.status, 'RUNNING')
  assert.equal(displayed.stages[0].inputs[0].version, 2)
  assert.deepEqual(displayed.stages[0].outputs, [])
  const done = withFixedExecution(work, { runs: [{ ...run, status: 'SUCCEEDED', stages: [{ ...run.stages[0], status: 'SUCCEEDED', output: { resourceId: 'out', version: 1 } }] }] })
  assert.deepEqual(done.stages[0].outputs, [{ resourceId: 'out', version: 1 }])
})
test('new Agent revision takes precedence over an older fixed execution', () => {
  const revised = { ...work, createdAt: '2026-11-16T03:00:00Z' }
  assert.equal(withFixedExecution(revised, { runs: [run] }), revised)
  assert.equal(withFixedExecution(work, null), work)
})
