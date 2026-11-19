import { marked } from 'marked'
import DOMPurify from 'dompurify'

export function isNativeDocument(name: string, format = '') {
  return /\.(md|markdown)$/i.test(name) || ['markdown', 'md', 'text/markdown'].includes(format.toLowerCase())
}

// Renderers are independent of storage and versioning; additional text formats can register here.
const renderers: Record<string, (source: string) => string> = {
  markdown: source => marked.parse(source, { async: false, gfm: true }) as string,
}
export function renderNativeDocument(source: string, format = 'markdown', allowImages = false) {
  const render = renderers[format]
  if (!render) throw new Error('不支持的文档格式')
  return DOMPurify.sanitize(render(source), {
    ALLOWED_TAGS: ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'del', 'blockquote', 'ul', 'ol', 'li', 'pre', 'code', 'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'details', 'summary', ...(allowImages ? ['img'] : [])],
    ALLOWED_ATTR: ['href', 'title', 'start', 'colspan', 'rowspan', ...(allowImages ? ['src', 'alt'] : [])],
  })
}

export function linkResourceReferences(html: string, resources: Array<{ id: string; name: string }>) {
  const names = new Map(resources.map(r => [r.id, r.name]))
  const root = document.createElement('div')
  root.innerHTML = html
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const nodes: Text[] = []
  while (walker.nextNode()) nodes.push(walker.currentNode as Text)
  for (const node of nodes) {
    if (node.parentElement?.closest('a,pre')) continue
    const matches = [...node.data.matchAll(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi)].filter(m => names.has(m[0]))
    if (!matches.length) continue
    const fragment = document.createDocumentFragment()
    let start = 0
    for (const match of matches) {
      fragment.append(node.data.slice(start, match.index))
      const link = document.createElement('a'); link.href = './' + match[0]; link.textContent = names.get(match[0])!
      fragment.append(link); start = match.index! + match[0].length
    }
    fragment.append(node.data.slice(start)); node.replaceWith(fragment)
  }
  return root.innerHTML
}
