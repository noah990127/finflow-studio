<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { BookOpen, Combine, Database, FileOutput, FileSpreadsheet, FileUp, Link, Sparkles } from 'lucide-vue-next'

const props = defineProps<{ data: { label: string; nodeType: string; status?: string; issue?: boolean; flowRole?: string; config?: Record<string, unknown> }; selected?: boolean }>()
const requirement = computed(() => {
  const config = props.data.config ?? {}
  return String(config.prompt ?? config.instruction ?? config.generationPrompt ?? config.requirements ?? '')
})
const meta: Record<string, { caption: string; icon: typeof FileUp }> = {
  FILE_INPUT: { caption: '选择文件', icon: FileUp }, DATA_EXTRACT: { caption: '提取数据', icon: Database },
  LINK_INPUT: { caption: '使用链接', icon: Link },
  DATASET_INPUT: { caption: '使用数据', icon: FileSpreadsheet },
  DATA_TRANSFORM: { caption: '加工数据', icon: Combine },
  REF_SEARCH: { caption: '查找参考', icon: BookOpen },
  AI_ANALYSIS: { caption: '智能分析', icon: Sparkles }, DELIVERABLE: { caption: '生成成果', icon: FileOutput },
}
const item = computed(() => meta[props.data.nodeType] ?? meta.FILE_INPUT)
</script>
<template>
  <div class="flow-node" :class="[{ selected, issue: data.issue, 'flow-source': data.flowRole === 'source', 'flow-target': data.flowRole === 'target' }, `run-${(data.status || '').toLowerCase()}`]">
    <Handle type="target" :position="Position.Left" />
    <span class="flow-node-icon"><component :is="item.icon" :size="17" /></span>
    <span class="flow-node-copy"><small>{{ item.caption }}</small><strong>{{ data.label }}</strong><span v-if="requirement" class="flow-node-requirement" :title="requirement">{{ requirement }}</span><span v-else-if="['AI_ANALYSIS', 'AGENT_TASK', 'DELIVERABLE'].includes(data.nodeType)" class="flow-node-requirement">尚未填写处理要求</span></span>
    <span v-if="data.status" class="flow-node-status" :title="data.status"></span>
    <Handle type="source" :position="Position.Right" />
  </div>
</template>
<style scoped>
.flow-node-requirement { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; color: #697781; font-size: 11px; line-height: 1.5; margin-top: 5px; overflow-wrap: anywhere; }
</style>
