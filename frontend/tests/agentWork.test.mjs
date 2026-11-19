import test from 'node:test'
import assert from 'node:assert/strict'
import { feedbackContext, isReviewed, resolveArtifact, workStages, workStatus, preferredStage, stageReviewLabel } from '../src/domain/agentWork.ts'

const ref = { resourceId: 'file-1', version: 1 }
const resource = { id: 'file-1', currentVersion: 1 }
const stage = { id: 'check', title: '核对差异', status: 'SUCCEEDED', inputs: [], outputs: [ref] }
const work = { id: 'run-1', goal: '月度经营分析', status: 'SUCCEEDED', stages: [stage], resultSummary: '已生成底稿' }

test('execution completion never implies human review', () => {
  assert.equal(stageReviewLabel(stage, [], [resource]), '待检查')
  assert.equal(stageReviewLabel(stage, [ref], [resource]), '已检查')
  assert.equal(stageReviewLabel({ ...stage, outputs: [] }, [], [resource]), '执行完成')
})
test('landing chooses required input then unreviewed output, not completed cleanup', () => {
  const cleanup = { ...stage, id: 'cleanup', outputs: [] }
  const waiting = { ...stage, id: 'waiting', status: 'WAITING_INPUT' }
  assert.equal(preferredStage({ ...work, stages: [cleanup, stage] }, [], [resource]).id, 'check')
  assert.equal(preferredStage({ ...work, stages: [stage, waiting] }, [], [resource]).id, 'waiting')
  assert.equal(preferredStage({ ...work, stages: [cleanup, stage] }, [ref], [resource]).id, 'check')
})

test('business stages are preserved, not generated from tool types', () => {
  assert.deepEqual(workStages(work), [stage])
  assert.equal(workStages({ ...work, stages: [], status: 'RUNNING' })[0].status, 'RUNNING')
})
test('CLI round completion cannot hide missing input, failed or unfinished stages', () => {
  for (const state of ['WAITING_INPUT', 'FAILED', 'STALE']) {
    assert.equal(workStatus({ ...work, stages: [{ ...stage, status: state }] }), state)
  }
  assert.equal(workStatus({ ...work, stages: [{ ...stage, status: 'RUNNING' }] }), 'INCOMPLETE')
  assert.equal(workStatus({ ...work, status: 'CANCELED' }), 'CANCELED')
})
test('review applies only to the exact current artifact version', () => {
  const reviews = [{ ...ref, reviewedBy: 'user' }]
  assert.equal(isReviewed(ref, reviews, [resource]), true)
  assert.equal(isReviewed(ref, reviews, [{ ...resource, currentVersion: 2 }]), false)
  assert.equal(isReviewed({ ...ref, version: 2 }, reviews, [{ ...resource, currentVersion: 2 }]), false)
  assert.equal(isReviewed(ref, reviews, []), false)
})
test('missing or changed artifacts are explicit, never substituted silently', () => {
  assert.equal(resolveArtifact(ref, []).missing, true)
  assert.equal(resolveArtifact(ref, [{ ...resource, currentVersion: 2 }]).stale, true)
})
test('feedback carries work, business stage and exact artifact version', () => {
  const message = feedbackContext(work, stage, [ref], '按原订单月份处理')
  for (const expected of ['run-1', '核对差异', 'file-1@1', '原订单月份', '保留旧版本']) assert(message.includes(expected))
})
