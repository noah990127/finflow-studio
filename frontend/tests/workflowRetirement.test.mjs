import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'

test('My Work is the only workflow surface and legacy canvas is removed', () => {
  const source = readFileSync(new URL('../src/views/ProjectWorkbench.vue', import.meta.url), 'utf8')
  assert.match(source, /我的工作/)
  assert.match(source, /AgentWorkOverview/)
  assert.doesNotMatch(source, /已保存的方法与技术流程|WorkflowView|createWorkflow\(|function nodeFor/)
  assert.equal(existsSync(new URL('../src/views/WorkflowView.vue', import.meta.url)), false)
})
test('resource input is handed to the agent without creating legacy nodes', () => {
  const source = readFileSync(new URL('../src/components/ResourceWorkbench.vue', import.meta.url), 'utf8')
  assert.match(source, /用于当前工作/)
  assert.match(source, /useAsInput/)
  assert.doesNotMatch(source, /加入工作流|addToWorkflow/)
  const overview = readFileSync(new URL('../src/components/AgentWorkOverview.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(overview, /openWorkflow|查看底层工作流/)
  assert.match(overview, /<WorkExecution\b[^>]*:work="baseWork \|\| work"/)
  const execution = readFileSync(new URL('../src/components/WorkExecution.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(execution, /openWorkflow|查看底层工作流/)
  assert.match(execution, /执行结果与来源记录<\/summary><pre>\{\{ JSON\.stringify\(work\.result/)
})
