import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import assert from 'node:assert/strict'

test('collapsed work process hides every activity while keeping the toggle and running summary', () => {
  const source = readFileSync(new URL('../src/components/AssistantPanel.vue', import.meta.url), 'utf8')
  assert.match(source, /v-show="showEvents" id="assistant-process-entries"/)
  assert.match(source, /v-for="item in assistant.timeline"/)
  assert.match(source, /v-if="!showEvents && running" class="assistant-process-current"/)
  assert.match(source, /:aria-expanded="showEvents" aria-controls="assistant-process-entries"/)
  assert.doesNotMatch(source, /visibleActivities|timeline\.slice\(-4\)/)
  assert.doesNotMatch(source, /assistant-tool-group|assistant-inline-progress|执行步骤/)
  assert.match(source, /v-if="running && liveReply"/)
  assert.ok(source.indexOf('v-if="assistant.error"') > source.indexOf('</section>', source.indexOf('aria-label="工作过程"')))
})

test('error details use the content column instead of the narrow icon column', () => {
  const css = readFileSync(new URL('../src/styles/assistant.css', import.meta.url), 'utf8')
  const details = css.match(/\.assistant-error > details\s*\{([^}]+)\}/)[1]
  assert.match(details, /grid-column:\s*2/)
  assert.match(details, /min-width:\s*0/)
  const pre = css.match(/\.assistant-error > details > pre\s*\{([^}]+)\}/)[1]
  assert.match(pre, /white-space:\s*pre-wrap/)
  assert.match(pre, /overflow-wrap:\s*anywhere/)
  assert.match(pre, /max-height:/)
})

test('approval stays in the persistent composer, outside the scrolling conversation', () => {
  const source = readFileSync(new URL('../src/components/AssistantPanel.vue', import.meta.url), 'utf8')
  const footer = source.match(/<footer class="assistant-composer">([\s\S]*?)<\/footer>/)[1]
  assert.match(footer, /v-if="waitingConfirmation"/)
  assert.match(footer, /@click="assistant\.confirm\(hasBrowserAuthorization && rememberBrowserAuthorization\)"/)
  assert.equal((source.match(/@click="assistant\.confirm\b/g) ?? []).length, 1)
  assert.match(footer, /assistant-approval-scope/)
  assert.match(footer, /step.description/)
})

test('assistant column is viewport anchored with bounded width', () => {
  const css = readFileSync(new URL('../src/styles/assistant.css', import.meta.url), 'utf8')
  const rule = css.match(/\.assistant-codex-panel\s*\{([^}]+)\}/)[1]
  assert.match(rule, /position:\s*fixed/)
  assert.match(rule, /inset:\s*0 0 0 auto/)
  assert.match(rule, /max-width:\s*100vw/)
  assert.match(rule, /height:\s*100dvh/)
  const scope = css.match(/\.assistant-approval-scope\s*\{([^}]+)\}/)[1]
  assert.match(scope, /max-height:/)
  assert.match(scope, /overflow:\s*auto/)
})
