import axios from 'axios'
import { ref } from 'vue'

const BASE = '/api'

export function useDashboardAPI() {
  const kpis = ref(null)
  const revenueByCustomer = ref([])
  const revenueTrend = ref([])
  const yieldByProduct = ref([])
  const defectsByType = ref([])
  const productionLots = ref([])
  const orders = ref([])
  const loading = ref(false)
  const error = ref(null)

  async function fetchAll() {
    loading.value = true
    error.value = null
    try {
      const [k, rc, rt, yp, dt, pl, or_] = await Promise.all([
        axios.get(`${BASE}/kpis`),
        axios.get(`${BASE}/revenue-by-customer`),
        axios.get(`${BASE}/revenue-trend`),
        axios.get(`${BASE}/yield-by-product`),
        axios.get(`${BASE}/defects-by-type`),
        axios.get(`${BASE}/production-lots`),
        axios.get(`${BASE}/orders`)
      ])
      kpis.value = k.data
      revenueByCustomer.value = rc.data
      revenueTrend.value = rt.data
      yieldByProduct.value = yp.data
      defectsByType.value = dt.data
      productionLots.value = pl.data
      orders.value = or_.data
    } catch (e) {
      error.value = e.message
    } finally {
      loading.value = false
    }
  }

  return { kpis, revenueByCustomer, revenueTrend, yieldByProduct,
           defectsByType, productionLots, orders, loading, error,
           fetchAll }
}
