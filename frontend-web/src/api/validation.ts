import http from './http'
import type { DatasetAssetItem, PageResult as DatasetPageResult } from './dataset'
import type { ModelAssetItem, PageResult as ModelPageResult } from './model'
import type { WorkflowDetail } from './workflow'

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

export interface CreateValidationRequest {
  modelAssetId: number
  datasetAssetId: number
  algorithmType?: string
}

export interface TemporaryValidationRequest {
  modelFile: File
  datasetArchive: File
  algorithmType?: string
}

export interface ValidationMetrics {
  mAP?: number
  precision?: number
  recall?: number
  map50?: number
  map50_95?: number
  accuracy?: number
  totalImages?: number
  cropDetections?: number
  livestockDetections?: number
  perClassResults?: Record<string, any>
}

export interface StandaloneValidationVO {
  id: number
  validationCode: string
  validationMode: 'STANDALONE' | 'WORKFLOW'
  userId: number
  modelAssetId?: number | null
  modelAssetName?: string
  modelPath: string
  modelYoloVersion?: string
  modelStatus?: string
  datasetAssetId?: number | null
  datasetAssetName?: string
  datasetPath: string
  datasetStatus?: string
  datasetSampleCount?: number
  datasetImageCount?: number
  algorithmType: string
  inputMode?: string
  retainInput?: number
  inputCleanupStatus?: string
  inputCleanedAt?: string
  status: string
  progress?: number
  pythonJobId?: string
  metricsJson?: string
  resultFilePath?: string
  qualityLabel?: string
  errorMessage?: string
  finishedAt?: string
  createdAt: string
  updatedAt: string
}

export interface ValidationResultPrediction {
  label: string
  confidence?: number
  category?: string
  bbox?: number[]
}

export interface ValidationResultSample {
  sampleIndex: number
  imageName?: string
  imageUrl?: string
  isAnnotated?: boolean
  annotatedImageAvailable?: boolean
  predictionCount?: number
  predictions: ValidationResultPrediction[]
}

export interface ValidationResultClassItem {
  className: string
  category?: string
  count?: number
  avgConfidence?: number
}

export interface ValidationResultView {
  validationMode: 'STANDALONE' | 'WORKFLOW'
  validationId?: number
  workflowId?: number
  validationCode?: string
  modelName?: string
  datasetName?: string
  algorithmType?: string
  status: string
  progress?: number
  pythonJobId?: string
  datasetSampleCount?: number
  datasetImageCount?: number
  accuracy?: number
  precision?: number
  recall?: number
  map50?: number
  map50_95?: number
  fallback?: boolean
  fallbackReason?: string
  qualityLabel?: string
  errorMessage?: string
  resultFileAvailable?: boolean
  visualizationMode?: string
  resultRetentionStatus?: 'LATEST_ONLY' | 'TEMPORARY' | 'SAVED' | 'DELETED'
  emptyVisualizationReason?: string
  startedAt?: string
  finishedAt?: string
  classResults?: ValidationResultClassItem[]
  sampleResults?: ValidationResultSample[]
}

export const submitStandaloneValidation = (data: CreateValidationRequest) =>
  http.post<ApiResponse<StandaloneValidationVO>, ApiResponse<StandaloneValidationVO>>(
    '/validations/standalone',
    data
  )

export const submitTemporaryStandaloneValidation = (payload: TemporaryValidationRequest) => {
  const formData = new FormData()
  formData.append('modelFile', payload.modelFile)
  formData.append('datasetArchive', payload.datasetArchive)
  if (payload.algorithmType) {
    formData.append('algorithmType', payload.algorithmType)
  }
  return http.post<ApiResponse<StandaloneValidationVO>, ApiResponse<StandaloneValidationVO>>(
    '/validations/standalone/temp-upload',
    formData,
    {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    }
  )
}

export const getStandaloneValidationDetail = (id: number) =>
  http.get<ApiResponse<StandaloneValidationVO>, ApiResponse<StandaloneValidationVO>>(
    `/validations/standalone/${id}`
  )

export const getStandaloneValidationResultView = (id: number) =>
  http.get<ApiResponse<ValidationResultView>, ApiResponse<ValidationResultView>>(
    `/validation-results/standalone/${id}`
  )

export const getWorkflowValidationResultView = (workflowId: number) =>
  http.get<ApiResponse<ValidationResultView>, ApiResponse<ValidationResultView>>(
    `/validation-results/workflows/${workflowId}`
  )

export const listMyStandaloneValidations = (limit: number = 10) =>
  http.get<ApiResponse<StandaloneValidationVO[]>, ApiResponse<StandaloneValidationVO[]>>(
    '/validations/standalone/my',
    { params: { limit } }
  )

export const createStandaloneValidation = (data: CreateValidationRequest) =>
  http.post<ApiResponse<number>, ApiResponse<number>>('/validations', data)

export const startValidation = (id: number) =>
  http.post<ApiResponse<void>, ApiResponse<void>>(`/validations/${id}/start`)

export const getValidationDetail = (id: number) =>
  getStandaloneValidationDetail(id)

export const pageValidations = (pageNum: number = 1, pageSize: number = 10) =>
  http.get<ApiResponse<PageResult<StandaloneValidationVO>>, ApiResponse<PageResult<StandaloneValidationVO>>>(
    '/validations',
    { params: { pageNum, pageSize } }
  )

export const getMyModelAssets = (params?: {
  keyword?: string
  validated?: boolean
  recordMode?: 'PATH_REGISTRY' | 'FORMAL_ASSET'
}) =>
  http.get<ApiResponse<ModelPageResult<ModelAssetItem>>, ApiResponse<ModelPageResult<ModelAssetItem>>>('/models', {
    params: {
      pageNum: 1,
      pageSize: 100,
      keyword: params?.keyword,
      validated: params?.validated,
      recordMode: params?.recordMode
    }
  })

export const getMyDatasetAssets = (params?: {
  keyword?: string
  validated?: boolean
  recordMode?: 'PATH_REGISTRY' | 'FORMAL_ASSET'
}) =>
  http.get<ApiResponse<DatasetPageResult<DatasetAssetItem>>, ApiResponse<DatasetPageResult<DatasetAssetItem>>>('/datasets', {
    params: {
      pageNum: 1,
      pageSize: 100,
      keyword: params?.keyword,
      validated: params?.validated,
      recordMode: params?.recordMode
    }
  })

export const getWorkflowDetailForValidation = (workflowId: number) =>
  http.get<ApiResponse<WorkflowDetail>, ApiResponse<WorkflowDetail>>(`/workflows/${workflowId}`)

export const startWorkflowValidation = (workflowId: number) =>
  http.post<ApiResponse<void>, ApiResponse<void>>(`/workflows/${workflowId}/start-python-job`)
