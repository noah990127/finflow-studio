type ComposerKeyEvent = Pick<KeyboardEvent, 'key' | 'keyCode' | 'isComposing' | 'shiftKey' | 'repeat' | 'preventDefault'>

export function createComposerKeyboard(send: () => void) {
  let composing = false
  return {
    compositionStart() { composing = true },
    compositionEnd() { composing = false },
    blur() { composing = false },
    keydown(event: ComposerKeyEvent) {
      // WebKit can emit compositionend before the confirming Enter; keyCode 229 still identifies that IME event.
      if (composing || event.isComposing || event.keyCode === 229) return
      if (event.key !== 'Enter' || event.shiftKey) return
      event.preventDefault()
      if (!event.repeat) send()
    },
  }
}
