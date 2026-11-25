<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { Play, LoaderCircle } from 'lucide-vue-next'
import { api } from '../api/client'
import { browserStates } from '../domain/browserHandoff'

const props = defineProps<{ sessionId: string; locked: boolean; waiting?: boolean }>()
const emit = defineEmits<{ continue: [sessionId: string, browserSession: string] }>()
const connections = ref<Awaited<ReturnType<typeof api.browserConnections>>>([])
const error = ref('')
const busy = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
let fetching = false
let generation = 0
async function refresh() {
  if (!props.sessionId || fetching || busy.value) return
  const version = generation
  const conversation = props.sessionId
  fetching = true
  try {
    const events = await api.listAssistantEvents(conversation)
    const states = browserStates(events)
    const active = await api.browserConnections()
    if (version === generation) {
      connections.value = active.filter(item => states.has(item.session) && (item.status === 'HUMAN_CONTROL'
        || (props.waiting && item.status === 'AGENT_CONTROL' && states.get(item.session) === 'HUMAN_CONTROL')))
      error.value = ''
    }
  } catch { if (version === generation) error.value = '无法读取人工操作状态，请重试' }
  finally { fetching = false }
}
async function complete(id: string) {
  if (busy.value || props.locked) return
  const conversation = props.sessionId
  busy.value = true; error.value = ''
  try {
    const current = connections.value.find(item => item.session === id)
    if (!current) throw new Error('网站连接已变化，请刷新状态后重试')
    if (current.status === 'HUMAN_CONTROL') await api.browserControl(id, 'resume')
    if (conversation !== props.sessionId) return
    connections.value = connections.value.filter(item => item.session !== id)
    emit('continue', conversation, id)
  } catch (e) { error.value = e instanceof Error ? e.message : '交还控制失败，请重试' }
  finally { busy.value = false }
}
watch(() => props.sessionId, () => { generation++; connections.value = []; error.value = ''; void refresh() })
onMounted(() => { void refresh(); timer = setInterval(refresh, 4000) })
onUnmounted(() => { generation++; if (timer) clearInterval(timer) })
</script>

<template>
  <section v-if="connections.length || error" class="task-handoff" aria-label="需要你操作网站">
    <div v-for="item in connections" :key="item.session" class="handoff-row">
      <div><strong>{{ item.status === 'HUMAN_CONTROL' ? '请完成网站中的操作' : '控制权已交还，可以继续任务' }}</strong><span>{{ item.origin.replace(/^https?:\/\//, '') }} · 完成后继续当前任务</span></div>
      <button class="primary-button" :disabled="busy || locked" @click="complete(item.session)"><LoaderCircle v-if="busy" :size="14" /><Play v-else :size="14" />{{ busy ? '正在继续…' : '我已完成，继续执行' }}</button>
    </div>
    <p v-if="error" role="alert">{{ error }} <button class="text-button" @click="refresh">重试</button></p>
  </section>
</template>

<style scoped>
.task-handoff { border-top: 1px solid #dce4df; padding: 10px 0; }
.handoff-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.handoff-row > div { flex: 1 1 150px; min-width: 0; }
strong { display: block; font-size: 13px; font-weight: 500; }
span { display: block; font-size: 12px; color: #68736c; overflow-wrap: anywhere; margin-top: 4px; }
button { display: inline-flex; align-items: center; gap: 5px; white-space: normal; }
p { font-size: 12px; color: #b42318; margin: 6px 0; }
</style>
