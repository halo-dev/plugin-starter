<script setup lang="ts">
import { axiosInstance } from '@halo-dev/api-client'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

type Summary = {
  requests: number
  requestBytes: number
  responseBytes: number
  totalBytes: number
  uniqueIps: number
  errorRequests: number
  unacknowledgedAlerts: number
  since: number
}

type TimelinePoint = {
  timestamp: number
  requests: number
  requestBytes: number
  responseBytes: number
  errors: number
}

type IpView = {
  ip: string
  requests: number
  totalBytes: number
  errorRequests: number
  firstSeen: number
  lastSeen: number
  lastPath: string
  lastStatus: number
}

type AlertView = {
  id: string
  timestamp: number
  ip: string
  type: string
  severity: 'high' | 'medium' | 'low'
  message: string
  path: string
  acknowledged: boolean
}

type BlockView = {
  ip: string
  blockedAt: number
  expiresAt: number
  reason: string
  source: string
  hitCount: number
  lastHit: number
}

type Dashboard = {
  summary: Summary
  timeline: TimelinePoint[]
  topIps: IpView[]
  alerts: AlertView[]
  blockedIps: BlockView[]
  generatedAt: number
}

const API = '/apis/console.api.traffic-monitor.halo.run/v1alpha1'
const dashboard = ref<Dashboard>()
const loading = ref(true)
const error = ref('')
const minutes = ref(60)
const live = ref(false)
let source: EventSource | undefined
let fallbackTimer: number | undefined

const maxRequests = computed(() =>
  Math.max(1, ...(dashboard.value?.timeline.map((point) => point.requests) ?? [1])),
)
const blockedSet = computed(() => new Set(dashboard.value?.blockedIps.map((item) => item.ip) ?? []))

function barHeight(value: number) {
  if (value === 0) return '2px'
  return `${Math.max(8, Math.round((value / maxRequests.value) * 180))}px`
}

