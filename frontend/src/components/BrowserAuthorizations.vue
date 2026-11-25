<script setup lang="ts">
import { ref } from 'vue'
import { ShieldCheck, X, RefreshCw, Ban, Hand, Play } from 'lucide-vue-next'
import { api } from '../api/client'

const dialog = ref<HTMLDialogElement | null>(null)
const tab = ref<'connections' | 'grants' | 'history'>('connections')
const connections = ref<Awaited<ReturnType<typeof api.browserConnections>>>([])
const notice = ref('')
const grants = ref<Awaited<ReturnType<typeof api.browserAuthorizations>>>([])
const history = ref<Awaited<ReturnType<typeof api.browserAuthorizationHistory>>>([])
const busy = ref(false)
const error = ref('')
const historyPage = ref(0)
const hasMore = ref(false)
const labels: Record<string, string> = {
  ACTIVE: '有效', REVOKED: '已撤销', EXPIRED: '已过期', GRANTED: '已记住授权', APPROVED_ONCE: '仅本次授权',
  EXECUTION_STARTED: '开始执行', AUTHORIZATION_REUSED: '复用历史授权', EXECUTION_SUCCEEDED: '执行成功',
  EXECUTION_FAILED: '执行失败', DENIED: '授权校验未通过', BROWSER_OPERATION: '浏览器操作', BROWSER_OPERATION_FAILED: '浏览器操作失败',
  SESSION_CLOSE_UNCONFIRMED: '已撤销，窗口关闭待核对',
  USER_CONTROL_REQUESTED: '用户切换浏览器控制权',
  SUBMISSION_APPROVED: '用户确认本次提交', BROWSER_HUMAN_CONTROL: '用户处理浏览器待办',
}
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
async function load() {
  busy.value = true; error.value = ''
  try { connections.value = await api.browserConnections(); grants.value = await api.browserAuthorizations(); history.value = await api.browserAuthorizationHistory(); historyPage.value = 0; hasMore.value = history.value.length === 200 }
  catch (e) { error.value = e instanceof Error ? e.message : '读取授权记录失败' }
  finally { busy.value = false }
}
function open() { dialog.value?.showModal(); void load() }
async function control(session: string, action: 'login' | 'restart_login' | 'resume' | 'close') {
  busy.value = true; error.value = ''; notice.value = ''
  try {
    await api.browserControl(session, action)
    notice.value = action === 'resume' ? '已交还 Agent，登录结果待核验' : action === 'restart_login' ? '已进入人工接管，并重新打开登录入口' : action === 'login' ? '已交给你操作原窗口' : '连接已关闭'
    await load()
  } catch (e) { error.value = e instanceof Error ? e.message : '控制权切换失败' }
  finally { busy.value = false }
}
async function moreHistory() {
  busy.value = true; error.value = ''
  try { const items = await api.browserAuthorizationHistory(historyPage.value + 1); history.value.push(...items); historyPage.value++; hasMore.value = items.length === 200 }
  catch (e) { error.value = e instanceof Error ? e.message : '读取历史失败' }
  finally { busy.value = false }
}
async function revoke(id: string) {
  busy.value = true; error.value = ''
  try { await api.revokeBrowserAuthorization(id); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '撤销失败' }
  finally { busy.value = false }
}
</script>

