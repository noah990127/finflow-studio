<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { Download, LoaderCircle, Play, ExternalLink } from 'lucide-vue-next'
import { api } from '../api/client'
import AssistantRichText from './AssistantRichText.vue'
import { browserInteraction } from '../domain/browserInteraction'
import { procedureLabel } from '../domain/procedureStatus'

type Tasks = Awaited<ReturnType<typeof api.browserAgentTasks>>
const props = defineProps<{ sessionId: string; stepIds?: string[]; items?: Tasks }>()
const emit = defineEmits<{ present: [value: boolean]; active: [value: boolean]; updated: [value: Tasks] }>()
const tasks = ref<Awaited<ReturnType<typeof api.browserAgentTasks>>>([])
const error = ref('')
const busy = ref('')
let timer: ReturnType<typeof setInterval> | undefined
let generation = 0
let fetching = false
const active = (state: string) => ['submitted', 'working', 'input-required', 'auth-required'].includes(state)
// Unfinished tasks keep their handoff controls; finished answers belong only to their turn.
const visibleTasks = computed(() => props.items ?? tasks.value.filter(task =>
  active(task.status.state) || !!task.stepId && props.stepIds?.includes(task.stepId)))
watch(visibleTasks, value => {
  emit('present', value.length > 0)
  emit('active', value.some(task => active(task.status.state)))
}, { immediate: true })
watch(tasks, value => emit('updated', value))
async function refresh() {
  if (props.items !== undefined || !props.sessionId || fetching) return
  const version = generation
  fetching = true
  try {
    const result = await api.browserAgentTasks(props.sessionId)
    if (version === generation) { tasks.value = result; error.value = '' }
  } catch (e) {
    if (version === generation) error.value = e instanceof Error ? e.message : '无法读取浏览器任务状态'
  } finally {
    fetching = false
    if (version !== generation) void refresh()
  }
}
async function control(id: string, action: 'resume' | 'approve' | 'cancel' | 'focus', promptId?: number) {
  if (busy.value) return
  busy.value = id
  const version = generation
  try { await api.browserAgentControl(id, action, promptId); if (version === generation) await refresh() }
  catch (e) { if (version === generation) error.value = e instanceof Error ? e.message : '操作失败，请重试' }
  finally { busy.value = '' }
}
watch(() => props.sessionId, () => { generation++; tasks.value = []; error.value = ''; emit('present', false); void refresh() })
onMounted(() => { if (props.items === undefined) { void refresh(); timer = setInterval(refresh, 3000) } })
onUnmounted(() => { generation++; if (timer) clearInterval(timer) })
</script>

<template>
  <section v-if="visibleTasks.length || error" class="browser-tasks" aria-label="浏览器任务">
    <div v-for="task in visibleTasks" :key="task.id" class="browser-task">
      <LoaderCircle v-if="['working', 'submitted'].includes(task.status.state)" class="spinning" :size="14" />
      <span>{{ task.status.state === 'completed' ? '网页操作已完成' : task.status.state === 'input-required' ? '需要你完成网页操作' : task.status.state === 'canceled' ? '执行已停止' : task.status.state === 'failed' ? '执行未完成' : '正在处理网页操作' }}</span>
      <details v-if="task.status.state === 'completed'" class="procedure-state">
        <summary>{{ procedureLabel(task.procedureState || task.metadata?.procedureState) }}</summary>
        <div>{{ task.procedureIssue || task.procedureError || task.metadata?.procedureError || '已保存固定工具与输入绑定；不会为验证而自动重复提交。' }}</div>
        <a v-for="file in task.files?.filter(file => file.name.startsWith('browser-procedure')) || []" :key="file.url" :href="file.url" :download="file.name">查看技术实现</a>
      </details>
      <a v-for="file in task.files?.filter(file => !file.name.startsWith('browser-procedure')) || []" :key="file.url" :href="file.url" :download="file.name" :title="file.name" class="download"><Download :size="14" />{{ file.name }}</a>
      <button v-if="task.status.state === 'input-required'" class="continue-action" :disabled="!!busy" @click="control(task.id, browserInteraction(task.metadata?.interaction?.kind).action, task.metadata?.promptId)"><Play :size="14" />{{ browserInteraction(task.metadata?.interaction?.kind).label }}</button>
      <button v-if="task.status.state === 'input-required' && task.metadata?.canFocus" class="continue-action" :disabled="!!busy" @click="control(task.id, 'focus', task.metadata?.promptId)"><ExternalLink :size="14" />打开操作窗口</button>
      <AssistantRichText v-if="task.status.message" class="task-reply" :content="task.status.message.parts.map(part => part.text || '').join('')" />
    </div>
    <p v-if="error" role="alert">{{ error }} <button class="text-button" @click="refresh">重试</button></p>
  </section>
</template>

<style scoped>
.browser-tasks { min-width: 0; padding: 4px 0; }
.browser-task { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 12px; padding: 4px 0; }
.browser-task > span { flex: 1 1 140px; min-width: 0; overflow-wrap: anywhere; max-height: 96px; overflow: auto; }
button { display: inline-flex; align-items: center; gap: 4px; }
.continue-action { flex: 0 0 auto; width: auto; height: 28px; min-height: 28px; padding: 0 8px; border: 1px solid var(--border, #dce4df); border-radius: 4px; background: transparent; color: var(--text-body, #334155); font-size: 12px; cursor: pointer; order: 2; }
.continue-action:disabled { opacity: .5; cursor: wait; }
.task-reply { flex-basis: 100%; min-width: 0; overflow-wrap: anywhere; line-height: 1.6; }
.browser-task > .icon-button { order: 2; }
.download { display: inline-flex; align-items: center; gap: 4px; overflow-wrap: anywhere; max-width: 100%; }
.procedure-state { min-width: 0; max-width: 100%; font-size: 12px; color: var(--text-muted, #667085); }
.procedure-state summary { cursor: pointer; overflow-wrap: anywhere; }
.procedure-state > div { max-width: 360px; padding: 6px 0; overflow-wrap: anywhere; }
p { color: #b42318; font-size: 12px; overflow-wrap: anywhere; }
.spinning { animation: spin 1s linear infinite; flex-shrink: 0; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spinning { animation: none; } }
</style>
