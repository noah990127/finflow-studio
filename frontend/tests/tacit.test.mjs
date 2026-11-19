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

test('clause-only Office citations resolve their registered quote instead of reading position zero', async () => {
  const {citationPreviewRequest,matchesCitationPage} = await import('../src/domain/tacit.ts')
  const ref = {source_id:'workbook',clause_id:'row-1750',quote:'达标　通过'}
  assert.deepEqual(citationPreviewRequest(ref,'7'),{...ref,version:'7'})
  assert.equal(citationPreviewRequest({source_id:'workbook'},'7'),null)
  const page = {source_id:'workbook',version:'7',text:'达标 通过。',start:48255,end:48261,citation_start:48255,citation_end:48260}
  assert.ok(matchesCitationPage(ref,page,'7'))
  assert.equal(matchesCitationPage(ref,{...page,version:'6'},'7'),false)
})

test('positional hits must match the precise offset, original text and version', async () => {
  const {citationPreviewRequest,matchesCitationPage} = await import('../src/domain/tacit.ts')
  const hit = {source_id:'a',start:9,end:12,version:'3',text:'收💶款'}
  assert.deepEqual(citationPreviewRequest(hit),{source_id:'a',version:'3',offset:9})
  const page = {source_id:'a',version:'3',start:9,text:'收💶款以后审批',next_offset:null}
  assert.ok(matchesCitationPage(hit,page))
  assert.equal(matchesCitationPage(hit,{...page,text:'旧：收💶款以后审批'}),false)
  assert.equal(matchesCitationPage(hit,{...page,start:7}),false)
})
