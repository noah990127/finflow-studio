import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { isNativeDocument } from '../src/domain/nativeDocument.ts'

test('native document routing accepts Markdown names and formats only', () => {
  for (const name of ['report.md', 'REPORT.MARKDOWN']) assert.equal(isNativeDocument(name), true)
  assert.equal(isNativeDocument('report', 'text/markdown'), true)
  assert.equal(isNativeDocument('report.pdf'), false)
  assert.equal(isNativeDocument('report.html'), false)
})
test('both preview surfaces share the versioned document component', () => {
  for (const name of ['FilePreview', 'ResourceWorkbench']) {
    const source = readFileSync(new URL(`../src/components/${name}.vue`, import.meta.url), 'utf8')
    assert.match(source, /<NativeDocument/)
  }
})
test('editing saves expected versions, preserves drafts and warns on navigation', () => {
  const source = readFileSync(new URL('../src/components/NativeDocument.vue', import.meta.url), 'utf8')
  assert.match(source, /saveNativeDocument\(props.kind, props.resourceId, submitted, version.value\)/)
  assert.match(source, /sessionStorage.setItem/)
  assert.match(source, /beforeunload/)
  assert.match(source, /saved.version !== version.value/)
})
