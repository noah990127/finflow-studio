import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MAX_UPLOAD_FILE_BYTES,
  MAX_UPLOAD_FILES,
  MAX_UPLOAD_SELECTION_BYTES,
  uploadSelectionError,
} from '../src/domain/fileUpload.ts'

test('accepts a multi-file selection within all upload limits', () => {
  assert.equal(uploadSelectionError([
    { name: 'actual.csv', size: 120 * 1024 * 1024 },
    { name: 'forecast.xlsx', size: 80 * 1024 * 1024 },
  ]), '')
})

test('rejects too many files before uploading', () => {
  const files = Array.from({ length: MAX_UPLOAD_FILES + 1 }, (_, index) => ({ name: `${index}.csv`, size: 1 }))
  assert.match(uploadSelectionError(files), /一次最多上传 20 个文件/)
})

test('rejects an oversized individual file', () => {
  assert.match(uploadSelectionError([
    { name: 'detail.parquet', size: MAX_UPLOAD_FILE_BYTES + 1 },
  ]), /detail\.parquet.*500 MB/)
})

test('rejects a selection whose combined size is too large', () => {
  assert.match(uploadSelectionError([
    { name: 'a.csv', size: 400 * 1024 * 1024 },
    { name: 'b.csv', size: 400 * 1024 * 1024 },
    { name: 'c.csv', size: 300 * 1024 * 1024 },
  ]), /合计 1\.1 GB.*超过 1 GB/)
})
