import test from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'

globalThis.__projectsTestApi = {}

const bundle = await build({
  stdin: {
    contents: `import { createPinia, setActivePinia } from 'pinia';
      import { useProjectsStore } from './src/stores/projects.ts';
      export function createStore() { setActivePinia(createPinia()); return useProjectsStore(); }`,
    resolveDir: fileURLToPath(new URL('..', import.meta.url)), loader: 'ts',
  },
  bundle: true, write: false, platform: 'node', format: 'esm',
  plugins: [{ name: 'mock-api', setup(builder) {
    builder.onLoad({filter: /src\/api\/client\.ts$/}, () => ({ contents: 'export const api = globalThis.__projectsTestApi;', loader: 'js' }))
  }}],
})

const { createStore } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`)
const api = globalThis.__projectsTestApi

function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

test('a slower project refresh cannot replace a newer project selection', async () => {
  const first = deferred()
  const second = deferred()
  let call = 0
  api.listProjects = () => (++call === 1 ? first.promise : second.promise)
  const state = createStore()
  state.current = {id:'initial-project'}

  const oldRefresh = state.refresh('old-project')
  const newRefresh = state.refresh('new-project')
  second.resolve([{id:'new-project', name:'新项目'}])
  await newRefresh
  first.resolve([{id:'old-project', name:'旧项目'}])
  await oldRefresh

  assert.equal(state.current.id, 'new-project')
  assert.deepEqual(state.projects.map(project => project.id), ['new-project'])
})
