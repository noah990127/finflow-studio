import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8')
test('review is blocked while document is dirty, loading or on another version', () => {
  const work = source('components/AgentWorkOverview.vue')
  assert.match(work, /documentState\.value\.dirty/)
  assert.match(work, /documentState\.value\.loading/)
  assert.match(work, /documentState\.value\.version !== selectedRef\.value\?\.version/)
  assert.match(work, /if \(reviewBlocked\.value\) return/)
  assert.match(work, /watch\(\[\(\) => selectedRef/)
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
