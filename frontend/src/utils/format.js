export function formatBytes(bytes) {
  const size = Number(bytes || 0)
  if (size <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(size) / Math.log(1024)), units.length - 1)
  const value = size / Math.pow(1024, index)
  return `${index === 0 ? value : value.toFixed(value >= 100 ? 0 : 1)} ${units[index]}`
}

export function quotaPercent(quota) {
  const total = Number(quota?.totalQuota || 0)
  if (!total) return 0
  return Math.min(100, Math.round((Number(quota.usedSize || 0) / total) * 100))
}

export function iconOf(file) {
  if (file.folder) return 'Folder'
  if (file.kind === 'image') return 'Picture'
  if (file.kind === 'text') return 'Document'
  return 'Files'
}

export function kindClass(file) {
  if (file.folder) return 'kind-folder'
  if (file.kind === 'image') return 'kind-image'
  if (file.kind === 'text') return 'kind-text'
  return 'kind-file'
}
