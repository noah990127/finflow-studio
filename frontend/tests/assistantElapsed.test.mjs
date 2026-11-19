import test from 'node:test'
import assert from 'node:assert/strict'
import { assistantElapsed } from '../src/domain/assistantElapsed.ts'

const createdAt = '2026-11-17T03:37:27.717Z'
const finishedAt = '2026-11-17T03:47:51.299Z'
const old = [
  {time:'2026-11-17T02:07:25Z',eventType:'agent.completed'},
  {time:'2026-11-17T02:07:26Z'},
]
test('old activity cannot freeze a running timer or inflate completed elapsed time', () => {
  assert.equal(assistantElapsed(Date.parse(createdAt)+65000,{createdAt,status:'RUNNING'},old),'已处理 1 分 5 秒')
  assert.equal(assistantElapsed(Date.now(),{createdAt,finishedAt,status:'SUCCEEDED'},old),'耗时 10 分 23 秒')
})
test('uses only latest request before a run exists', () => {
  const timeline=[...old,{time:createdAt,eventType:'assistant.request.received'}]
  assert.equal(assistantElapsed(Date.parse(createdAt)+2000,null,timeline),'已处理 2 秒')
})
test('freezes at current cancellation or confirmation but ignores stale ending timestamps', () => {
  const run={createdAt,status:'RUNNING'}
  assert.equal(assistantElapsed(Date.parse(createdAt)+10000,run,old,old[0].time),'已处理 10 秒')
  assert.equal(assistantElapsed(Date.now(),run,[],new Date(Date.parse(createdAt)+4000).toISOString()),'耗时 4 秒')
  assert.equal(assistantElapsed(Date.now(),{createdAt,status:'WAITING_CONFIRMATION'},[
    {time:new Date(Date.parse(createdAt)+5000).toISOString(),eventType:'agent.waiting_confirmation'}
  ],'',true),'等待前已执行 5 秒')
})
test('does not invent a start time from arbitrary local UI messages', () => {
  assert.equal(assistantElapsed(Date.now(),null,old),'刚刚开始')
})