function formatBytes(value: number) {
  if (!Number.isFinite(value) || value <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1)
  return `${(value / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

function formatTime(timestamp: number, withDate = false) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: withDate ? '2-digit' : undefined,
    day: withDate ? '2-digit' : undefined,
    hour: '2-digit',
    minute: '2-digit',
    second: withDate ? '2-digit' : undefined,
    hour12: false,
  }).format(new Date(timestamp))
}

async function loadDashboard() {
  try {
    const { data } = await axiosInstance.get<Dashboard>(`${API}/dashboard`, {
      params: { minutes: minutes.value },
    })
    dashboard.value = data
    error.value = ''
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '加载监控数据失败'
  } finally {
    loading.value = false
  }
}

function startRealtime() {
  stopRealtime()
  source = new EventSource(`${API}/stream?minutes=${minutes.value}`)
  source.addEventListener('dashboard', (event) => {
    try {
      dashboard.value = JSON.parse((event as MessageEvent).data) as Dashboard
      live.value = true
      error.value = ''
    } catch {
      live.value = false
    }
  })
  source.onerror = () => {
    live.value = false
  }
  fallbackTimer = window.setInterval(() => {
    if (!live.value) void loadDashboard()
  }, 5000)
}

function stopRealtime() {
  source?.close()
  source = undefined
  if (fallbackTimer !== undefined) window.clearInterval(fallbackTimer)
  fallbackTimer = undefined
  live.value = false
}

async function acknowledge(alert: AlertView) {
  await axiosInstance.post(`${API}/alerts/${encodeURIComponent(alert.id)}/acknowledge`)
  alert.acknowledged = true
}

async function blockIp(ip: string) {
  if (!window.confirm(`封禁 ${ip} 24 小时？白名单地址仍会放行。`)) return
  await axiosInstance.post(`${API}/blocks/${encodeURIComponent(ip)}`, undefined, {
    params: { minutes: 1440, reason: 'Console 手动封禁' },
  })
  await loadDashboard()
}

async function unblockIp(ip: string) {
  if (!window.confirm(`立即解除 ${ip} 的封禁？`)) return
  await axiosInstance.delete(`${API}/blocks/${encodeURIComponent(ip)}`)
  await loadDashboard()
}

async function resetData() {
  if (!window.confirm('确定清空当前访问统计和告警吗？')) return
  await axiosInstance.delete(`${API}/data`)
  await loadDashboard()
}

watch(minutes, async () => {
  loading.value = true
  await loadDashboard()
  startRealtime()
})

onMounted(async () => {
  await loadDashboard()
  startRealtime()
})

onBeforeUnmount(stopRealtime)
</script>

<template>
  <div class="traffic-page">
    <header class="page-header">
      <div>
        <div class="eyebrow">HALO TRAFFIC OBSERVATORY</div>
        <h1>IP 流量监控</h1>
        <p>实时查看访问次数、访问时间、上下行字节和异常告警。</p>
      </div>
      <div class="header-actions">
        <span class="live-pill" :class="{ active: live }">
          <i></i>{{ live ? '实时连接' : '轮询更新' }}
        </span>
        <select v-model.number="minutes" aria-label="统计时间范围">
          <option :value="30">最近 30 分钟</option>
          <option :value="60">最近 1 小时</option>
          <option :value="360">最近 6 小时</option>
          <option :value="1440">最近 24 小时</option>
        </select>
        <button class="ghost-button" type="button" @click="loadDashboard">立即刷新</button>
      </div>
    </header>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading && !dashboard" class="loading-state">正在读取流量数据……</div>

    <template v-if="dashboard">
      <section class="metric-grid">
        <article class="metric-card accent-blue">
          <span>访问次数</span>
          <strong>{{ dashboard.summary.requests.toLocaleString() }}</strong>
          <small>{{ minutes }} 分钟窗口</small>
        </article>
        <article class="metric-card accent-violet">
          <span>独立 IP</span>
          <strong>{{ dashboard.summary.uniqueIps.toLocaleString() }}</strong>
          <small>去重客户端地址</small>
        </article>
        <article class="metric-card accent-cyan">
          <span>总流量</span>
          <strong>{{ formatBytes(dashboard.summary.totalBytes) }}</strong>
          <small
            >入站 {{ formatBytes(dashboard.summary.requestBytes) }} · 出站
            {{ formatBytes(dashboard.summary.responseBytes) }}</small
          >
        </article>
        <article class="metric-card accent-red">
          <span>未处理告警</span>
          <strong>{{ dashboard.summary.unacknowledgedAlerts }}</strong>
          <small
            >5xx 请求 {{ dashboard.summary.errorRequests }} · 已封禁
            {{ dashboard.blockedIps.length }}</small
          >
        </article>
      </section>

      <section class="panel chart-panel">
        <div class="panel-title">
          <div>
            <h2>访问时间柱状图</h2>
            <p>每根柱代表对应时间桶内的 HTTP 请求数</p>
          </div>
          <div class="legend"><i></i>访问次数</div>
        </div>
        <div class="chart-shell">
          <div class="y-guide">
            <span>{{ maxRequests }}</span
            ><span>{{ Math.round(maxRequests / 2) }}</span
            ><span>0</span>
          </div>
          <div class="bar-chart">
            <div
              v-for="(point, index) in dashboard.timeline"
              :key="point.timestamp"
              class="bar-slot"
              :title="`${formatTime(point.timestamp, true)}\n请求 ${point.requests}\n流量 ${formatBytes(point.requestBytes + point.responseBytes)}\n5xx ${point.errors}`"
            >
              <div
                class="bar"
                :class="{ empty: point.requests === 0 }"
                :style="{ height: barHeight(point.requests) }"
              ></div>
              <span v-if="index % Math.max(1, Math.floor(dashboard.timeline.length / 8)) === 0">
                {{ formatTime(point.timestamp) }}
              </span>
            </div>
          </div>
        </div>
      </section>

      <div class="content-grid">
        <section class="panel">
          <div class="panel-title">
            <div>
              <h2>IP 访问排名</h2>
              <p>按访问次数从高到低</p>
            </div>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>IP</th>
                  <th>访问</th>
                  <th>流量</th>
                  <th>最后时间</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in dashboard.topIps" :key="item.ip">
                  <td>
                    <code>{{ item.ip }}</code
                    ><small class="path">{{ item.lastPath }}</small>
                  </td>
                  <td>{{ item.requests.toLocaleString() }}</td>
                  <td>{{ formatBytes(item.totalBytes) }}</td>
                  <td>{{ formatTime(item.lastSeen, true) }}</td>
                  <td>
                    <span class="status" :class="{ bad: item.lastStatus >= 500 }">{{
                      item.lastStatus
                    }}</span>
                  </td>
                  <td>
                    <button
                      v-if="blockedSet.has(item.ip)"
                      class="mini-button unblock"
                      type="button"
                      @click="unblockIp(item.ip)"
                    >
                      解封
                    </button>
                    <button
                      v-else
                      class="mini-button block"
                      type="button"
                      @click="blockIp(item.ip)"
                    >
                      封禁
                    </button>
                  </td>
                </tr>
                <tr v-if="dashboard.topIps.length === 0">
                  <td colspan="6" class="empty-row">暂无访问数据</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel alerts-panel">
          <div class="panel-title">
            <div>
              <h2>异常流量告警</h2>
              <p>速率、带宽、5xx 爆发、大流量与扫描路径</p>
            </div>
          </div>
          <div class="alert-list">
            <article
              v-for="alert in dashboard.alerts"
              :key="alert.id"
              class="alert-item"
              :class="{ acknowledged: alert.acknowledged }"
            >
              <div class="alert-severity" :class="alert.severity"></div>
              <div class="alert-body">
                <div>
                  <strong>{{ alert.type }}</strong
                  ><time>{{ formatTime(alert.timestamp, true) }}</time>
                </div>
                <p>{{ alert.message }}</p>
                <code>{{ alert.ip }} · {{ alert.path }}</code>
              </div>
              <button v-if="!alert.acknowledged" type="button" @click="acknowledge(alert)">
                确认
              </button>
              <span v-else class="done">已确认</span>
            </article>
            <div v-if="dashboard.alerts.length === 0" class="empty-row">暂无异常流量告警</div>
          </div>
        </section>
      </div>

      <section class="panel protection-panel">
        <div class="panel-title">
          <div>
            <h2>IP 封禁列表</h2>
            <p>登录失败达到阈值后自动封禁；白名单请在插件设置中维护，默认放行本机地址。</p>
          </div>
          <span class="block-count">{{ dashboard.blockedIps.length }} ACTIVE</span>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>IP</th>
                <th>来源</th>
                <th>原因</th>
                <th>封禁时间</th>
                <th>到期时间</th>
                <th>拦截次数</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in dashboard.blockedIps" :key="item.ip">
                <td>
                  <code>{{ item.ip }}</code>
                </td>
                <td>
                  <span class="source-pill">{{ item.source }}</span>
                </td>
                <td>{{ item.reason }}</td>
                <td>{{ formatTime(item.blockedAt, true) }}</td>
                <td>{{ formatTime(item.expiresAt, true) }}</td>
                <td>{{ item.hitCount }}</td>
                <td>
                  <button class="mini-button unblock" type="button" @click="unblockIp(item.ip)">
                    立即解封
                  </button>
                </td>
              </tr>
              <tr v-if="dashboard.blockedIps.length === 0">
                <td colspan="7" class="empty-row">当前没有被封禁的 IP</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <footer>
        <span>最后更新：{{ formatTime(dashboard.generatedAt, true) }}</span>
        <button type="button" class="danger-link" @click="resetData">清空监控数据</button>
      </footer>
    </template>
  </div>
</template>

<style scoped lang="scss">
.traffic-page {
  min-height: 100vh;
  padding: 28px;
  color: #172033;
  background:
    radial-gradient(circle at 10% -10%, rgb(54 111 255 / 12%), transparent 32rem), #f5f7fb;
}

.page-header,
.header-actions,
.panel-title,
.alert-body > div,
footer {
  display: flex;
  align-items: center;
}
.page-header {
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}
.eyebrow {
  color: #4f6fff;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.16em;
}
h1 {
  margin: 5px 0;
  font-size: 30px;
  letter-spacing: -0.04em;
}
.page-header p,
.panel-title p {
  margin: 0;
  color: #718096;
  font-size: 13px;
}
.header-actions {
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
select,
.ghost-button {
  height: 38px;
  border: 1px solid #dce3ef;
  border-radius: 9px;
  background: white;
  padding: 0 12px;
  color: #34405a;
}
button {
  cursor: pointer;
}
.live-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #7d879a;
  font-size: 12px;
}
.live-pill i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #aab2c0;
}
.live-pill.active {
  color: #0c9b65;
}
.live-pill.active i {
  background: #13c27c;
  box-shadow: 0 0 0 5px rgb(19 194 124 / 12%);
  animation: pulse 1.8s infinite;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}
.metric-card,
.panel {
  border: 1px solid #e3e8f1;
  border-radius: 15px;
  background: rgb(255 255 255 / 92%);
  box-shadow: 0 10px 35px rgb(28 47 86 / 5%);
}
.metric-card {
  position: relative;
  overflow: hidden;
  padding: 18px;
}
.metric-card::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  background: var(--accent);
}
.metric-card span {
  color: #6e788c;
  font-size: 12px;
  font-weight: 600;
}
.metric-card strong {
  display: block;
  margin: 8px 0 4px;
  font-size: 28px;
  letter-spacing: -0.03em;
}
.metric-card small {
  color: #919bad;
  font-size: 11px;
}
.accent-blue {
  --accent: #436dff;
}
.accent-violet {
  --accent: #8c5cff;
}
.accent-cyan {
  --accent: #00a9bd;
}
.accent-red {
  --accent: #f05264;
}

.panel {
  padding: 20px;
}
.panel-title {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.panel-title h2 {
  margin: 0 0 4px;
  font-size: 16px;
}
.legend {
  color: #758095;
  font-size: 11px;
}
.legend i {
  display: inline-block;
  width: 9px;
  height: 9px;
  margin-right: 6px;
  border-radius: 2px;
  background: linear-gradient(#547bff, #355cf2);
}
.chart-panel {
  margin-bottom: 14px;
}
.chart-shell {
  display: grid;
  grid-template-columns: 32px 1fr;
  height: 225px;
}
.y-guide {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding-bottom: 23px;
  color: #a0a8b7;
  font-size: 10px;
}
.bar-chart {
  display: flex;
  align-items: flex-end;
  gap: 3px;
  border-bottom: 1px solid #dfe5ef;
  background: repeating-linear-gradient(
    to bottom,
    #edf1f7 0,
    #edf1f7 1px,
    transparent 1px,
    transparent 50%
  );
}
.bar-slot {
  display: flex;
  flex: 1;
  min-width: 2px;
  height: 100%;
  align-items: center;
  justify-content: flex-end;
  flex-direction: column;
}
.bar {
  width: min(72%, 16px);
  min-width: 2px;
  border-radius: 4px 4px 1px 1px;
  background: linear-gradient(180deg, #688aff, #365df2);
  transition: height 0.25s ease;
}
.bar.empty {
  background: #dce3ef;
}
.bar-slot > span {
  height: 22px;
  padding-top: 7px;
  color: #9aa3b3;
  font-size: 9px;
  white-space: nowrap;
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(360px, 0.8fr);
  gap: 14px;
}
.table-wrap {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
th {
  padding: 9px 8px;
  color: #8b94a6;
  font-size: 10px;
  text-align: left;
  border-bottom: 1px solid #edf0f5;
}
td {
  padding: 11px 8px;
  border-bottom: 1px solid #f0f2f6;
}
td code {
  color: #31405c;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}
.path {
  display: block;
  max-width: 220px;
  margin-top: 3px;
  overflow: hidden;
  color: #a0a8b7;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.status {
  display: inline-flex;
  padding: 2px 7px;
  border-radius: 9px;
  color: #16805a;
  background: #e9f8f2;
}
.status.bad {
  color: #c6374b;
  background: #fdecef;
}
.mini-button {
  border: 0;
  border-radius: 7px;
  padding: 6px 9px;
  font-size: 10px;
  font-weight: 700;
}
.mini-button.block {
  color: #c6374b;
  background: #fdecef;
}
.mini-button.unblock {
  color: #167657;
  background: #e7f8f1;
}
.protection-panel {
  margin-top: 14px;
}
.block-count {
  border: 1px solid #ffd0d6;
  border-radius: 999px;
  padding: 5px 9px;
  color: #c6374b;
  background: #fff2f4;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
}
.source-pill {
  display: inline-flex;
  border-radius: 999px;
  padding: 3px 7px;
  color: #4c5f86;
  background: #eef2fa;
  font-size: 10px;
}

.alert-list {
  max-height: 390px;
  overflow: auto;
}
.alert-item {
  display: grid;
  grid-template-columns: 4px 1fr auto;
  gap: 11px;
  padding: 11px 0;
  border-bottom: 1px solid #f0f2f6;
}
.alert-severity {
  border-radius: 4px;
  background: #f0ad4e;
}
.alert-severity.high {
  background: #ef5063;
}
.alert-severity.low {
  background: #4e7cf0;
}
.alert-body {
  min-width: 0;
}
.alert-body > div {
  justify-content: space-between;
  gap: 10px;
}
.alert-body strong {
  font-size: 11px;
  letter-spacing: 0.04em;
}
.alert-body time {
  color: #9aa3b3;
  font-size: 10px;
}
.alert-body p {
  margin: 4px 0;
  color: #505c72;
  font-size: 12px;
}
.alert-body code {
  display: block;
  overflow: hidden;
  color: #8993a5;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.alert-item button {
  align-self: center;
  border: 0;
  border-radius: 7px;
  padding: 6px 9px;
  color: #3157dc;
  background: #edf2ff;
  font-size: 11px;
}
.alert-item.acknowledged {
  opacity: 0.55;
}
.done {
  align-self: center;
  color: #8d96a7;
  font-size: 10px;
}
.empty-row {
  padding: 28px !important;
  color: #9aa3b3;
  text-align: center;
}

footer {
  justify-content: space-between;
  padding: 14px 4px 0;
  color: #929bad;
  font-size: 11px;
}
.danger-link {
  border: 0;
  color: #d04a5a;
  background: transparent;
}
.error-banner {
  margin-bottom: 14px;
  border: 1px solid #ffcbd2;
  border-radius: 10px;
  padding: 10px 13px;
  color: #bb3045;
  background: #fff2f4;
  font-size: 12px;
}
.loading-state {
  padding: 80px;
  color: #8a94a6;
  text-align: center;
}

@keyframes pulse {
  50% {
    box-shadow: 0 0 0 8px rgb(19 194 124 / 4%);
  }
}
@media (max-width: 1100px) {
  .metric-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .content-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 720px) {
  .traffic-page {
    padding: 16px;
  }
  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }
  .header-actions {
    justify-content: flex-start;
  }
  .metric-grid {
    grid-template-columns: 1fr;
  }
  .chart-shell {
    overflow-x: auto;
  }
  .bar-chart {
    min-width: 700px;
  }
}
</style>
