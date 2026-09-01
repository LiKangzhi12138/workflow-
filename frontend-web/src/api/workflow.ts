import http from './http'
import { collectAllPageRecords } from './pagination'

export type WorkflowStatus =
  | 'CREATED'
  | 'ACCEPTED'
  | 'PREPARING'
  | 'TRAINING_RUNNING'
  | 'VALIDATING'
  | 'COMPLETED'
  | 'FAILED'
  | 'WITHDRAWN'

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

export interface ServerUserOption {
  id: number
  username: string
  displayName?: string
  label?: string
}

export interface CreateWorkflowRequest {
  workflowName: string
  serverUserId: number
  clientModelAssetId: number
  clientModelCount: number
  yoloVersion: string
  isPublic?: number
  dpEnabled?: boolean
  dpEpsilon?: number
  dpDelta?: number
  dpClipNorm?: number
  dpNoiseMultiplier?: number
  shuffleEnabled?: boolean
  secureAggregationEnabled?: boolean
  secureAggregationMode?: 'PLAIN' | 'SECURE'
  remark?: string
}

export interface BindServerDatasetRequest {
  serverDatasetAssetId: number
}

export interface WorkflowListItem {
  id: number
  workflowCode: string
  workflowName: string
  initiatorUserId: number
  initiatorUsername: string
  serverUserId: number
  serverUsername: string

  clientModelAssetId?: number
  clientModelAssetName?: string
  clientModelVersion?: string
  clientModelCount?: number
  expectedModelCount?: number
  yoloVersion?: string
  activeUploadCount?: number
  receivedModelCount?: number
  collectedModelCount?: number
  uploadLimitReached?: boolean
  latestUploadStatus?: string
  federatedStatus?: string
  federatedStrategy?: string
  federatedModelAssetId?: number
  federatedModelAssetName?: string
  federatedModelAvailable?: boolean
  federatedModelUnavailableReason?: string
  federatedStartedAt?: string
  federatedFinishedAt?: string
  dpEnabled?: boolean
  dpEpsilon?: number
  dpDelta?: number
  dpClipNorm?: number
  dpNoiseMultiplier?: number
  dpStatus?: string
  dpSummary?: string
  shuffleEnabled?: boolean
  shuffleBatchNo?: string
  shuffleStatus?: string
  shuffleOrderSummary?: string
  shuffleStartedAt?: string
  shuffleFinishedAt?: string
  secureAggregationEnabled?: boolean
  secureAggregationMode?: string
  secureAggregationStatus?: string
  secureAggregationSummary?: string
  secureAggregationStartedAt?: string
  secureAggregationFinishedAt?: string

  serverDatasetAssetId?: number
  serverDatasetAssetName?: string
  serverDatasetDataFormat?: string
  serverDatasetTaskType?: string

  isPublic?: number

  status: WorkflowStatus
  currentStep: string
  progress: number
  pythonJobId?: string
  remark?: string
  resultRetentionStatus?: 'TEMPORARY' | 'SAVED' | 'DELETED'
  resultSavedAt?: string
  resultDeletedAt?: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowStepItem {
  stepNo: number
  stepCode: string
  stepName: string
  fromStatus?: string
  toStatus: string
  operatorUserId?: number
  operatorRole?: string
  message?: string
  createdAt: string
}

export interface WorkflowDetail {
  id: number
  workflowCode: string
  workflowName: string
  initiatorUserId: number
  initiatorUsername: string
  serverUserId: number
  serverUsername: string

  clientModelAssetId?: number
  clientModelAssetName?: string
  clientModelVersion?: string
  clientModelCount?: number
  expectedModelCount?: number
  yoloVersion?: string
  activeUploadCount?: number
  receivedModelCount?: number
  collectedModelCount?: number
  uploadLimitReached?: boolean
  latestUploadStatus?: string
  federatedStatus?: string
  federatedStrategy?: string
  federatedModelAssetId?: number
  federatedModelAssetName?: string
  federatedModelAvailable?: boolean
  federatedModelUnavailableReason?: string
  federatedStartedAt?: string
  federatedFinishedAt?: string
  dpEnabled?: boolean
  dpEpsilon?: number
  dpDelta?: number
  dpClipNorm?: number
  dpNoiseMultiplier?: number
  dpStatus?: string
  dpSummary?: string
  shuffleEnabled?: boolean
  shuffleBatchNo?: string
  shuffleStatus?: string
  shuffleOrderSummary?: string
  shuffleStartedAt?: string
  shuffleFinishedAt?: string
  secureAggregationEnabled?: boolean
  secureAggregationMode?: string
  secureAggregationStatus?: string
  secureAggregationSummary?: string
  secureAggregationStartedAt?: string
  secureAggregationFinishedAt?: string

  serverDatasetAssetId?: number
  serverDatasetAssetName?: string
  serverDatasetDataFormat?: string
  serverDatasetTaskType?: string

  isPublic?: number

