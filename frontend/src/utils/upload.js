import { api } from '../api'

const SMALL_LIMIT = 5 * 1024 * 1024

/**
 * 小文件走 /upload/small 直传；超过 5MB 走分片上传（与后端 app.upload.chunk-size 对齐）。
 */
export async function uploadFile(file, parentId, onProgress = () => {}) {
  if (file.size <= SMALL_LIMIT) {
    onProgress(30)
    const dto = await api.uploadSmall(file, parentId)
    onProgress(100)
    return dto
  }

  const init = await api.initChunk({ fileName: file.name, totalSize: file.size, parentId })
  if (init.instant) {
    onProgress(100)
    return init.file
  }

  const { taskId, chunkSize, totalChunk } = init
  for (let index = 0; index < totalChunk; index += 1) {
    const start = index * chunkSize
    const blob = file.slice(start, Math.min(start + chunkSize, file.size))
    await api.uploadChunk(taskId, index, blob)
    onProgress(Math.round(((index + 1) / totalChunk) * 90))
  }

  const dto = await api.mergeChunk({ taskId, parentId, fileName: file.name })
  onProgress(100)
  return dto
}

export function downloadBlob(blob, fileName) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
