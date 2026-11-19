<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Entry } from '../../domain/tacit'
const props=withDefaults(defineProps<{items:Entry[];size?:number;unit?:string}>(),{size:20,unit:'项'})
const number=ref(1)
const pages=computed(()=>Math.max(1,Math.ceil(props.items.length/props.size)))
watch(pages,n=>number.value=Math.min(number.value,n))
function goToIndex(index:number) {
  number.value=Math.max(1,Math.min(pages.value,Math.floor(index/props.size)+1))
}
defineExpose({goToIndex})
const start=computed(()=>(number.value-1)*props.size)
const page=computed(()=>props.items.slice(start.value,start.value+props.size))
</script>
<template>
  <slot :items="page" :start="start"/>
  <nav v-if="pages>1" class="t-actions t-pager" :aria-label="unit+'分页'">
    <button type="button" class="t-btn" :disabled="number<=1" @click="number--">上一页</button>
    <small aria-live="polite">{{start+1}}–{{start+page.length}} / {{items.length}} {{unit}}</small>
    <button type="button" class="t-btn" :disabled="number>=pages" @click="number++">下一页</button></nav>
</template>
