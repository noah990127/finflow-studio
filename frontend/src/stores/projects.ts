import { defineStore } from 'pinia'
import { api, type Project } from '../api/client'

export const useProjectsStore = defineStore('projects', {
  state: () => ({
    projects: [] as Project[],
    current: null as Project | null,
    loading: false,
    error: '',
  }),
  actions: {
    async initialize() {
      this.loading = true
      this.error = ''
      try {
        this.projects = await api.listProjects()
        this.current = this.projects[0] ?? null
      } catch (error) {
        this.error = error instanceof Error ? error.message : '暂时无法打开工作空间'
      } finally {
        this.loading = false
      }
    },
    reset() {
      this.projects = []
      this.current = null
      this.loading = false
      this.error = ''
    },
    async refresh(selectId?: string) {
      this.projects = await api.listProjects()
      const target = selectId ? this.projects.find(item => item.id === selectId) : this.projects.find(item => item.id === this.current?.id)
      this.current = target ?? this.projects[0] ?? null
    },
    select(project: Project) {
      this.current = project
    },
    async create(name: string, description = '') {
      const project = await api.createProject(name, description)
      this.projects = [project, ...this.projects]
      this.current = project
      return project
    },
    async update(id: string, name: string, description = '') {
      const project = await api.updateProject(id, name, description)
      this.projects = this.projects.map(item => item.id === id ? project : item)
      if (this.current?.id === id) this.current = project
      return project
    },
    async delete(id: string) {
      await api.deleteProject(id)
      this.projects = this.projects.filter(item => item.id !== id)
      if (this.current?.id === id) this.current = this.projects[0] ?? null
    },
  },
})
