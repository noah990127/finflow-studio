import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const panel = readFileSync(new URL('../src/components/AssistantPanel.vue', import.meta.url), 'utf8')

test('history browsing does not add a persistent work mismatch banner', () => {
  assert.doesNotMatch(panel, /assistant-context-warning|返回当前工作|当前正在查看另一段对话/)
})

test('sending targets the visible conversation regardless of the selected work', () => {
  const send = panel.slice(panel.indexOf('function send()'), panel.indexOf('function switchSession'))
  assert.doesNotMatch(panel, /workSessionId|请先在左侧选择要继续的工作/)
  assert.match(send, /controlsLocked.value \|\| assistant.stopping/)
  assert.match(send, /assistant.send\(props.project.id, text\)/)
  assert.doesNotMatch(send, /assistant.input = ''/)
})
