<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BarChart3, LineChart, PieChart } from 'lucide-vue-next'
import * as echarts from 'echarts'
import type { FinancialReportChart } from '../api/client'

const props = defineProps<{ chart: FinancialReportChart; storageKey: string }>()
const chartEl = ref<HTMLElement | null>(null)
const chartType = ref<'bar' | 'line' | 'pie'>(props.chart.type)
let instance: echarts.ECharts | null = null

function savedType() {
  const value = localStorage.getItem(props.storageKey)
  return value === 'bar' || value === 'line' || value === 'pie' ? value : props.chart.type
}

function draw() {
  if (!chartEl.value) return
  instance ??= echarts.init(chartEl.value)
  const pie = chartType.value === 'pie'
  const series = pie
    ? [{ type: 'pie', radius: ['34%', '68%'], center: ['50%', '52%'], data: props.chart.categories.map((name, index) => ({ name, value: props.chart.series[0]?.values[index] ?? 0 })), label: { color: '#42566f' } }]
    : props.chart.series.map(item => ({
        name: item.name, type: chartType.value, data: item.values, smooth: chartType.value === 'line', symbolSize: 7,
        lineStyle: { width: 3 }, areaStyle: chartType.value === 'line' ? { opacity: .06 } : undefined,
        itemStyle: { borderRadius: chartType.value === 'bar' ? [4, 4, 0, 0] : 0 }
      }))
  instance.setOption({
    animationDuration: 350,
    color: ['#1677ff', '#36a269', '#f0a128', '#7b61c9', '#e45b64', '#42a5b3'],
    grid: { top: 42, right: 24, bottom: 42, left: 62 },
    legend: { show: props.chart.series.length > 1 || pie, top: 4, textStyle: { color: '#64748b' } },
    tooltip: { trigger: pie ? 'item' : 'axis' },
    xAxis: pie ? undefined : { type: 'category', data: props.chart.categories, axisLabel: { color: '#64748b' }, axisLine: { lineStyle: { color: '#cad5e5' } } },
    yAxis: pie ? undefined : { type: 'value', axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: '#e8eef6' } } },
    series
  }, true)
}

function choose(type: 'bar' | 'line' | 'pie') {
  chartType.value = type
  localStorage.setItem(props.storageKey, type)
}

watch(() => props.chart, async () => { chartType.value = savedType(); await nextTick(); draw() }, { deep: true })
watch(chartType, async () => { await nextTick(); draw() })
onMounted(() => { chartType.value = savedType(); draw(); window.addEventListener('resize', draw) })
onBeforeUnmount(() => { window.removeEventListener('resize', draw); instance?.dispose() })
</script>

<template>
  <section class="report-generated-chart">
    <header>
      <div><strong>{{ chart.title }}</strong><span v-if="chart.source_ref">来源：{{ chart.source_ref }}</span></div>
      <nav class="report-chart-switch" aria-label="图表类型">
        <button :class="{ active: chartType === 'bar' }" title="柱状图" @click="choose('bar')"><BarChart3 :size="15"/></button>
        <button :class="{ active: chartType === 'line' }" title="折线图" @click="choose('line')"><LineChart :size="15"/></button>
        <button :class="{ active: chartType === 'pie' }" title="饼图" @click="choose('pie')"><PieChart :size="15"/></button>
      </nav>
    </header>
    <div ref="chartEl" class="report-generated-chart-canvas"></div>
  </section>
</template>
