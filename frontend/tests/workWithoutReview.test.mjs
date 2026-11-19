import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { preferredExecutionStage } from '../src/domain/agentWork.ts'

test('completed work opens final stage without requiring review marks', () => {
  const stages = [{ id: 'first', status: 'SUCCEEDED' }, { id: 'last', status: 'SUCCEEDED' }]
  assert.equal(preferredExecutionStage({ stages }).id, 'last')
  assert.equal(preferredExecutionStage({ stages: [{ id: 'waiting', status: 'WAITING_INPUT' }, ...stages] }).id, 'waiting')
})
test('overview no longer fetches or writes manual review state', () => {
  const source = readFileSync(new URL('../src/components/AgentWorkOverview.vue', import.meta.url), 'utf8')
  for (const obsolete of ['listArtifactReviews', 'reviewArtifact', '标记已检查', '待检查', 'isReviewed', 'reviewBlocked']) assert.ok(!source.includes(obsolete), obsolete)
  assert.ok(source.includes('selected.stale'))
  assert.ok(source.includes('ResourceWorkbench compact'))
})
