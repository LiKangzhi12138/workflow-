export function resolveValidPage(currentPage: number, pageSize: number, total: number) {
  const safePageSize = Math.max(1, pageSize)
  const lastPage = Math.max(1, Math.ceil(Math.max(0, total) / safePageSize))
  return Math.min(Math.max(1, currentPage), lastPage)
}
