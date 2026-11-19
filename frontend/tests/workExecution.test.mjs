import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const component = readFileSync(new URL('../src/components/WorkExecution.vue', import.meta.url), 'utf8')
test('fixed execution must never silently replan through the agent', () => {
  assert.ok(!component.includes('assistant.send('))
  assert.ok(!component.includes('repeatWorkRequest'))
  assert.ok(component.includes('api.startFixedWork('))
  assert.ok(component.includes('尚未固化实现'))
})
test('execution controls offer start and run-scoped cancellation', () => {
  assert.ok(component.includes('<Play :size="15"/>'))
  assert.ok(component.includes('开始执行'))
  assert.ok(component.includes('api.cancelFixedWork('))
  assert.ok(component.includes('api.cancelAssistantRun(id)'))
  assert.ok(component.includes('const id = props.work.id'))
  assert.ok(component.includes('v-if="running"'))
})
test('execution details belong to work, not stage tabs', () => {
  const source = readFileSync(new URL('../src/components/AgentWorkOverview.vue', import.meta.url), 'utf8')
  assert.ok(source.indexOf('<WorkExecution') < source.indexOf('<nav class="business-stages"'))
  assert.ok(!source.includes("tab === 'tech'"))
})
test('polling refresh does not reset state when the work object is replaced', () => {
  assert.ok(component.includes('watch([() => props.projectId, () => props.work.sessionId]'))
  assert.ok(!component.includes('watch(() => [props.projectId'))
})
