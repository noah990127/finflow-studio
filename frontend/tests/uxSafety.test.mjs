import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8')
test('a changed stage output opens the exact referenced version and keeps the current preview separate', () => {
  const work = source('components/AgentWorkOverview.vue')
  const stale = work.match(/<section v-if="selected\.stale"[\s\S]*?<\/section>/)[0]
  assert.match(stale, /<NativeDocument[^>]+:resource-id="selectedRef\.resourceId"[^>]+:requested-version="selectedRef\.version"/)
  assert.match(stale, /@click="emit\('openResource', selectedRef\.resourceId\)">查看当前版本/)
  assert.doesNotMatch(stale, /<ResourceWorkbench/)
  assert.match(work, /v-else-if="selected\.missing"[\s\S]*?v-else-if="selected\.resource"[\s\S]*?<ResourceWorkbench[^>]+:resource="selected\.resource"/)
})
test('historical documents cannot save or restore current drafts', () => {
  const doc = source('components/NativeDocument.vue')
  assert.match(doc, /restore && !props.requestedVersion/)
  assert.match(doc, /!dirty.value \|\| props.requestedVersion\) return/)
  assert.match(doc, /!version.value \|\| props.requestedVersion\) return/)
  assert.match(doc, /if \(!props.requestedVersion\) sessionStorage.removeItem\(draftKey\)/)
})
test('work navigation verifies session binding before sending resource context', () => {
  const work = source('views/ProjectWorkbench.vue')
  assert.match(work, /await assistant.switchSession\(id\)/)
  assert.match(work, /if \(assistant.sessionId !== work.sessionId\) return/)
  assert.doesNotMatch(work, /works.slice\(0, 12\)/)
  assert.doesNotMatch(work, /所有内容自动保存在当前项目/)
})
test('bulk operations have one explicit confirmation and report partial failures', () => {
  const work = source('views/ProjectWorkbench.vue')
  assert.match(work, /selected.map\(r =>/)
  assert.match(work, /current.currentVersion !== resource.currentVersion/)
  assert.match(work, /failures.push/)
  assert.doesNotMatch(work, /window.confirm/)
})
