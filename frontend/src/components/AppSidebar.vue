<script setup lang="ts">
import { BookOpen, Database, FileOutput, Home, Settings, Sheet, Workflow } from 'lucide-vue-next'
defineProps<{ active: string }>()
const emit = defineEmits<{ select: [id: string] }>()
const items = [
  { id: 'home', label: '首页', icon: Home }, { id: 'data', label: '数据', icon: Database },
  { id: 'workflows', label: '工作流', icon: Workflow },
  { id: 'sheets', label: '表格', icon: Sheet }, { id: 'knowledge', label: '资料', icon: BookOpen },
  { id: 'outputs', label: '输出', icon: FileOutput },
]
</script>
<template>
  <aside class="sidebar">
    <div class="brand"><div class="brand-mark">F</div><div><strong>FinFlow Studio</strong><span>个人工作空间</span></div></div>
    <nav class="nav-list" aria-label="主导航"><button v-for="item in items" :key="item.id" class="nav-item" :class="{ active: active === item.id }" type="button" :aria-label="item.label" @click="emit('select', item.id)"><component :is="item.icon" :size="18" /><span>{{ item.label }}</span></button></nav>
    <div class="sidebar-bottom"><button class="nav-item" type="button" aria-label="设置" @click="emit('select', 'settings')"><Settings :size="18" /><span>设置</span></button><div class="space-status"><span>当前空间</span><strong>我的工作空间</strong></div></div>
  </aside>
</template>
