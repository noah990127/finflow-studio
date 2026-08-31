<script setup lang="ts">
import { onMounted } from 'vue'
import ProjectWorkbench from './views/ProjectWorkbench.vue'
import { useProjectsStore } from './stores/projects'
const projects = useProjectsStore()
onMounted(() => projects.initialize())
</script>
<template>
  <ProjectWorkbench :project="projects.current" :loading="projects.loading" :error="projects.error" />
</template>
