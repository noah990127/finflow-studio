<script setup lang="ts">
import { computed } from 'vue'
import { renderRichText } from '../domain/richText'

const props = defineProps<{ content: string }>()
const html = computed(() => renderRichText(props.content))
</script>

<template>
  <div class="assistant-rich-text" v-html="html"></div>
</template>
