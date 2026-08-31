<script setup lang="ts">
import { ExternalLink, X } from 'lucide-vue-next'

defineProps<{ url: string; title: string }>()
defineEmits<{ close: [] }>()
</script>

<template>
  <section class="data-formulator-dialog" role="dialog" aria-modal="true" aria-label="财务报告设计器">
    <header>
      <div><span>财务报告</span><strong>{{ title || 'Data Formulator 报告设计器' }}</strong></div>
      <nav>
        <a class="secondary-button" :href="url" target="_blank" rel="noopener noreferrer"><ExternalLink :size="15"/>独立窗口打开</a>
        <button class="icon-button" type="button" title="关闭" @click="$emit('close')"><X :size="18"/></button>
      </nav>
    </header>
    <iframe :src="url" title="Data Formulator 财务报告设计器" allow="clipboard-read; clipboard-write"></iframe>
  </section>
</template>
