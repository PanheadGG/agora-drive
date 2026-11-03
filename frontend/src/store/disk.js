import { reactive } from 'vue'
import { api } from '../api'

export const disk = reactive({
  keyword: '',
  view: localStorage.getItem('acd_view') || 'grid',
  quota: { totalQuota: 0, usedSize: 0 },
})

export function setView(view) {
  disk.view = view
  localStorage.setItem('acd_view', view)
}

export async function refreshQuota() {
  const quota = await api.quota()
  disk.quota = quota
}
