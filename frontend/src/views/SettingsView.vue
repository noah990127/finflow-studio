<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Bot, CheckCircle2, Server } from 'lucide-vue-next'
import type { Project } from '../api/client'
defineProps<{ project: Project | null }>(); const status = ref<Record<string, unknown> | null>(null); const error = ref('')
const llm = computed(() => (status.value?.llm ?? {}) as { provider?: string; model?: string; configured?: boolean })
const llmDescription = computed(() => { const provider = ['codex', 'codex-cli'].includes(llm.value.provider ?? '') ? 'Codex' : llm.value.provider || '本地'; const model = llm.value.model ? ` · ${llm.value.model}` : ''; const mode = llm.value.configured ? ' · 已启用' : ' · 未配置，需先连接模型'; return `${provider}${model}${mode}` })
onMounted(async () => { try { const response = await fetch('/api/system/status'); if (!response.ok) throw new Error('服务状态暂时不可用'); status.value = await response.json() } catch (e) { error.value = e instanceof Error ? e.message : '无法读取状态' } })
</script>
<template><main class="workspace"><header class="page-header"><div><p class="eyebrow">设置</p><h1>运行状态</h1><p>当前为单用户个人空间，不需要登录。</p></div></header><p v-if="error" class="notice error">{{ error }}</p><section class="settings-list"><article><Server :size="21"/><div><strong>主服务</strong><p>项目、数据抽取、版本和输出管理</p></div><CheckCircle2 :size="18"/></article><article><Bot :size="21"/><div><strong>AI 与资料服务</strong><p>{{ status ? llmDescription : '正在读取模型网关状态' }}</p></div><span>{{ status ? '已连接' : '检查中' }}</span></article></section></main></template>
