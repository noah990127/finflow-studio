import test from 'node:test'
import assert from 'node:assert/strict'
import { createComposerKeyboard } from '../src/domain/composerKeyboard.ts'

function fixture() {
  let sent = 0
  let prevented = 0
  const keyboard = createComposerKeyboard(() => sent++)
  const press = (overrides = {}) => keyboard.keydown({ key: 'Enter', keyCode: 13, isComposing: false,
    shiftKey: false, repeat: false, preventDefault: () => prevented++, ...overrides })
  return { keyboard, press, counts: () => ({ sent, prevented }) }
}

test('IME Enter confirms text without sending or interfering with the input method', () => {
  const f = fixture()
  f.press({ isComposing: true })
  f.keyboard.compositionStart()
  f.press()
  assert.deepEqual(f.counts(), { sent: 0, prevented: 0 })
})

test('WebKit compositionend before Enter does not send, but the next deliberate Enter does', () => {
  const f = fixture()
  f.keyboard.compositionStart()
  f.keyboard.compositionEnd()
  f.press({ keyCode: 229, isComposing: false })
  assert.deepEqual(f.counts(), { sent: 0, prevented: 0 })
  f.press()
  assert.deepEqual(f.counts(), { sent: 1, prevented: 1 })
})

test('IME keydown before compositionstart is ignored', () => {
  const f = fixture()
  f.press({ keyCode: 229 })
  assert.deepEqual(f.counts(), { sent: 0, prevented: 0 })
})

test('Shift+Enter and ordinary typing retain their default behavior', () => {
  const f = fixture()
  f.press({ shiftKey: true })
  f.press({ key: 'a', keyCode: 65 })
  assert.deepEqual(f.counts(), { sent: 0, prevented: 0 })
})

test('holding Enter cannot send repeatedly', () => {
  const f = fixture()
  f.press()
  f.press({ repeat: true })
  assert.deepEqual(f.counts(), { sent: 1, prevented: 2 })
})

test('leaving the composer clears stale composition state', () => {
  const f = fixture()
  f.keyboard.compositionStart()
  f.keyboard.blur()
  f.press()
  assert.deepEqual(f.counts(), { sent: 1, prevented: 1 })
})
