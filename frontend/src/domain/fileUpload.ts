export const MAX_UPLOAD_FILES = 20
export const MAX_UPLOAD_FILE_BYTES = 500 * 1024 * 1024
export const MAX_UPLOAD_SELECTION_BYTES = 1024 * 1024 * 1024

type UploadCandidate = { name: string; size: number }

export function formatUploadSize(bytes: number) {
  if (bytes >= 1024 * 1024 * 1024) return `${trim(bytes / (1024 * 1024 * 1024))} GB`
  if (bytes >= 1024 * 1024) return `${trim(bytes / (1024 * 1024))} MB`
  if (bytes >= 1024) return `${trim(bytes / 1024)} KB`
  return `${bytes} B`
}

export function uploadSelectionError(files: readonly UploadCandidate[]) {
  if (files.length > MAX_UPLOAD_FILES) {
    return `一次最多上传 ${MAX_UPLOAD_FILES} 个文件，本次选择了 ${files.length} 个。请分批上传。`
  }
  const oversized = files.find(file => file.size > MAX_UPLOAD_FILE_BYTES)
  if (oversized) {
    return `“${oversized.name}”超过单文件 ${formatUploadSize(MAX_UPLOAD_FILE_BYTES)} 的上限。`
  }
  const totalBytes = files.reduce((sum, file) => sum + Math.max(0, file.size), 0)
  if (totalBytes > MAX_UPLOAD_SELECTION_BYTES) {
    return `本次文件合计 ${formatUploadSize(totalBytes)}，超过 ${formatUploadSize(MAX_UPLOAD_SELECTION_BYTES)} 的上限。请分批上传。`
  }
  return ''
}

function trim(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}