<template>
  <button class="icon-button" title="网站授权与审计" aria-label="网站授权与审计" @click="open"><ShieldCheck :size="16" /></button>
  <Teleport to="body">
    <dialog ref="dialog" class="browser-grants-dialog" aria-labelledby="browser-grants-title">
      <header><h2 id="browser-grants-title">网站授权</h2><button class="icon-button" title="刷新" :disabled="busy" @click="load"><RefreshCw :size="16" /></button><button class="icon-button" title="关闭" @click="dialog?.close()"><X :size="18" /></button></header>
      <nav aria-label="授权记录视图"><button :aria-pressed="tab === 'connections'" @click="tab = 'connections'">浏览器</button><button :aria-pressed="tab === 'grants'" @click="tab = 'grants'">已授权</button><button :aria-pressed="tab === 'history'" @click="tab = 'history'">审计历史</button></nav>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="busy" role="status">正在处理…</p>
      <section v-if="tab === 'connections'" aria-label="我的浏览器连接">
        <p v-if="!busy && !connections.length">暂无活动连接</p>
        <article v-for="item in connections" :key="item.session">
          <div><strong>{{ item.origin }}</strong><small>{{ item.loginWindowExpired ? '人工操作时间已到，可重新接管' : item.status === 'HUMAN_CONTROL' ? '由你操作' : '由 Agent 操作' }}</small></div>
          <button v-if="item.status !== 'HUMAN_CONTROL' || item.loginWindowExpired" class="icon-button" title="接管浏览器" aria-label="接管浏览器" :disabled="busy" @click="control(item.session, 'login')"><Hand :size="16" /></button>
          <button class="icon-button" title="重新进入登录页" aria-label="重新进入登录页" :disabled="busy" @click="control(item.session, 'restart_login')"><RefreshCw :size="16" /></button>
          <button v-if="item.status === 'HUMAN_CONTROL'" class="text-button" :disabled="busy" @click="control(item.session, 'resume')"><Play :size="14" />交还 Agent</button>
          <button class="icon-button" title="关闭连接" aria-label="关闭连接" :disabled="busy" @click="control(item.session, 'close')"><X :size="16" /></button>
        </article>
      </section>
      <section v-else-if="tab === 'grants'" aria-label="我的网站授权">
        <p v-if="!busy && !grants.length">暂无已保存的授权</p>
        <article v-for="item in grants" :key="item.id"><div><strong>{{ item.label }}</strong><small>{{ labels[item.status] || item.status }} · 到期 {{ date(item.expiresAt) }}</small></div><button v-if="item.status === 'ACTIVE'" class="icon-button" title="撤销授权" :aria-label="`撤销授权：${item.label}`" :disabled="busy" @click="revoke(item.id)"><Ban :size="16" /></button></article>
      </section>
      <section v-else aria-label="授权审计历史">
        <p v-if="!busy && !history.length">暂无审计记录</p>
        <article v-for="item in history" :key="item.id"><div><strong>{{ labels[item.event] || item.event }}</strong><span>{{ item.label }}</span><small>{{ date(item.createdAt) }}</small></div></article>
        <button v-if="hasMore" class="text-button" :disabled="busy" @click="moreHistory">更早记录</button>
      </section>
    </dialog>
  </Teleport>
</template>

<style scoped>
.browser-handoff { display: flex; flex-wrap: wrap; gap: 4px; min-width: 0; flex: 0 0 100%; order: 1; }
.browser-control { display: flex; align-items: center; gap: 8px; width: 100%; min-width: 0; font-size: 12px; }
.browser-site { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #626974; }
.browser-control-state { color: #626974; }
.browser-handoff button { display: inline-flex; align-items: center; gap: 4px; max-width: 100%; white-space: normal; overflow-wrap: anywhere; color: #24634b; }
.browser-handoff [role='alert'] { font-size: 12px; }
.browser-grants-dialog { width: min(640px, calc(100vw - 32px)); max-height: min(720px, calc(100dvh - 48px)); padding: 20px; border: 1px solid #dce1e7; border-radius: 8px; color: #24272c; background: #fff; overflow: auto; box-sizing: border-box; }
.browser-grants-dialog::backdrop { background: rgb(0 0 0 / 25%); }
header { display: flex; align-items: center; gap: 8px; } h2 { margin: 0; flex: 1; font-size: 18px; }
nav { display: flex; gap: 16px; border-bottom: 1px solid #e7e9ec; margin: 12px 0; } nav button { border: 0; border-bottom: 2px solid transparent; background: transparent; padding: 10px 0; cursor: pointer; color: #626974; } nav button[aria-pressed='true'] { color: #24634b; border-bottom-color: #24634b; }
article { display: flex; gap: 12px; align-items: start; padding: 12px 0; border-bottom: 1px solid #edf0f2; } article div { flex: 1; min-width: 0; } article strong, article span, article small { display: block; overflow-wrap: anywhere; } article strong { font-size: 13px; font-weight: 500; } article span { font-size: 13px; margin-top: 4px; } article small { font-size: 12px; color: #737b85; margin-top: 6px; } p { font-size: 13px; } [role='alert'] { color: #b42318; }
</style>
