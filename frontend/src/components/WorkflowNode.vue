<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { BookOpen, CircleUserRound, Combine, Database, FileOutput, FileSpreadsheet, FileUp, Link, Sheet, Sparkles } from 'lucide-vue-next'

const props = defineProps<{ data: { label: string; nodeType: string; status?: string; issue?: boolean; flowRole?: string }; selected?: boolean }>()
const meta: Record<string, { caption: string; icon: typeof FileUp }> = {
  FILE_INPUT: { caption: '选择文件', icon: FileUp }, DATA_EXTRACT: { caption: '提取数据', icon: Database },
  LINK_INPUT: { caption: '使用链接', icon: Link },
  DATASET_INPUT: { caption: '使用数据', icon: FileSpreadsheet },
  DATA_TRANSFORM: { caption: '加工数据', icon: Combine },
  SPREADSHEET_TRANSFORM: { caption: '加工表格', icon: Sheet }, REF_SEARCH: { caption: '查找参考', icon: BookOpen },
  AI_ANALYSIS: { caption: '智能分析', icon: Sparkles }, DELIVERABLE: { caption: '生成成果', icon: FileOutput },
  REVIEW: { caption: '人工确认', icon: CircleUserRound },
}
const item = computed(() => meta[props.data.nodeType] ?? meta.FILE_INPUT)
</script>
<template>
  <div class="flow-node" :class="[{ selected, issue: data.issue, 'flow-source': data.flowRole === 'source', 'flow-target': data.flowRole === 'target' }, `run-${(data.status || '').toLowerCase()}`]">
    <Handle type="target" :position="Position.Left" />
    <span class="flow-node-icon"><component :is="item.icon" :size="17" /></span>
    <span class="flow-node-copy"><small>{{ item.caption }}</small><strong>{{ data.label }}</strong></span>
    <span v-if="data.status" class="flow-node-status" :title="data.status"></span>
    <Handle type="source" :position="Position.Right" />
  </div>
</template>
