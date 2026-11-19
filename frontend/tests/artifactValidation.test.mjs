import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

test('resource and output views neither fetch nor display quality ratings', () => {
  for (const file of ['components/ResourceWorkbench.vue', 'views/OutputsView.vue', 'api/client.ts']) {
    const source = readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')
    assert.doesNotMatch(source, /getDeliverableQuality|ArtifactQuality|quality\.score|质量检查.*分/)
  }
})
