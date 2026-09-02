<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ArrowUp,
  Activity,
  Bot,
  Check,
  GripVertical,
  LoaderCircle,
  RotateCcw,
  ShieldCheck,
  Sparkles,
  X,
} from 'lucide-vue-next'
import { useAssistantStore } from '../stores/assistant'
import type { Project } from '../api/client'

const props = defineProps<{ project: Project | null }>()
const assistant = useAssistantStore()
const position = ref({ x: 24, y: 24 })
const moving = ref(false)
let moveOffset = { x: 0, y: 0 }

const floatingStyle = computed(() => ({ left: `${position.value.x}px`, top: `${position.value.y}px` }))

function panelSize() {
  return assistant.open
    ? { width: Math.min(390, window.innerWidth - 28), height: Math.min(720, window.innerHeight - 32) }
    : { width: 52, height: 52 }
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
  return props.project ? `${props.project.name} / ${assistant.contextTitle}` : '个人首页'
})

function send() {
  if (props.project) assistant.send(props.project.id)
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
                <span v-for="skill in (step.arguments.agent_skills as string[] || [])" :key="skill" class="skill-label">{{ skill }}</span>
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

        <div v-if="assistant.run?.status === 'SUCCEEDED' && assistant.run.result?.createdProjectId" class="result-actions">
          <button class="secondary-button" type="button" @click="assistant.rollback">
            <RotateCcw :size="16" /> 撤销本次操作
          </button>
        </div>

        <p v-if="assistant.error" class="assistant-error">{{ assistant.error }}</p>

        <div v-if="!assistant.plan" class="assistant-empty">
          <span><Sparkles :size="22" /></span>
          <strong>处理工作台上的任何任务</strong>
          <p>可以查看内容、整理资料、处理数据、编排工作流、生成指定成果，也可以直接操作当前工作区域。</p>
          <div class="prompt-suggestions">
            <button type="button" @click="assistant.input = '介绍当前项目里有哪些内容，并建议下一步'">了解项目</button>
            <button type="button" @click="assistant.input = '打开当前项目的工作流'">打开工作流</button>
            <button type="button" @click="assistant.input = '把当前选中的内容加入工作流'">加入工作流</button>
          </div>
        </div>
      </div>

      <footer class="assistant-composer">
        <textarea
          v-model="assistant.input"
          rows="2"
          placeholder="告诉我你想完成什么"
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
