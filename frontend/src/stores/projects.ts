import { defineStore } from 'pinia'
import { api, type Project } from '../api/client'

export const useProjectsStore = defineStore('projects', {
  state: () => ({
    projects: [] as Project[],
    current: null as Project | null,
    loading: false,
    error: '',
    loadSequence: 0,
  }),
  actions: {
    async initialize() {
      const sequence = ++this.loadSequence
      this.loading = true
      this.error = ''
      try {
        const projects = await api.listProjects()
        if (sequence !== this.loadSequence) return
        this.projects = projects
        this.current = this.projects[0] ?? null
      } catch (error) {
        if (sequence !== this.loadSequence) return
        this.error = error instanceof Error ? error.message : '暂时无法打开工作空间'
      } finally {
        if (sequence === this.loadSequence) this.loading = false
      }
    },
    reset() {
      this.loadSequence += 1
      this.projects = []
      this.current = null
      this.loading = false
      this.error = ''
    },
    async refresh(selectId?: string) {
      const sequence = ++this.loadSequence
      const projects = await api.listProjects()
      if (sequence !== this.loadSequence) return
      const target = selectId ? projects.find(item => item.id === selectId) : projects.find(item => item.id === this.current?.id)
      this.projects = projects
      this.current = target ?? projects[0] ?? null
    },
    select(project: Project) {
      this.loadSequence += 1
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
