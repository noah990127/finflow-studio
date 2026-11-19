import test from 'node:test'
import assert from 'node:assert/strict'
import {
  OFFICE_DIRECT_BYTES,
  OFFICE_RECOMMENDED_MAX_BYTES,
  spreadsheetAccessMode,
} from '../src/domain/spreadsheetAccess.ts'

test('routes spreadsheets by the safe online editing thresholds', () => {
  assert.equal(spreadsheetAccessMode('xlsx', OFFICE_DIRECT_BYTES), 'DIRECT')
  assert.equal(spreadsheetAccessMode('xlsx', OFFICE_DIRECT_BYTES + 1), 'CONFIRM')
  assert.equal(spreadsheetAccessMode('xlsx', 111 * 1024 * 1024), 'CONVERT')
  assert.equal(spreadsheetAccessMode('xlsm', OFFICE_RECOMMENDED_MAX_BYTES + 1), 'CONVERT')
  assert.equal(spreadsheetAccessMode('xlsx', 500 * 1024 * 1024), 'CONVERT')
})

test('does not restrict non-xlsx office files with spreadsheet thresholds', () => {
  assert.equal(spreadsheetAccessMode('pptx', 500 * 1024 * 1024), 'DIRECT')
})
