<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowRight, BookOpen, Database, FileOutput, Sheet, Sparkles } from 'lucide-vue-next'
import type { DataConnection, Deliverable, ExtractJob, FileResource, Project } from '../api/client'
import { api } from '../api/client'
import { useAssistantStore } from '../stores/assistant'
const props = defineProps<{ project: Project | null; loading: boolean; error: string }>()
defineEmits<{ retry: []; navigate: [id: string] }>()
const assistant = useAssistantStore(); const connections = ref<DataConnection[]>([]); const jobs = ref<ExtractJob[]>([]); const files = ref<FileResource[]>([]); const outputs = ref<Deliverable[]>([]); const notice = ref('')
const sheets = computed(() => files.value.filter((f) => /\.(xlsx|xlsm|csv|tsv)$/i.test(f.name)))
async function load() { if (!props.project) return; notice.value = ''; try { [connections.value, jobs.value, files.value, outputs.value] = await Promise.all([api.listConnections(props.project.id), api.listExtracts(props.project.id), api.listFiles(props.project.id), api.listDeliverables(props.project.id)]) } catch (e) { notice.value = e instanceof Error ? e.message : '内容加载失败' } }
watch(() => props.project?.id, load); onMounted(load)
</script>
<template>
  <main class="workspace">
    <header class="page-header"><div><p class="eyebrow">个人工作空间</p><h1>{{ project?.name ?? (error ? '连接未完成' : '正在打开工作空间') }}</h1><p>{{ project?.description || error || '把数据、资料和输出放在一个安静的工作空间里' }}</p></div></header>
    <section class="focus-banner"><div class="focus-icon"><Sparkles :size="22" /></div><div><span>用一句话继续工作</span><strong>让 AI 帮你整理数据、阅读资料并形成输出</strong></div><button type="button" @click="assistant.openWithSuggestion('整理当前数据，结合资料生成一份分析汇报')">开始规划 <ArrowRight :size="16" /></button></section>
    <p v-if="notice" class="notice error">{{ notice }}</p>
    <section class="stats-band" aria-label="工作空间概况">
      <button class="stat-item" type="button" @click="$emit('navigate', 'data')"><Database :size="20" /><div><strong>{{ connections.length }}</strong><span>个数据连接</span></div><small>{{ jobs.filter(j => j.status === 'RUNNING').length }} 个抽取正在运行</small></button>
      <button class="stat-item" type="button" @click="$emit('navigate', 'sheets')"><Sheet :size="20" /><div><strong>{{ sheets.length }}</strong><span>份表格</span></div><small>公式与宏保持原文件版本</small></button>
      <button class="stat-item" type="button" @click="$emit('navigate', 'knowledge')"><BookOpen :size="20" /><div><strong>{{ files.length }}</strong><span>份资料</span></div><small>{{ files.filter(f => f.parseStatus === 'READY').length }} 份 Ref 可用</small></button>
      <button class="stat-item" type="button" @click="$emit('navigate', 'outputs')"><FileOutput :size="20" /><div><strong>{{ outputs.length }}</strong><span>个输出</span></div><small>PPT、Word 与 Mermaid</small></button>
    </section>
    <section class="content-band"><div class="section-title-row"><div><span>最近内容</span><h2>继续处理</h2></div></div>
      <div v-if="!jobs.length && !files.length && !outputs.length" class="empty-state"><strong>工作空间还是空的</strong><p>先添加一个数据连接或上传资料，AI 助手就能围绕这些内容工作。</p><button class="primary-button" type="button" @click="$emit('navigate', 'data')">添加数据</button></div>
      <div v-else class="resource-list">
        <button v-for="job in jobs.slice(0, 3)" :key="job.id" type="button" @click="$emit('navigate', 'data')"><span class="resource-icon"><Database :size="18" /></span><span><strong>{{ job.name }}</strong><small>{{ job.rowCount.toLocaleString() }} 行 · {{ job.status }}</small></span><ArrowRight :size="17" /></button>
        <button v-for="file in files.slice(0, 3)" :key="file.id" type="button" @click="$emit('navigate', 'knowledge')"><span class="resource-icon"><BookOpen :size="18" /></span><span><strong>{{ file.name }}</strong><small>第 {{ file.currentVersion }} 版 · {{ file.parseStatus }}</small></span><ArrowRight :size="17" /></button>
      </div>
    </section>
  </main>
</template>
