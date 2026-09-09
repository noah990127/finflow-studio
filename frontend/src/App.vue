<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import ProjectWorkbench from './views/ProjectWorkbench.vue'
import LoginView from './views/LoginView.vue'
import { useProjectsStore } from './stores/projects'
import { api, type AuthUser } from './api/client'

const projects = useProjectsStore()
const user = ref<AuthUser | null>(null)
const resolvingSession = ref(true)

async function resolveSession() {
  try {
    user.value = await api.me()
    await projects.initialize()
  } catch {
    user.value = null
    projects.reset()
  } finally {
    resolvingSession.value = false
  }
}

function login(authenticatedUser: AuthUser) {
  user.value = authenticatedUser
  void projects.initialize()
}

async function logout() {
  try { await api.logout() } finally {
    user.value = null
    projects.reset()
  }
}

function unauthorized() {
  user.value = null
  projects.reset()
}

onMounted(() => {
  window.addEventListener('finflow:unauthorized', unauthorized)
  void resolveSession()
})
onBeforeUnmount(() => window.removeEventListener('finflow:unauthorized', unauthorized))
</script>
<template>
  <main v-if="resolvingSession" class="session-loading">正在打开工作台</main>
  <LoginView v-else-if="!user" @login="login" />
  <ProjectWorkbench v-else :project="projects.current" :loading="projects.loading" :error="projects.error" :account="user.username" @logout="logout" />
</template>
