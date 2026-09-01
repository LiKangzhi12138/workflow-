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

export type ModelRecordMode = 'PATH_REGISTRY' | 'FORMAL_ASSET'

export interface ModelRegistryRequest {
  assetName: string
  modelType: string
  modelVersion: string
  taskType: string
  yoloVersion?: string
  filePath?: string
  isPublic?: number
  description?: string
}

export interface ServerImportModelAssetRequest extends ModelRegistryRequest {}

export interface UploadModelAssetPayload {
  assetName: string
  modelType: string
  modelVersion: string
  taskType: string
  yoloVersion?: string
  isPublic?: number
  description?: string
  file: File
}

export interface UpdateModelAssetRequest {
  assetName?: string
  modelType?: string
  modelVersion?: string
  taskType?: string
  yoloVersion?: string
  filePath?: string
  isPublic?: number
  description?: string
  status?: string
}

export interface ModelAssetItem {
  id: number
  assetCode: string
  assetName: string
  modelType: string
  modelVersion: string
  taskType: string
  fileName?: string
  filePath?: string
  sourcePath?: string
  sourceType?: string
  importMode?: string
  recordMode?: ModelRecordMode
  fileSize?: number
  filePathValidated?: number
  lastCheckAt?: string
  lastCheckStatus?: string
  lastCheckMessage?: string
  yoloVersion?: string
  isPublic: number
  status: string
  description?: string
  createdAt: string
  updatedAt: string
}

export interface ModelAssetDetail extends ModelAssetItem {
  ownerUserId: number
  ownerRoleCode: string
}

export function createModelAssetApi(data: ModelRegistryRequest) {
  return http.post<ApiResponse<number>, ApiResponse<number>>('/models', data)
}

export function importModelAssetApi(data: ServerImportModelAssetRequest) {
  return http.post<ApiResponse<number>, ApiResponse<number>>('/models/server-import', data)
}

export function uploadModelAssetApi(
  payload: UploadModelAssetPayload,
  onProgress?: (percent: number) => void
) {
  const formData = new FormData()
  formData.append('assetName', payload.assetName)
  formData.append('modelType', payload.modelType)
  formData.append('modelVersion', payload.modelVersion)
  formData.append('taskType', payload.taskType)
  if (payload.yoloVersion) {
    formData.append('yoloVersion', payload.yoloVersion)
  }
  if (payload.isPublic != null) {
    formData.append('isPublic', String(payload.isPublic))
  }
  if (payload.description) {
    formData.append('description', payload.description)
  }
  formData.append('file', payload.file)

  return http.post<ApiResponse<number>, ApiResponse<number>>('/models/upload', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    onUploadProgress: (event: AxiosProgressEvent) => {
      if (!event.total || !onProgress) return
      onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
    }
  })
}

export function listModelAssetsApi(params: {
  pageNum: number
  pageSize: number
  keyword?: string
  validated?: boolean
  recordMode?: ModelRecordMode
}) {
  return http.get<ApiResponse<PageResult<ModelAssetItem>>, ApiResponse<PageResult<ModelAssetItem>>>(
    '/models',
    { params }
  )
}

export function listAllModelAssetsApi(
  params: {
    keyword?: string
    validated?: boolean
    recordMode?: ModelRecordMode
  } = {}
) {
  return collectAllPageRecords<ModelAssetItem>(async (pageNum, pageSize) => {
    const response = await listModelAssetsApi({ pageNum, pageSize, ...params })
    return response.data
  })
}

export function getModelAssetDetailApi(id: number) {
  return http.get<ApiResponse<ModelAssetDetail>, ApiResponse<ModelAssetDetail>>(`/models/${id}`)
}

export function updateModelAssetApi(id: number, data: UpdateModelAssetRequest) {
  return http.put<ApiResponse<null>, ApiResponse<null>>(`/models/${id}`, data)
}

export function checkModelAssetApi(id: number) {
  return http.post<ApiResponse<null>, ApiResponse<null>>(`/models/${id}/check`)
}

export function deleteModelAssetApi(id: number) {
  return http.delete<ApiResponse<null>, ApiResponse<null>>(`/models/${id}`)
}
