<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ArrowUp,
  Activity,
  Bot,
  Check,
  ChevronRight,
  FileUp,
  GripVertical,
  LoaderCircle,
  Paperclip,
  RotateCcw,
  ShieldCheck,
  Sparkles,
  X,
} from 'lucide-vue-next'
import { useAssistantStore } from '../stores/assistant'
import type { Project } from '../api/client'

const props = defineProps<{ project: Project | null }>()
const assistant = useAssistantStore()
const fileInput = ref<HTMLInputElement | null>(null)
const dragging = ref(false)
const position = ref({ x: 24, y: 24 })
const moving = ref(false)
let moveOffset = { x: 0, y: 0 }

const floatingStyle = computed(() => ({ left: `${position.value.x}px`, top: `${position.value.y}px` }))

function panelSize() {
  return assistant.open
    ? { width: Math.min(390, window.innerWidth - 28), height: Math.min(720, window.innerHeight - 32) }
    : { width: Math.min(390, window.innerWidth - 32), height: 62 }
}
function clampPosition() {
  const size = panelSize()
  position.value = {
    x: Math.max(8, Math.min(position.value.x, window.innerWidth - size.width - 8)),
    y: Math.max(8, Math.min(position.value.y, window.innerHeight - size.height - 8)),
  }
  localStorage.setItem('finflow-assistant-position', JSON.stringify(position.value))
}
function startMove(event: PointerEvent) {
  moving.value = true
  moveOffset = { x: event.clientX - position.value.x, y: event.clientY - position.value.y }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stopMove, { once: true })
}
function move(event: PointerEvent) {
  if (!moving.value) return
  position.value = { x: event.clientX - moveOffset.x, y: event.clientY - moveOffset.y }
  clampPosition()
}
function stopMove() {
  moving.value = false
  window.removeEventListener('pointermove', move)
  clampPosition()
}

const contextLabel = computed(() => {
  if (assistant.selection) return `已选：${assistant.selection.range.join('、')}`
  return props.project ? `${props.project.name} / 项目工作台` : '个人首页'
})

function send() {
  if (props.project) assistant.send(props.project.id)
}
async function addFiles(items: FileList | File[]) {
  if (!props.project) return
  await assistant.attachFiles(props.project.id, Array.from(items))
}
function onDrop(event: DragEvent) {
  dragging.value = false
  if (event.dataTransfer?.files.length) addFiles(event.dataTransfer.files)
}
onMounted(() => {
  try {
    const saved = JSON.parse(localStorage.getItem('finflow-assistant-position') ?? 'null')
    if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) position.value = saved
    else position.value = { x: window.innerWidth - panelSize().width - 28, y: window.innerHeight - 86 }
  } catch { position.value = { x: window.innerWidth - panelSize().width - 28, y: window.innerHeight - 86 } }
  clampPosition(); window.addEventListener('resize', clampPosition)
})
onBeforeUnmount(() => { window.removeEventListener('resize', clampPosition); window.removeEventListener('pointermove', move) })
watch(() => assistant.open, async () => { await nextTick(); clampPosition() })
</script>

