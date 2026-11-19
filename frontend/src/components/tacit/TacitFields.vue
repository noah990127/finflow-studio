<script setup lang="ts">
import { label, record, type Entry } from '../../domain/tacit'
defineProps<{ fields: Entry }>()
const empty = (v: unknown) => v == null || v === '' || (typeof v === 'object' && Object.keys(v).length === 0)
function scalar(value: unknown) { return typeof value === 'boolean' ? (value ? '是' : '否') : label(value) }
</script>
<template>
  <dl class="t-fields"><template v-for="(value,key) in fields" :key="key">
    <template v-if="!empty(value)"><dt>{{ label(key) }}</dt><dd>
      <template v-if="Array.isArray(value)">
        <ul v-if="value.some(v=>typeof v === 'object')" class="t-values"><li v-for="(item,index) in value" :key="index">
          <TacitFields v-if="typeof item === 'object'" :fields="record(item)"/><template v-else>{{ scalar(item) }}</template>
        </li></ul><template v-else>{{ value.map(scalar).join('、') }}</template>
      </template>
      <TacitFields v-else-if="typeof value === 'object'" :fields="record(value)"/><template v-else>{{ scalar(value) }}</template>
    </dd></template>
  </template></dl>
</template>
