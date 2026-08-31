<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown, ChevronRight, Folder, FolderInput, FolderPlus, Pencil, Plus, Trash2 } from 'lucide-vue-next'
import type { WorkspaceFolder, WorkspaceResource } from '../api/client'

const props = defineProps<{ folder: WorkspaceFolder; folders: WorkspaceFolder[]; resources: WorkspaceResource[]; activeResourceId?: string; query: string; depth?: number; uiRoot: 'DATA' | 'KNOWLEDGE' | 'OUTPUT'; iconFor: (resource: WorkspaceResource) => unknown }>()
const emit = defineEmits<{ open: [resource: WorkspaceResource]; drag: [event: DragEvent, resource: WorkspaceResource]; addContent: [root: 'DATA' | 'KNOWLEDGE' | 'OUTPUT', folder: WorkspaceFolder]; addFolder: [folder: WorkspaceFolder]; renameFolder: [folder: WorkspaceFolder]; deleteFolder: [folder: WorkspaceFolder]; moveResource: [resource: WorkspaceResource] }>()
const open = ref(true)
const children = computed(() => props.folders.filter(item => item.parentId === props.folder.id))
const items = computed(() => props.resources.filter(item => item.folderId === props.folder.id))
const visible = computed(() => props.query.length > 0 || open.value)
</script>

<template>
  <div class="tree-folder" :style="{ '--tree-depth': depth ?? 0 }">
    <div class="tree-folder-row">
      <button class="tree-toggle" type="button" :title="visible ? '收起目录' : '展开目录'" @click="open = !open"><ChevronDown v-if="visible" :size="13"/><ChevronRight v-else :size="13"/></button>
      <Folder :size="15"/><span :title="folder.name">{{ folder.name }}</span><small>{{ children.length + items.length }}</small>
      <span class="tree-row-actions"><button v-if="uiRoot !== 'OUTPUT'" type="button" title="添加内容" @click="emit('addContent', uiRoot, folder)"><Plus :size="13"/></button><button type="button" title="新建子目录" @click="emit('addFolder', folder)"><FolderPlus :size="13"/></button><button type="button" title="重命名" @click="emit('renameFolder', folder)"><Pencil :size="12"/></button><button type="button" title="删除目录" @click="emit('deleteFolder', folder)"><Trash2 :size="12"/></button></span>
    </div>
    <div v-if="visible" class="tree-children">
      <ResourceTreeFolder v-for="child in children" :key="child.id" :folder="child" :folders="folders" :resources="resources" :active-resource-id="activeResourceId" :query="query" :depth="(depth ?? 0) + 1" :ui-root="uiRoot" :icon-for="iconFor" @open="emit('open', $event)" @drag="(event, resource) => emit('drag', event, resource)" @add-content="(root, folder) => emit('addContent', root, folder)" @add-folder="emit('addFolder', $event)" @rename-folder="emit('renameFolder', $event)" @delete-folder="emit('deleteFolder', $event)" @move-resource="emit('moveResource', $event)"/>
      <div v-for="item in items" :key="item.id" class="tree-resource-row" :class="{ active: activeResourceId === item.id }"><button draggable="true" type="button" @dragstart="emit('drag', $event, item)" @click="emit('open', item)"><component :is="iconFor(item)" :size="15"/><span :title="item.name">{{ item.name }}</span><i v-if="item.inProjectWorkflow" title="已在工作流中"></i></button><button type="button" title="移动到目录" @click="emit('moveResource', item)"><FolderInput :size="13"/></button></div>
      <p v-if="children.length === 0 && items.length === 0" class="tree-empty">空目录</p>
    </div>
  </div>
</template>