<template>
  <div
    v-if="!assistant.open"
    class="assistant-launcher"
    :style="floatingStyle"
    role="button"
    tabindex="0"
    @click="assistant.open = true"
    @keydown.enter="assistant.open = true"
  >
    <span class="assistant-icon"><Sparkles :size="18" /></span>
    <span>
      <strong>AI 助手</strong>
      <small>告诉我你想完成什么</small>
    </span>
    <button class="assistant-drag-handle" type="button" title="移动助手" @click.stop @pointerdown.stop.prevent="startMove"><GripVertical :size="17" /></button>
  </div>

  <div v-if="assistant.open" class="assistant-side-host" :style="floatingStyle">
    <aside class="assistant-drawer" aria-label="AI 助手任务面板">
      <header class="assistant-header" :class="{ moving }" @pointerdown="startMove">
        <div>
          <div class="assistant-title"><Bot :size="20" /> AI 助手</div>
          <p>{{ contextLabel }}</p>
        </div>
        <div class="assistant-header-actions"><GripVertical :size="17"/><button class="icon-button" type="button" title="关闭" @pointerdown.stop @click="assistant.open = false"><X :size="18" /></button></div>
      </header>

      <div class="assistant-body">
        <section class="assistant-files" :class="{ dragging }" @dragenter.prevent="dragging = true" @dragover.prevent @dragleave.prevent="dragging = false" @drop.prevent="onDrop">
          <FileUp :size="18"/><div><strong>把文件放进来分析</strong><p>支持表格、PDF、Word、PPT、图片和文本资料</p></div>
          <button class="icon-button" type="button" title="选择文件" :disabled="!project || assistant.busy" @click="fileInput?.click()"><Paperclip :size="16"/></button>
          <input ref="fileInput" hidden multiple type="file" @change="addFiles(($event.target as HTMLInputElement).files || [])">
        </section>
        <div v-if="assistant.attachments.length" class="assistant-attachments"><span v-for="item in assistant.attachments" :key="item.id">{{ item.name }}<button type="button" title="移除" @click="assistant.removeAttachment(item.id)"><X :size="12"/></button></span></div>
        <div v-if="assistant.assistantMessage" class="assistant-summary">
          <Sparkles :size="16" />
          <p>{{ assistant.assistantMessage }}</p>
        </div>

        <section v-if="assistant.progressLabel" class="assistant-live-progress">
          <header>
            <div><Activity :size="16"/><strong>当前处理进展</strong></div>
            <span>{{ assistant.progress }}%</span>
          </header>
          <div class="assistant-progress-track"><i :style="{ width: `${assistant.progress}%` }"></i></div>
          <p><LoaderCircle v-if="assistant.streaming" :size="14" class="assistant-spinner"/>{{ assistant.progressLabel }}</p>
          <div v-if="assistant.streamLines.length" class="assistant-stream-output" aria-live="polite">
            <span v-for="(line, index) in assistant.streamLines" :key="`${index}-${line}`">{{ line }}</span>
          </div>
        </section>

        <section v-if="assistant.plan" class="plan-section">
          <div class="section-heading">
            <div>
              <span>执行计划</span>
              <strong>我准备这样完成</strong>
            </div>
            <span class="plan-version">v{{ assistant.plan.version }}</span>
          </div>

          <div class="plan-steps">
            <div v-for="step in assistant.plan.steps" :key="step.id" class="plan-step">
              <span class="step-number">{{ step.order }}</span>
              <div>
                <strong>{{ step.title }}</strong>
                <p>{{ step.description }}</p>
                <span v-if="step.requiresConfirmation" class="change-label">会创建新版本</span>
                <span v-else class="read-label">只读取或生成草稿</span>
              </div>
            </div>
          </div>

          <div v-if="assistant.needsConfirmation && !assistant.run" class="confirm-box">
            <ShieldCheck :size="18" />
            <div>
              <strong>执行前确认</strong>
              <p>原始内容不会被覆盖，结果会保存为新版本。</p>
            </div>
          </div>

          <button
            v-if="assistant.needsConfirmation && !assistant.run"
            class="primary-button full-width"
            type="button"
            :disabled="assistant.busy"
            @click="assistant.confirm"
          >
            <Check :size="17" />
            {{ assistant.busy ? '正在开始…' : '确认并开始' }}
          </button>
        </section>

        <section v-if="assistant.timeline.length" class="timeline-section">
          <div v-for="item in assistant.timeline" :key="item.id" class="timeline-item">
            <span class="timeline-dot" :class="item.tone"></span>
            <div><strong>{{ item.title }}</strong><p>{{ item.detail }}</p></div>
          </div>
        </section>

        <div v-if="assistant.run?.status === 'SUCCEEDED'" class="result-actions">
          <button class="secondary-button" type="button" @click="assistant.rollback">
            <RotateCcw :size="16" /> 撤销本次操作
          </button>
          <button class="text-button" type="button">查看结果</button>
        </div>

        <p v-if="assistant.error" class="assistant-error">{{ assistant.error }}</p>

        <div v-if="!assistant.plan" class="assistant-empty">
          <span><Sparkles :size="22" /></span>
          <strong>从当前工作开始</strong>
          <p>我能帮你整理数据、检查表格、阅读资料、编排工作流、形成分析并生成输出。</p>
          <div class="prompt-suggestions">
            <button type="button" @click="assistant.input = '检查当前数据并给出整理建议'">检查数据</button>
            <button type="button" @click="assistant.input = '结合项目资料生成一份复盘汇报'">生成汇报</button>
          </div>
        </div>
      </div>

      <footer class="assistant-composer">
        <textarea
          v-model="assistant.input"
          rows="2"
          placeholder="描述你想完成的工作"
          @keydown.meta.enter.prevent="send"
          @keydown.ctrl.enter.prevent="send"
        ></textarea>
        <button class="send-button" type="button" title="发送" :disabled="assistant.busy" @click="send">
          <ArrowUp :size="18" />
        </button>
        <p>涉及修改和导出时，我会先让你确认</p>
      </footer>
    </aside>
  </div>
</template>
