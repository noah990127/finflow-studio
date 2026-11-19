<script setup lang="ts">
import TacitResultPage from './TacitResultPage.vue'
import { label, record, rows, type Entry } from '../../domain/tacit'
const props = defineProps<{ fields: Entry; literal?: boolean }>()
const empty = (v: unknown) => v == null || v === '' || (typeof v === 'object' && Object.keys(v).length === 0)
const localized = new Set(['status','review_status','kind','case_kind','read_status','disposition','coverage',
  'source_type','content_kind','process_disposition','default_status','operator','comparator','origin','semantic_unit','strategy'])
function scalar(value: unknown,key: string) {
  if (props.literal) return String(value)
  if (typeof value==='boolean') return value ? '是' : '否'
  return localized.has(key) ? label(value) : String(value ?? '')
}
</script>
<template>
  <dl class="t-fields"><template v-for="(value,key) in fields" :key="key">
    <template v-if="literal || !empty(value)"><dt>{{ literal ? key : label(key) }}</dt><dd>
      <template v-if="literal && empty(value)">{{JSON.stringify(value)}}</template>
      <template v-else-if="Array.isArray(value)">
        <template v-if="key==='columns' && value.every(v=>v && typeof v==='object')">
          <small>共 {{value.length}} 个字段</small><TacitResultPage :items="rows(value)" :size="160" unit="个字段" v-slot="{items,start}">
            <ol class="t-values" :start="start+1"><li v-for="(column,index) in items" :key="start+index"><TacitFields :fields="column"/></li></ol>
          </TacitResultPage></template>
        <ul v-else-if="value.some(v=>typeof v === 'object')" class="t-values"><li v-for="(item,index) in value" :key="index">
          <TacitFields v-if="Array.isArray(item)" :fields="{['第 '+(index+1)+' 组']:item}"/>
          <TacitFields v-else-if="item && typeof item === 'object'" :fields="record(item)" :literal="literal"/><template v-else>{{ scalar(item,key) }}</template>
        </li></ul><template v-else>{{ value.map(v=>scalar(v,key)).join('、') }}</template>
      </template>
      <TacitFields v-else-if="typeof value === 'object'" :fields="record(value)" :literal="literal || ['inputs','expected_outputs','observed_outputs','default_value','value'].includes(key)"/><template v-else>{{ scalar(value,key) }}</template>
    </dd></template>
  </template></dl>
</template>
