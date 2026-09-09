<script setup lang="ts">
import { ref } from 'vue'
import ProjectWorkbench from './views/ProjectWorkbench.vue'
import LoginView from './views/LoginView.vue'
import { useProjectsStore } from './stores/projects'

const SESSION_KEY = 'finflow.authenticated'
const projects = useProjectsStore()
const authenticated = ref(sessionStorage.getItem(SESSION_KEY) === 'true')

if (authenticated.value) void projects.initialize()

function login() {
  sessionStorage.setItem(SESSION_KEY, 'true')
  authenticated.value = true
  void projects.initialize()
}

function logout() {
  sessionStorage.removeItem(SESSION_KEY)
  authenticated.value = false
}
</script>
<template>
  <LoginView v-if="!authenticated" @login="login" />
  <ProjectWorkbench v-else :project="projects.current" :loading="projects.loading" :error="projects.error" @logout="logout" />
</template>
