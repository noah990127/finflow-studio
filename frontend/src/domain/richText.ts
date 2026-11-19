import DOMPurify from 'dompurify'
import { marked } from 'marked'

const sanitizeOptions = {
  USE_PROFILES: { html: true },
  FORBID_TAGS: ['style', 'script', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'textarea', 'select'],
  FORBID_ATTR: ['style'],
}

DOMPurify.addHook('afterSanitizeAttributes', node => {
  if (node.nodeName !== 'A') return
  node.setAttribute('target', '_blank')
  node.setAttribute('rel', 'noopener noreferrer')
})

export function renderRichText(content: string): string {
  if (!content.trim()) return ''
  const html = marked.parse(content, { async: false, breaks: true, gfm: true })
  return DOMPurify.sanitize(html, sanitizeOptions)
}