  status: WorkflowStatus
  currentStep: string
  progress: number
  pythonJobId?: string
  remark?: string
  metricsJson?: string
  resultFilePath?: string
  resultRetentionStatus?: 'TEMPORARY' | 'SAVED' | 'DELETED'
  resultSavedAt?: string
  resultDeletedAt?: string
  errorMessage?: string
  createdAt: string
  updatedAt: string
  steps: WorkflowStepItem[]
}

export interface WorkflowListQuery {
  pageNum: number
  pageSize: number
  status?: string
}

export function listServerUsersApi() {
  return http.get<ApiResponse<ServerUserOption[]>, ApiResponse<ServerUserOption[]>>('/users/servers')
}

export function createWorkflowApi(data: CreateWorkflowRequest) {
  return http.post<ApiResponse<number>, ApiResponse<number>>('/workflows', data)
}

export function listWorkflowsApi(params: WorkflowListQuery) {
  return http.get<ApiResponse<PageResult<WorkflowListItem>>, ApiResponse<PageResult<WorkflowListItem>>>(
    '/workflows',
    { params }
  )
}

export function listAllWorkflowsApi(params: Pick<WorkflowListQuery, 'status'> = {}) {
  return collectAllPageRecords<WorkflowListItem>(async (pageNum, pageSize) => {
    const response = await listWorkflowsApi({ pageNum, pageSize, ...params })
    return response.data
  })
}

export function getWorkflowDetailApi(id: number) {
  return http.get<ApiResponse<WorkflowDetail>, ApiResponse<WorkflowDetail>>(`/workflows/${id}`)
}

export function withdrawWorkflowApi(id: number) {
  return http.post<ApiResponse<null>, ApiResponse<null>>(`/workflows/${id}/withdraw`)
}

export function advanceWorkflowApi(id: number) {
  return http.post<ApiResponse<null>, ApiResponse<null>>(`/workflows/${id}/advance`)
}

export function bindServerDatasetApi(id: number, data: BindServerDatasetRequest) {
  return http.post<ApiResponse<null>, ApiResponse<null>>(`/workflows/${id}/bind-server-dataset`, data)
}
export function startPythonJobApi(id: number) {
  return http.post<ApiResponse<null>, ApiResponse<null>>(`/workflows/${id}/start-python-job`)
}

export function saveWorkflowResultApi(id: number) {
  return http.post<ApiResponse<null>, ApiResponse<null>>(`/workflows/${id}/results/save`)
}

export function deleteSavedWorkflowResultApi(id: number) {
  return http.delete<ApiResponse<null>, ApiResponse<null>>(`/workflows/${id}/results`)
}

// ============ 模型上传相关 API ============

export interface ModelUploadInitRequest {
  workflowId: number
  modelAssetId?: number
  originalFilename: string
  fileSize: number
  fileSha256?: string
  replaceExisting?: boolean
}

export type WorkflowClientCryptoMode = 'WEB_CRYPTO' | 'SERVER_COMPAT'

export interface ModelUploadInitResponse {
  uploadId: number
  workflowId: number
  modelAssetId?: number
  aesKeyBase64?: string
  aesIvBase64?: string
  uploadToken?: string
  tokenExpireAt?: string
}

export interface UploadProgressVO {
  workflowId: number
  workflowName: string
  yoloVersion: string
  requiredModelCount: number
  activeUploadCount: number
  receivedModelCount: number
  collectedModelCount: number
  uploadLimitReached: boolean
  replaceAllowed: boolean
  latestUploadStatus?: string
  status: string
  dpEnabled?: boolean
  dpEpsilon?: number
  dpDelta?: number
  dpClipNorm?: number
  dpNoiseMultiplier?: number
  dpStatus?: string
  dpSummary?: string
  shuffleEnabled?: boolean
  shuffleBatchNo?: string
  shuffleStatus?: string
  shuffleOrderSummary?: string
  secureAggregationEnabled?: boolean
  secureAggregationMode?: string
  secureAggregationStatus?: string
  secureAggregationSummary?: string
  uploadRecords: Array<{
    uploadId: number
    uploaderName: string
    uploadStatus: string
    uploadedAt: string
    originalFilename: string
    clientDisplayName?: string
    modelAssetId?: number
    serverModelAssetId?: number
    federatedRound?: number
    federatedWeight?: number
    aggregationStatus?: string
    errorMessage?: string
  }>
}

/** 申请上传模型（获取AES密钥和上传令牌）*/
export const initModelUpload = (data: ModelUploadInitRequest) =>
  http.post<ApiResponse<ModelUploadInitResponse>, ApiResponse<ModelUploadInitResponse>>(
    '/workflow-uploads/init',
    data
  )

/** 上传加密模型文件 */
export const uploadEncryptedModelFile = (
  uploadId: number,
  uploadToken: string,
  formData: FormData,
  onProgress?: (percent: number) => void,
  clientCryptoMode: WorkflowClientCryptoMode = 'WEB_CRYPTO'
) =>
  http.post<ApiResponse<void>, ApiResponse<void>>(
    `/workflow-uploads/${uploadId}/file?uploadToken=${encodeURIComponent(uploadToken)}&clientCryptoMode=${clientCryptoMode}`,
    formData,
    {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => {
        if (onProgress && e.total) {
          onProgress(Math.round((e.loaded / e.total) * 100))
        }
      }
    }
  )

/** 查询工作流模型收集进度 */
export const getWorkflowUploadProgress = (workflowId: number) =>
  http.get<ApiResponse<UploadProgressVO>, ApiResponse<UploadProgressVO>>(
    `/workflow-uploads/progress/${workflowId}`
  )

/** 服务端解密已收到的模型文件 */
export const decryptUploadedModel = (uploadId: number) =>
  http.post<ApiResponse<void>, ApiResponse<void>>(
    `/workflow-uploads/${uploadId}/decrypt`
  )

/** 客户端绑定本地模型资产到工作流 */
export const bindLocalModelToWorkflow = (workflowId: number, modelAssetId: number) =>
  http.post<ApiResponse<ModelUploadInitResponse>, ApiResponse<ModelUploadInitResponse>>(
    '/workflow-uploads/bind-local',
    { workflowId, modelAssetId }
  )
