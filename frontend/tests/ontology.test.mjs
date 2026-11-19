import assert from 'node:assert/strict'
import test from 'node:test'
import { buildBlockers, ontologyBundle, publishableDigest, publicationAction } from '../src/domain/ontology.ts'

const state = { spec: { completion:'complete', scope:{goal:'核对',in_scope:['采购']}, gaps:[] },
  issues:[], artifacts:{flows:{status:'approved'},candidates:{status:'approved',content:[{kind:'Object'}]}} }
const target = {configured:true, target_id:'catalog-a'}
const publication = status => ({status, target_id:'catalog-a', model_digest:'abc'})

test('offline build uses scoped completed ledger and current reviews', () => {
  assert.deepEqual(buildBlockers(state), [])
  assert.equal(buildBlockers({...state, spec:{completion:'draft',gaps:[{status:'open',blocking:true}]},
    artifacts:{flows:{status:'stale'}, candidates:{status:'pending'}}}).length, 5)
  assert.equal(ontologyBundle({digest:'abc'}), null)
})
test('local exports stay separate from the quality-gated publication digest', () => {
  const bundle = {digest:'abc', quality:{publication_ready:true}}
  assert.equal(publishableDigest('ready', bundle, []), 'abc')
  assert.equal(publishableDigest('stale', bundle, []), undefined)
  assert.equal(publishableDigest('ready', bundle, ['缺口']), undefined)
  assert.equal(publishableDigest('ready', {digest:'abc'}, []), undefined)
  assert.equal(publishableDigest('ready', {...bundle,quality:{publication_ready:false}}, []), undefined)
})
test('write retries bind the exact active digest and configured target', () => {
  for (const status of ['FAILED','ABSENT']) {
    assert.equal(publicationAction(publication(status), target, 'abc'), 'retry')
    assert.equal(publicationAction(publication(status), target, 'changed'), null)
    assert.equal(publicationAction(publication(status), {...target,target_id:'catalog-b'}, 'abc'), null)
  }
  assert.equal(publicationAction(publication('VERIFIED'), target, 'abc'), null)
})
test('uncertain and active publications can verify the historical model without a fresh build', () => {
  for (const status of ['PUBLISHING','UNCERTAIN']) {
    assert.equal(publicationAction(publication(status), target), 'verify')
    assert.equal(publicationAction(publication(status), null), null)
    assert.equal(publicationAction(publication(status), {configured:false}), null)
  }
})
