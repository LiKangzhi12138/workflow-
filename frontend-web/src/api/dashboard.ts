import http from './http'

export interface ApiResponse<T> {
  code: string | number
  message: string
  data: T
}

export interface DashboardMetrics {
  totalWorkflows: number
  pendingServerReceiveCount: number
  pendingReceiveWorkflowCount: number
  pendingDecryptCount: number
  pendingValidationCount: number
  completedValidationCount: number
  latestAccuracy?: number | null
  latestAccuracySourceType?: string | null
  latestAccuracySourceLabel?: string | null
}

export interface DashboardWorkflowCard {
  id: number
  workflowCode?: string
  workflowName: string
  clientModelAssetName?: string | null
  serverDatasetAssetName?: string | null
  status: string
  currentStep?: string | null
  updatedAt?: string | null
  receivedByServer?: boolean | null
  decryptCompleted?: boolean | null
  validationReady?: boolean | null
  validationReadyReason?: string | null
  federatedModelAvailable?: boolean | null
  federatedModelUnavailableReason?: string | null
  requiredModelCount?: number | null
  receivedModelCount?: number | null
  collectedModelCount?: number | null
  latestUploadStatus?: string | null
}

export interface DashboardValidationCard {
  sourceType: 'WORKFLOW' | 'STANDALONE'
  workflowId?: number | null
  validationId?: number | null
  taskName: string
  modelName?: string | null
  datasetName?: string | null
  status: string
  accuracy?: number | null
  precision?: number | null
  recall?: number | null
  updatedAt?: string | null
  sourceLabel?: string | null
}

export interface DashboardModelCard {
  totalModelCount: number
  latestModelId?: number | null
  latestModelName?: string | null
  latestModelVersion?: string | null
  latestModelStatus?: string | null
  latestModelCreatedAt?: string | null
}

export interface DashboardOverview {
  roleCode: 'CLIENT' | 'SERVER'
  metrics: DashboardMetrics
  latestWorkflow?: DashboardWorkflowCard | null
  recentValidation?: DashboardValidationCard | null
  modelOverview: DashboardModelCard
}

export const getDashboardOverview = () =>
  http.get<ApiResponse<DashboardOverview>, ApiResponse<DashboardOverview>>('/dashboard/overview', {
    params: { _t: Date.now() },
    headers: {
      'Cache-Control': 'no-cache',
      Pragma: 'no-cache'
    }
  })
