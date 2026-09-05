import test from 'node:test'
import assert from 'node:assert/strict'
import { businessError, businessText, workProgress } from '../src/domain/workProgress.ts'

test('shows business actions instead of tool identifiers', () => {
  assert.equal(workProgress({type:'agent.tool_call',toolName:'knowledge.extract_table',message:'knowledge.extract_table'}).title, '正在从资料中提取表格')
  assert.equal(workProgress({type:'agent.executing',toolName:'workflow.run',message:'workflow.run workflow_id=123'}).title, '正在运行工作流')
  assert.equal(workProgress({type:'agent.skill_loading',message:'加载 Skill financial-analysis'}).title, '准备处理方法')
  assert.doesNotMatch(businessText('knowledge.extract_table'), /extract_table/)
})
test('preserves concrete readable facts', () => {
  assert.equal(businessText('已找到 3 份年度报告，正在核对发布时间。'), '已找到 3 份年度报告，正在核对发布时间。')
  assert.equal(workProgress({type:'agent.generating',message:'正在整理收入与成本的对比结果'}).detail, '正在整理收入与成本的对比结果')
})
test('does not announce success when observation failed', () => {
  assert.equal(workProgress({type:'agent.observation',status:'FAILED',error:'资料不存在'}).title, '这一步未完成')
  assert.equal(workProgress({type:'agent.observation',message:'工具没有完成，Agent 正在调整方案'}).title, '这一步未完成')
  assert.equal(workProgress({type:'agent.cancelled'}).title, '已停止')
  assert.equal(workProgress({type:'agent.waiting_confirmation'}).title, '需要你确认')
})
test('explains service and data errors without raw URLs or status codes', () => {
  assert.doesNotMatch(businessError('503 Service Unavailable from POST http://localhost:8001/v1/agent/plan'), /503|localhost|POST/)
  assert.match(businessError('422 Unprocessable Content'), /内容不符合要求/)
  assert.match(businessError('资料不存在'), /没有找到/)
})
test('thinking and recovery are public progress summaries', () => {
  assert.equal(workProgress({type:'agent.thinking_summary',message:'internal private deliberation'}).detail, '正在检查已有结果，确定接下来需要完成的工作。')
  assert.match(workProgress({type:'agent.retrying'}).detail, /调整处理方式/)
  assert.equal(workProgress({type:'agent.completed',message:'报告已保存'}).title, '处理完成')
})
