<template>
  <div class="app">
    <header>
      <div>
        <h1>반도체 파운드리 운영 대시보드</h1>
        <span class="subtitle">Semiconductor Foundry Operations</span>
      </div>
      <button class="docs-toggle-btn" @click="showDocs = !showDocs">
        {{ showDocs ? '← 대시보드' : '📄 문서 관리' }}
      </button>
    </header>

    <DocumentsManager v-if="showDocs" @close="showDocs = false" />

    <template v-else>
    <div v-if="loading" class="loading">데이터 로딩 중...</div>
    <div v-else-if="error" class="error">백엔드 연결 오류: {{ error }}</div>

    <div v-else class="content">
      <!-- KPI Cards -->
      <section class="kpi-grid">
        <div class="kpi-card">
          <div class="kpi-label">총 매출</div>
          <div class="kpi-value">${{ formatM(kpis?.totalRevenue) }}M</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">활성 주문</div>
          <div class="kpi-value">{{ kpis?.activeOrders }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">평균 수율</div>
          <div class="kpi-value">{{ kpis?.avgYieldRate }}%</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">진행 중 Lot</div>
          <div class="kpi-value">{{ kpis?.activeLots }}</div>
        </div>
      </section>

      <!-- Charts Row -->
      <section class="charts-row">
        <div class="chart-box">
          <h3>월별 매출 추이</h3>
          <Line v-if="trendChart.labels.length" :data="trendChart" :options="lineOpts" />
        </div>
        <div class="chart-box">
          <h3>고객별 매출</h3>
          <Bar v-if="customerChart.labels.length" :data="customerChart" :options="barOpts" />
        </div>
      </section>

      <section class="charts-row">
        <div class="chart-box">
          <h3>제품별 수율</h3>
          <Bar v-if="yieldChart.labels.length" :data="yieldChart" :options="yieldOpts" />
        </div>
        <div class="chart-box">
          <h3>불량 유형 분포</h3>
          <Doughnut v-if="defectChart.labels.length" :data="defectChart" :options="doughnutOpts" />
        </div>
      </section>

      <!-- Tables Row -->
      <section class="tables-row">
        <div class="table-box">
          <h3>최근 생산 Lot</h3>
          <table>
            <thead><tr><th>Lot No.</th><th>제품</th><th>수량</th><th>상태</th><th>수율</th></tr></thead>
            <tbody>
              <tr v-for="lot in productionLots" :key="lot.lot_number">
                <td>{{ lot.lot_number }}</td>
                <td>{{ lot.product }}</td>
                <td>{{ lot.quantity }}</td>
                <td><span :class="['badge', lot.status.toLowerCase()]">{{ lot.status }}</span></td>
                <td>{{ lot.yield_rate != null ? lot.yield_rate + '%' : '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="table-box">
          <h3>최근 주문</h3>
          <table>
            <thead><tr><th>고객</th><th>제품</th><th>금액</th><th>상태</th></tr></thead>
            <tbody>
              <tr v-for="o in orders" :key="o.id">
                <td>{{ o.customer }}</td>
                <td>{{ o.product }}</td>
                <td>${{ formatK(o.total_amount) }}K</td>
                <td><span :class="['badge', o.status.toLowerCase()]">{{ o.status }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- AI Chatbox -->
      <section class="chat-section">
        <AIChatbox :sendChat="sendChat" :uploadDocument="uploadDocument" />
      </section>
    </div>
    </template>
  </div>
</template>

<script setup>
import { onMounted, computed, ref } from 'vue'
import { Line, Bar, Doughnut } from 'vue-chartjs'
import {
  Chart as ChartJS, CategoryScale, LinearScale, PointElement,
  LineElement, BarElement, ArcElement, Tooltip, Legend, Filler
} from 'chart.js'
import { useDashboardAPI } from './composables/useDashboardAPI.js'
import { useChatApi } from './composables/useChatApi.js'
import AIChatbox from './components/AIChatbox.vue'
import DocumentsManager from './components/DocumentsManager.vue'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement,
  BarElement, ArcElement, Tooltip, Legend, Filler)

const { kpis, revenueByCustomer, revenueTrend, yieldByProduct,
        defectsByType, productionLots, orders, loading, error,
        fetchAll } = useDashboardAPI()
const { sendChat, uploadDocument } = useChatApi()

const showDocs = ref(false)

onMounted(fetchAll)

function formatM(v) { return v ? (v / 1_000_000).toFixed(2) : '0' }
function formatK(v) { return v ? (v / 1_000).toFixed(0) : '0' }

const COLORS = ['#1a1a2e','#16213e','#0f3460','#533483','#e94560','#2b9348','#f5a623','#4a90d9']

const trendChart = computed(() => ({
  labels: revenueTrend.value.map(r => r.month),
  datasets: [{
    label: '매출 ($)',
    data: revenueTrend.value.map(r => r.revenue),
    borderColor: '#0f3460',
    backgroundColor: 'rgba(15,52,96,0.15)',
    fill: true, tension: 0.4
  }]
}))

const customerChart = computed(() => ({
  labels: revenueByCustomer.value.map(r => r.name),
  datasets: [{
    label: '매출 ($)',
    data: revenueByCustomer.value.map(r => r.total_revenue),
    backgroundColor: COLORS
  }]
}))

const yieldChart = computed(() => ({
  labels: yieldByProduct.value.map(r => r.name),
  datasets: [{
    label: '평균 수율 (%)',
    data: yieldByProduct.value.map(r => parseFloat(r.avg_yield).toFixed(1)),
    backgroundColor: '#2b9348'
  }]
}))

const defectChart = computed(() => ({
  labels: defectsByType.value.map(r => r.defect_type),
  datasets: [{
    data: defectsByType.value.map(r => r.total_count),
    backgroundColor: COLORS
  }]
}))

const lineOpts = { responsive: true, plugins: { legend: { display: false } } }
const barOpts = { responsive: true, indexAxis: 'y', plugins: { legend: { display: false } } }
const yieldOpts = { responsive: true, plugins: { legend: { display: false } },
  scales: { x: { min: 80, max: 100 } } }
const doughnutOpts = { responsive: true, plugins: { legend: { position: 'right' } } }
</script>

<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f4f5f7; color: #333; }
.app { max-width: 1400px; margin: 0 auto; padding: 20px; }
header { background: #1a1a2e; color: #fff; padding: 20px 24px; border-radius: 8px; margin-bottom: 20px;
  display: flex; align-items: center; justify-content: space-between; gap: 16px; }
header h1 { font-size: 22px; font-weight: 700; }
header .subtitle { font-size: 13px; opacity: .7; }
.docs-toggle-btn {
  flex-shrink: 0;
  padding: 8px 16px;
  border-radius: 6px;
  border: 1px solid rgba(255,255,255,.25);
  background: rgba(255,255,255,.1);
  color: #fff;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}
.docs-toggle-btn:hover { background: rgba(255,255,255,.2); }
.loading, .error { text-align: center; padding: 60px; font-size: 16px; }
.error { color: #e94560; }
.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 20px; }
.kpi-card { background: #fff; border-radius: 8px; padding: 20px; box-shadow: 0 1px 4px rgba(0,0,0,.08); }
.kpi-label { font-size: 12px; color: #888; text-transform: uppercase; letter-spacing: .5px; }
.kpi-value { font-size: 28px; font-weight: 700; color: #1a1a2e; margin-top: 6px; }
.charts-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; }
.chart-box { background: #fff; border-radius: 8px; padding: 20px; box-shadow: 0 1px 4px rgba(0,0,0,.08); }
.chart-box h3 { font-size: 14px; color: #555; margin-bottom: 14px; }
.tables-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; }
.table-box { background: #fff; border-radius: 8px; padding: 20px; box-shadow: 0 1px 4px rgba(0,0,0,.08); overflow-x: auto; }
.table-box h3 { font-size: 14px; color: #555; margin-bottom: 14px; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th { background: #f8f9fa; padding: 8px 10px; text-align: left; color: #666; font-weight: 600; border-bottom: 1px solid #eee; }
td { padding: 7px 10px; border-bottom: 1px solid #f0f0f0; }
.badge { padding: 2px 8px; border-radius: 12px; font-size: 11px; font-weight: 600; }
.badge.completed, .badge.delivered { background: #d4edda; color: #155724; }
.badge.in_progress, .badge.in_production { background: #d1ecf1; color: #0c5460; }
.badge.queued, .badge.pending { background: #fff3cd; color: #856404; }
.badge.scrapped, .badge.cancelled { background: #f8d7da; color: #721c24; }
.badge.shipped { background: #cce5ff; color: #004085; }
.chat-section { margin-bottom: 20px; }
@media (max-width: 900px) {
  .kpi-grid, .charts-row, .tables-row { grid-template-columns: 1fr; }
}
</style>
