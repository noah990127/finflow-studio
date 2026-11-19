import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/components/AgentWorkOverview.vue', import.meta.url), 'utf8')
test('new work is selected on server acknowledgement, not after the long agent run', () => {
  const refresh = source.slice(source.indexOf('async function refresh'), source.indexOf('watch(() => props.project.id'))
  assert.match(refresh, /nextWorks\.some\(item => item\.sessionId === pendingSessionId\)/)
  assert.match(refresh, /props\.workId === '__new__'/)
  assert.match(refresh, /emit\('started', startedSession\)/)
  assert(source.indexOf('pendingSessionId = assistant.sessionId') < source.indexOf('await assistant.send(projectId, text)'))
})
