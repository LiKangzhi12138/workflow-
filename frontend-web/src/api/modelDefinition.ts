import http from './http'

export interface ApiResponse<T> {
  code: string | number
  message: string
  data: T
}

export interface ModelDefinitionAvailability {
  available: boolean
  state?: string
  reasonCode?: string
  message?: string
}

export interface AvailableModelDefinition {
  id: number
  code: string
  displayName: string
  modelFamily: string
  version: string | null
  variant: string | null
  taskType: string | null
  framework: string
  frameworkVersion: string
  classes: string[]
  runtimeProfileId: string
  workflowSelectable?: boolean
  availability: ModelDefinitionAvailability
}

export interface ModelDefinitionRegistryStatus {
  enabled: boolean
}

export function getModelDefinitionRegistryStatusApi() {
  return http.get<
    ApiResponse<ModelDefinitionRegistryStatus>,
    ApiResponse<ModelDefinitionRegistryStatus>
  >('/model-definitions/registry-status')
}

export function listAvailableModelDefinitionsApi() {
  return http.get<
    ApiResponse<AvailableModelDefinition[]>,
    ApiResponse<AvailableModelDefinition[]>
  >('/model-definitions/available')
}
