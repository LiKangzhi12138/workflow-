import type { AxiosProgressEvent } from 'axios'
import http from './http'
import { collectAllPageRecords } from './pagination'

export interface ApiResponse<T> {
  code: string | number
  message: string
  data: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

export type DatasetRecordMode = 'PATH_REGISTRY' | 'FORMAL_ASSET'

export interface DatasetRegistryRequest {
  assetName: string
  datasetType: string
  sampleCount?: number
  filePath?: string
  isPublic?: number
  description?: string
}

export interface UploadDatasetAssetPayload {
  assetName: string
  datasetType: string
  sampleCount?: number
  isPublic?: number
  description?: string
  file: File
}

export interface UpdateDatasetAssetRequest {
  assetName?: string
  datasetType?: string
  sampleCount?: number
  filePath?: string
  isPublic?: number
  description?: string
  status?: string
}

export interface DatasetAssetItem {
  id: number
  assetCode: string
  assetName: string
  datasetType: string
  dataFormat: string
  taskType: string
  sampleCount?: number
  fileName?: string
  filePath?: string
  sourcePath?: string
  sourceType?: string
  importMode?: string
  recordMode?: DatasetRecordMode
  fileSize?: number
  imageCount?: number
  filePathValidated?: number
  lastCheckAt?: string
  lastCheckStatus?: string
  lastCheckMessage?: string
  isPublic: number
  status: string
  description?: string
  createdAt: string
  updatedAt: string
}

export interface DatasetAssetDetail extends DatasetAssetItem {
  ownerUserId: number
  ownerRoleCode: string
}

export function createDatasetAssetApi(data: DatasetRegistryRequest) {
  return http.post<ApiResponse<number>, ApiResponse<number>>('/datasets', data)
}

export function importDatasetAssetApi(data: DatasetRegistryRequest) {
  return http.post<ApiResponse<number>, ApiResponse<number>>('/datasets/server-import', data)
}

export function uploadDatasetAssetApi(
  payload: UploadDatasetAssetPayload,
  onProgress?: (percent: number) => void
) {
  const formData = new FormData()
  formData.append('assetName', payload.assetName)
  formData.append('datasetType', payload.datasetType)
  if (payload.sampleCount != null) {
    formData.append('sampleCount', String(payload.sampleCount))
  }
  if (payload.isPublic != null) {
    formData.append('isPublic', String(payload.isPublic))
  }
  if (payload.description) {
    formData.append('description', payload.description)
  }
  formData.append('file', payload.file)

  return http.post<ApiResponse<number>, ApiResponse<number>>('/datasets/upload', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    onUploadProgress: (event: AxiosProgressEvent) => {
      if (!event.total || !onProgress) return
      onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
    }
  })
}

export function listDatasetAssetsApi(params: {
  pageNum: number
  pageSize: number
  keyword?: string
  validated?: boolean
  recordMode?: DatasetRecordMode
}) {
  return http.get<ApiResponse<PageResult<DatasetAssetItem>>, ApiResponse<PageResult<DatasetAssetItem>>>(
    '/datasets',
    { params }
  )
}

export function listAllDatasetAssetsApi(
  params: {
    keyword?: string
    validated?: boolean
    recordMode?: DatasetRecordMode
  } = {}
) {
  return collectAllPageRecords<DatasetAssetItem>(async (pageNum, pageSize) => {
    const response = await listDatasetAssetsApi({ pageNum, pageSize, ...params })
    return response.data
  })
}

export function getDatasetAssetDetailApi(id: number) {
  return http.get<ApiResponse<DatasetAssetDetail>, ApiResponse<DatasetAssetDetail>>(`/datasets/${id}`)
}

export function updateDatasetAssetApi(id: number, data: UpdateDatasetAssetRequest) {
  return http.put<ApiResponse<null>, ApiResponse<null>>(`/datasets/${id}`, data)
}

export function checkDatasetAssetApi(id: number) {
  return http.post<ApiResponse<null>, ApiResponse<null>>(`/datasets/${id}/check`)
}

export function deleteDatasetAssetApi(id: number) {
  return http.delete<ApiResponse<null>, ApiResponse<null>>(`/datasets/${id}`)
}
