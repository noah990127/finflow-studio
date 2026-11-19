import test from 'node:test'
import assert from 'node:assert/strict'
import {extractionOffset, nextExtractionOffset} from '../src/domain/tacit.ts'

test('resume only from a checkpoint for this exact source version',()=>{
  const state = { extraction:{ file:{ version:'1',next_offset:8000 } } }
  assert.equal(extractionOffset(state,{source_id:'file',version:'1',read_status:'read'}),8000)
  assert.equal(extractionOffset(state,{source_id:'file',version:'2',read_status:'read'}),0)
  state.extraction.file.next_offset = null
  assert.equal(extractionOffset(state,{source_id:'file',version:'1'}),null)
})
test('stop if the extraction service did not advance its cursor',()=>{
  assert.equal(nextExtractionOffset({next_offset:100},10),100)
  assert.equal(nextExtractionOffset({next_offset:null},10),null)
  for(const next_offset of [undefined,10,-1,NaN,Infinity,'12']) {
    assert.throws(()=>nextExtractionOffset({next_offset},10))
  }
})

test('failed source clause marks only its linked draft entries for verification', async ()=>{
  const {entryIssueIndex}=await import('../src/domain/tacit.ts')
  const state={spec:{sources:[{source_id:'s',clauses:[{clause_id:'A'},{clause_id:'B'}]}],
    rules:[{rule_id:'linked',evidence:[{source_id:'s',clause_id:'A'}]},{rule_id:'other',evidence:[{source_id:'s',clause_id:'B'}]}]},
    issues:[{code:'fabricated_quote',path:'sources[0].clauses[0]',message:'引文未命中'}]}
  const issues = entryIssueIndex(state,'rules')
  assert.deepEqual([...issues.get('linked')],['引用依据 · 引文未命中'])
  assert.equal(issues.has('other'),false)
})
