export interface PageRecords<T> {
  records?: T[]
  total?: number
  pages?: number
}

export async function collectAllPageRecords<T>(
  fetchPage: (pageNum: number, pageSize: number) => Promise<PageRecords<T> | null | undefined>,
  pageSize: number = 100
) {
  const records: T[] = []
  let pageNum = 1

  while (true) {
    const page = await fetchPage(pageNum, pageSize)
    const pageRecords = page?.records || []
    records.push(...pageRecords)

    const total = Number(page?.total ?? records.length)
    const pages = Number(page?.pages ?? Math.ceil(total / pageSize))
    if (pageRecords.length === 0 || records.length >= total || pageNum >= pages) {
      return records
    }
    pageNum += 1
  }
}
