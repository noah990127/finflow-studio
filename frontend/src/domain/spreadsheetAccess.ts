export const OFFICE_DIRECT_BYTES = 50 * 1024 * 1024
export const OFFICE_RECOMMENDED_MAX_BYTES = 100 * 1024 * 1024

export type SpreadsheetAccessMode = 'DIRECT' | 'CONFIRM' | 'CONVERT'

export function spreadsheetAccessMode(extension: string, sizeBytes: number): SpreadsheetAccessMode {
  if (!['xlsx', 'xlsm'].includes(extension.toLowerCase())) return 'DIRECT'
  if (sizeBytes <= OFFICE_DIRECT_BYTES) return 'DIRECT'
  if (sizeBytes <= OFFICE_RECOMMENDED_MAX_BYTES) return 'CONFIRM'
  return 'CONVERT'
}

export function formatFileSize(bytes: number) {
  if (bytes >= 1024 * 1024 * 1024) return `${trim(bytes / (1024 * 1024 * 1024))} GB`
  if (bytes >= 1024 * 1024) return `${trim(bytes / (1024 * 1024))} MB`
  if (bytes >= 1024) return `${trim(bytes / 1024)} KB`
  return `${bytes} B`
}

function trim(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}
