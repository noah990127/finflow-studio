import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

test('stage connectors join adjacent steps without a trailing line or intercepted clicks', () => {
  const source = readFileSync(new URL('../src/components/AgentWorkOverview.vue', import.meta.url), 'utf8')
  assert.match(source, /button:not\(:last-child\)::before/)
  assert.match(source, /top: 14px;/)
  assert.match(source, /left: 35px;/)
  assert.match(source, /pointer-events: none;/)
  assert.match(source, /button.selected .stage-number/)
  assert.match(source, /button:focus-visible/)
})
