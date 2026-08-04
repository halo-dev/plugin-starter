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
  ips: { ip: string; requests: number }[]
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

type AccessLogView = {
  id: string
  timestamp: number
  ip: string
  method: string
  path: string
  status: number
  requestBytes: number
  responseBytes: number
  durationMs: number
  contentType: string
  responsePreview: string
  bodyCaptured: boolean
  bodyTruncated: boolean
}

type AccessLogPage = {
  items: AccessLogView[]
  total: number
  offset: number
  limit: number
}

type AccessDetail = {
  id: string
  timestamp: number
  ip: string
  method: string
  path: string
  status: number
  requestBytes: number
  responseBytes: number
  durationMs: number
  userAgent: string
  contentType: string
  responseHeaders: Record<string, string[]>
  responseBody: string
  bodyCaptured: boolean
  bodyTruncated: boolean
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
const records = ref<AccessLogPage>({ items: [], total: 0, offset: 0, limit: 100 })
const recordsLoading = ref(false)
const recordFilters = ref({ ip: '', path: '', status: '' })
const selectedRecord = ref<AccessDetail>()
const detailLoading = ref(false)
const blockModes = ref<Record<string, string>>({})
const selectedTimelineTimestamp = ref<number>()
let source: EventSource | undefined
let fallbackTimer: number | undefined

const maxRequests = computed(() =>
  Math.max(1, ...(dashboard.value?.timeline.map((point) => point.requests) ?? [1])),
)
const blockedSet = computed(() => new Set(dashboard.value?.blockedIps.map((item) => item.ip) ?? []))
const activeTimelinePoint = computed(() => {
  const points = dashboard.value?.timeline ?? []
  return (
    points.find((point) => point.timestamp === selectedTimelineTimestamp.value) ??
    [...points].reverse().find((point) => point.requests > 0)
  )
})

function barHeight(value: number) {
  if (value === 0) return '2px'
  return `${Math.max(8, Math.round((value / maxRequests.value) * 180))}px`
}

function ipColor(ip: string) {
  if (ip === '其他') return '#aab4c5'
  let hash = 0
  for (let index = 0; index < ip.length; index += 1) hash = (hash * 31 + ip.charCodeAt(index)) | 0
  return `hsl(${Math.abs(hash) % 360} 72% 57%)`
}

function timelineTitle(point: TimelinePoint) {
  const ips = point.ips.map((item) => `${item.ip}: ${item.requests} 次`).join('\n')
  return `${formatTime(point.timestamp, true)}\n总访问 ${point.requests}\n${ips}\n流量 ${formatBytes(point.requestBytes + point.responseBytes)}\n5xx ${point.errors}`
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

function formatBlockExpiry(timestamp: number) {
  return timestamp === 0 ? '永久' : formatTime(timestamp, true)
}

function responseBody(detail: AccessDetail) {
  if (!detail.responseBody) return ''
  if (detail.contentType.toLowerCase().includes('json')) {
    try {
      return JSON.stringify(JSON.parse(detail.responseBody), null, 2)
    } catch {
      return detail.responseBody
    }
  }
  return detail.responseBody
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

async function loadRecords() {
  recordsLoading.value = true
  try {
    const status = recordFilters.value.status.trim()
    const { data } = await axiosInstance.get<AccessLogPage>(`${API}/records`, {
      params: {
        ip: recordFilters.value.ip.trim() || undefined,
        path: recordFilters.value.path.trim() || undefined,
        status: status || undefined,
        minutes: minutes.value,
        limit: 100,
        offset: 0,
      },
    })
    records.value = data
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '加载请求明细失败'
  } finally {
    recordsLoading.value = false
  }
}

async function openRecord(id: string) {
  detailLoading.value = true
  selectedRecord.value = undefined
  try {
    const { data } = await axiosInstance.get<AccessDetail>(
      `${API}/records/${encodeURIComponent(id)}`,
    )
    selectedRecord.value = data
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '加载响应详情失败'
  } finally {
    detailLoading.value = false
  }
}

function clearRecordFilters() {
  recordFilters.value = { ip: '', path: '', status: '' }
  void loadRecords()
}

function filterByIp(ip: string) {
  recordFilters.value.ip = ip
  void loadRecords()
}

function selectedBlockMode(ip: string) {
  return blockModes.value[ip] ?? '24h'
}

function setBlockMode(ip: string, event: Event) {
  blockModes.value[ip] = (event.target as HTMLSelectElement).value
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
  await loadDashboard()
}

async function blockIp(ip: string) {
  const mode = selectedBlockMode(ip)
  const labels: Record<string, string> = {
    '24h': '24 小时',
    '7d': '7 天',
    '30d': '30 天',
    permanent: '永久',
  }
  if (!window.confirm(`封禁 ${ip}：${labels[mode]}？白名单地址仍会放行。`)) return
  await axiosInstance.post(`${API}/blocks/${encodeURIComponent(ip)}`, undefined, {
    params: { mode, reason: `Console 手动封禁（${labels[mode]}）` },
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
  await loadRecords()
}

watch(minutes, async () => {
  loading.value = true
  await loadDashboard()
  await loadRecords()
  startRealtime()
})

onMounted(async () => {
  await loadDashboard()
  await loadRecords()
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
            <p>柱子总高度为访问次数，颜色按来源 IP 堆叠；点击柱子查看 IP 明细</p>
          </div>
          <div class="ip-legend">
            <span v-for="item in dashboard.topIps.slice(0, 5)" :key="item.ip">
              <i :style="{ background: ipColor(item.ip) }"></i>{{ item.ip }}
            </span>
          </div>
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
              :title="timelineTitle(point)"
            >
              <button
                type="button"
                class="bar"
                :class="{
                  empty: point.requests === 0,
                  selected: activeTimelinePoint?.timestamp === point.timestamp,
                }"
                :style="{ height: barHeight(point.requests) }"
                @click="selectedTimelineTimestamp = point.timestamp"
              >
                <i
                  v-for="ip in point.ips"
                  :key="ip.ip"
                  class="bar-segment"
                  :style="{ flexGrow: ip.requests, background: ipColor(ip.ip) }"
                  :title="`${ip.ip}: ${ip.requests} 次`"
                ></i>
              </button>
              <span v-if="index % Math.max(1, Math.floor(dashboard.timeline.length / 8)) === 0">
                {{ formatTime(point.timestamp) }}
              </span>
            </div>
          </div>
        </div>
        <div v-if="activeTimelinePoint" class="chart-ip-detail">
          <strong
            >{{ formatTime(activeTimelinePoint.timestamp, true) }} · 总访问
            {{ activeTimelinePoint.requests }} 次</strong
          >
          <div>
            <span v-for="item in activeTimelinePoint.ips" :key="item.ip">
              <i :style="{ background: ipColor(item.ip) }"></i>
              <code>{{ item.ip }}</code>
              <b>{{ item.requests }} 次</b>
            </span>
          </div>
        </div>
      </section>

      <section class="panel records-panel">
        <div class="panel-title records-title">
          <div>
            <h2>请求明细与响应数据</h2>
            <p>按 IP、访问路径和状态码筛选；详情包含响应头与限定长度的文本响应正文。</p>
          </div>
          <span class="record-count">{{ records.total.toLocaleString() }} RECORDS</span>
        </div>
        <form class="filter-bar" @submit.prevent="loadRecords">
          <label>
            <span>IP</span>
            <input v-model="recordFilters.ip" type="search" placeholder="例如 203.0.113.8" />
          </label>
          <label class="path-filter">
            <span>访问路径</span>
            <input v-model="recordFilters.path" type="search" placeholder="例如 /api/posts" />
          </label>
          <label>
            <span>状态码</span>
            <input
              v-model="recordFilters.status"
              type="number"
              min="100"
              max="599"
              placeholder="全部"
            />
          </label>
          <button class="filter-button" type="submit">筛选</button>
          <button class="clear-button" type="button" @click="clearRecordFilters">清空</button>
        </form>
        <div class="table-wrap records-table">
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>IP</th>
                <th>请求</th>
                <th>状态</th>
                <th>响应</th>
                <th>耗时</th>
                <th>详情</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in records.items" :key="item.id">
                <td>{{ formatTime(item.timestamp, true) }}</td>
                <td>
                  <button class="link-button" type="button" @click="filterByIp(item.ip)">
                    {{ item.ip }}
                  </button>
                </td>
                <td class="request-cell">
                  <span class="method-pill">{{ item.method }}</span>
                  <code :title="item.path">{{ item.path }}</code>
                </td>
                <td>
                  <span class="status" :class="{ bad: item.status >= 400 }">{{ item.status }}</span>
                </td>
                <td>
                  <strong>{{ formatBytes(item.responseBytes) }}</strong>
                  <small class="response-preview">{{
                    item.responsePreview || item.contentType || '未保存正文'
                  }}</small>
                </td>
                <td>{{ item.durationMs }} ms</td>
                <td>
                  <button class="mini-button detail" type="button" @click="openRecord(item.id)">
                    查看响应包
                  </button>
                </td>
              </tr>
              <tr v-if="recordsLoading">
                <td colspan="7" class="empty-row">正在筛选请求明细……</td>
              </tr>
              <tr v-else-if="records.items.length === 0">
                <td colspan="7" class="empty-row">当前条件下没有请求明细</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="records.total > records.limit" class="record-note">
          当前显示最新 {{ records.limit }} 条，共匹配 {{ records.total }} 条。
        </p>
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
                    <div v-else class="block-action">
                      <select
                        :value="selectedBlockMode(item.ip)"
                        aria-label="封禁时长"
                        @change="setBlockMode(item.ip, $event)"
                      >
                        <option value="24h">24 小时</option>
                        <option value="7d">7 天</option>
                        <option value="30d">30 天</option>
                        <option value="permanent">永久</option>
                      </select>
                      <button class="mini-button block" type="button" @click="blockIp(item.ip)">
                        封禁
                      </button>
                    </div>
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
              <p>速率、带宽、5xx 爆发、大流量与扫描路径；命中后自动封禁 24 小时</p>
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
                确认并封禁 24h
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
            <p>登录失败或异常流量可自动封禁；手动支持 24 小时、7 天、30 天和永久封禁。</p>
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
                <td>
                  <strong :class="{ permanent: item.expiresAt === 0 }">{{
                    formatBlockExpiry(item.expiresAt)
                  }}</strong>
                </td>
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

      <div
        v-if="detailLoading || selectedRecord"
        class="detail-backdrop"
        @click.self="selectedRecord = undefined"
      >
        <aside class="detail-drawer" aria-label="响应包详情">
          <div v-if="detailLoading" class="loading-state">正在读取响应包……</div>
          <template v-else-if="selectedRecord">
            <header class="detail-header">
              <div>
                <span>REQUEST / RESPONSE</span>
                <h2>响应包详情</h2>
              </div>
              <button type="button" aria-label="关闭" @click="selectedRecord = undefined">×</button>
            </header>
            <dl class="detail-meta">
              <div>
                <dt>来源 IP</dt>
                <dd>{{ selectedRecord.ip }}</dd>
              </div>
              <div>
                <dt>访问时间</dt>
                <dd>{{ formatTime(selectedRecord.timestamp, true) }}</dd>
              </div>
              <div>
                <dt>请求</dt>
                <dd>{{ selectedRecord.method }} {{ selectedRecord.path }}</dd>
              </div>
              <div>
                <dt>响应状态</dt>
                <dd>{{ selectedRecord.status }}</dd>
              </div>
              <div>
                <dt>流量</dt>
                <dd>
                  请求 {{ formatBytes(selectedRecord.requestBytes) }} / 响应
                  {{ formatBytes(selectedRecord.responseBytes) }}
                </dd>
              </div>
              <div>
                <dt>耗时</dt>
                <dd>{{ selectedRecord.durationMs }} ms</dd>
              </div>
              <div>
                <dt>User-Agent</dt>
                <dd>{{ selectedRecord.userAgent || '-' }}</dd>
              </div>
              <div>
                <dt>Content-Type</dt>
                <dd>{{ selectedRecord.contentType || '-' }}</dd>
              </div>
            </dl>
            <section class="packet-section">
              <h3>响应头</h3>
              <pre><template v-for="(values, name) in selectedRecord.responseHeaders" :key="name">{{ name }}: {{ values.join(', ') }}
</template><template v-if="Object.keys(selectedRecord.responseHeaders).length === 0">未保存响应头</template></pre>
            </section>
            <section class="packet-section body-section">
              <div class="packet-title">
                <h3>响应正文</h3>
                <span v-if="selectedRecord.bodyTruncated">已按配置截断</span>
              </div>
              <pre>{{
                selectedRecord.bodyCaptured
                  ? responseBody(selectedRecord) || '空响应正文'
                  : '该响应为二进制、未启用正文抓取或未写入响应体。'
              }}</pre>
            </section>
          </template>
        </aside>
      </div>
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
.ghost-button,
input {
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
.ip-legend {
  display: flex;
  max-width: 60%;
  gap: 6px 12px;
  flex-wrap: wrap;
  justify-content: flex-end;
  color: #758095;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 9px;
}
.ip-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.ip-legend i {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
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
  display: flex;
  width: min(72%, 16px);
  min-width: 2px;
  overflow: hidden;
  flex-direction: column-reverse;
  border: 0;
  border-radius: 4px 4px 1px 1px;
  padding: 0;
  background: linear-gradient(180deg, #688aff, #365df2);
  transition: height 0.25s ease;
}
.bar:hover,
.bar.selected {
  outline: 2px solid rgb(67 109 255 / 28%);
  outline-offset: 2px;
}
.bar.empty {
  background: #dce3ef;
}
.bar-segment {
  width: 100%;
  min-height: 1px;
  flex-basis: 0;
}
.bar-slot > span {
  height: 22px;
  padding-top: 7px;
  color: #9aa3b3;
  font-size: 9px;
  white-space: nowrap;
}
.chart-ip-detail {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 34px;
  margin-top: 10px;
  border-radius: 10px;
  padding: 8px 11px;
  color: #536077;
  background: #f4f7fc;
  font-size: 10px;
}
.chart-ip-detail > strong {
  white-space: nowrap;
}
.chart-ip-detail > div {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.chart-ip-detail span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid #e0e6ef;
  border-radius: 999px;
  padding: 4px 7px;
  background: white;
}
.chart-ip-detail i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.chart-ip-detail code {
  color: #45516a;
}
.chart-ip-detail b {
  color: #172033;
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
.records-panel {
  margin-bottom: 14px;
}
.records-title {
  margin-bottom: 14px;
}
.record-count {
  border-radius: 999px;
  padding: 5px 9px;
  color: #3157dc;
  background: #edf2ff;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
}
.filter-bar {
  display: grid;
  grid-template-columns: minmax(180px, 0.8fr) minmax(260px, 1.4fr) 120px auto auto;
  gap: 10px;
  align-items: end;
  margin-bottom: 16px;
  border-radius: 12px;
  padding: 12px;
  background: #f6f8fc;
}
.filter-bar label {
  display: grid;
  gap: 5px;
}
.filter-bar label > span {
  color: #788398;
  font-size: 10px;
  font-weight: 700;
}
.filter-bar input {
  width: 100%;
  box-sizing: border-box;
}
.filter-button,
.clear-button {
  height: 38px;
  border: 0;
  border-radius: 9px;
  padding: 0 14px;
  font-size: 11px;
  font-weight: 700;
}
.filter-button {
  color: white;
  background: #436dff;
}
.clear-button {
  color: #667188;
  background: #e9edf5;
}
.records-table {
  max-height: 520px;
}
.request-cell {
  max-width: 380px;
}
.request-cell code {
  display: inline-block;
  max-width: 310px;
  overflow: hidden;
  vertical-align: middle;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.method-pill {
  display: inline-flex;
  margin-right: 7px;
  border-radius: 5px;
  padding: 3px 5px;
  color: #3157dc;
  background: #edf2ff;
  font-size: 9px;
  font-weight: 800;
}
.link-button {
  border: 0;
  padding: 0;
  color: #3157dc;
  background: transparent;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}
.response-preview {
  display: block;
  max-width: 260px;
  margin-top: 3px;
  overflow: hidden;
  color: #9aa3b3;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.record-note {
  margin: 10px 0 0;
  color: #8c96a9;
  font-size: 10px;
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
.mini-button.detail {
  color: #3157dc;
  background: #edf2ff;
  white-space: nowrap;
}
.block-action {
  display: flex;
  gap: 5px;
}
.block-action select {
  width: 82px;
  height: 29px;
  padding: 0 5px;
  font-size: 10px;
}
.permanent {
  color: #c6374b;
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
.detail-backdrop {
  position: fixed;
  z-index: 1000;
  inset: 0;
  display: flex;
  justify-content: flex-end;
  background: rgb(18 27 46 / 42%);
  backdrop-filter: blur(2px);
}
.detail-drawer {
  width: min(720px, 92vw);
  height: 100%;
  box-sizing: border-box;
  overflow: auto;
  padding: 24px;
  background: #f7f9fc;
  box-shadow: -18px 0 55px rgb(12 24 49 / 18%);
}
.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}
.detail-header span {
  color: #4f6fff;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.14em;
}
.detail-header h2 {
  margin: 4px 0 0;
}
.detail-header button {
  border: 0;
  color: #738096;
  background: transparent;
  font-size: 28px;
}
.detail-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1px;
  overflow: hidden;
  border: 1px solid #e0e6f0;
  border-radius: 12px;
  background: #e0e6f0;
}
.detail-meta > div {
  min-width: 0;
  padding: 11px 13px;
  background: white;
}
.detail-meta dt {
  color: #8b95a7;
  font-size: 9px;
  font-weight: 700;
}
.detail-meta dd {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
  color: #303c54;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}
.packet-section {
  margin-top: 16px;
}
.packet-section h3 {
  margin: 0 0 7px;
  font-size: 12px;
}
.packet-section pre {
  min-height: 80px;
  max-height: 360px;
  margin: 0;
  overflow: auto;
  border: 1px solid #1d2a40;
  border-radius: 11px;
  padding: 14px;
  color: #c9d7ed;
  background: #101828;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.packet-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.packet-title span {
  color: #d14a5c;
  font-size: 10px;
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
  .filter-bar {
    grid-template-columns: 1fr;
  }
  .detail-meta {
    grid-template-columns: 1fr;
  }
}
</style>
