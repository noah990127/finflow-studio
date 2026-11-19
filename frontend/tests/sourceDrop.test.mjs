import test from 'node:test'
import assert from 'node:assert/strict'
import { sourceDropAccepts } from '../src/domain/sourceDrop.ts'

test('highlights the source area for external files and URLs', () => {
  assert.equal(sourceDropAccepts(['Files']), true)
  assert.equal(sourceDropAccepts(['text/uri-list', 'text/plain']), true)
})

test('does not highlight the source area while dragging workspace items to the canvas', () => {
  assert.equal(sourceDropAccepts(['application/x-finflow-resource']), false)
  assert.equal(sourceDropAccepts(['application/x-finflow-step', 'text/plain']), false)
})

test('ignores unsupported drag payloads', () => {
  assert.equal(sourceDropAccepts([]), false)
  assert.equal(sourceDropAccepts(['text/html']), false)
})
