import test from 'node:test'
import assert from 'node:assert/strict'
import { internalResourceIds } from '../src/domain/resourceVisibility.ts'

test('only declared internals are hidden; business use takes precedence', () => {
  const works = [{stages:[{inputs:[{resourceId:'input'}],outputs:[
    {resourceId:'raw',role:'INTERNAL'}, {resourceId:'shared',role:'INTERNAL'},
    {resourceId:'shared',role:'SUPPORTING'}, {resourceId:'legacy'},
  ]}]}]
  assert.deepEqual([...internalResourceIds(['old-raw','input','legacy'],works)].sort(), ['old-raw','raw'])
})
test('unknown files are never inferred to be internal', () => {
  assert.equal(internalResourceIds().size, 0)
})
