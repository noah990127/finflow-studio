import test from 'node:test'
import assert from 'node:assert/strict'
import { browserStates } from '../src/domain/browserHandoff.ts'

test('open and login use the real nested browserResult event contract', () => {
  const states = browserStates([{ payload: { output: { browserResult: { session: 'current', status: 'HUMAN_CONTROL' } } } }])
  assert.equal(states.get('current'), 'HUMAN_CONTROL')
  assert.equal(states.has('other-conversation'), false)
})

test('observations update nested open state without losing connection identity', () => {
  const states = browserStates([
    { payload: { output: { browserResult: { session: 'current', status: 'HUMAN_CONTROL' } } } },
    { payload: { output: { session: 'current', status: 'READY' } } },
    { payload: { output: null } },
    { payload: {} },
  ])
  assert.equal(states.get('current'), 'READY')
  assert.equal(states.size, 1)
})
