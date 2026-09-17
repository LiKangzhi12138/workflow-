import type { AvailableModelDefinition } from '@/api/modelDefinition'
import type { CreateWorkflowRequest } from '@/api/workflow'

export type ModelSelectionMode = 'loading' | 'definition' | 'legacy' | 'error'

export function resolveModelSelectionMode(registryEnabled: boolean): ModelSelectionMode {
  return registryEnabled ? 'definition' : 'legacy'
}

export function normalizeAvailableModelDefinitions(
  definitions: AvailableModelDefinition[] | null | undefined
): AvailableModelDefinition[] {
  return Array.isArray(definitions)
    ? definitions.filter(
        (definition) =>
          definition.availability?.available === true &&
          definition.workflowSelectable !== false
      )
    : []
}

export function resolveModelSelectionFailure(message?: string) {
  return {
    mode: 'error' as const,
    message: message || '模型能力检查失败，请稍后重试'
  }
}

export function buildWorkflowModelSelectionPayload(
  mode: ModelSelectionMode,
  modelDefinitionId?: number,
  yoloVersion?: string
): Pick<CreateWorkflowRequest, 'modelDefinitionId' | 'yoloVersion'> {
  if (mode === 'definition') {
    if (!modelDefinitionId || modelDefinitionId <= 0) {
      throw new Error('请选择服务器当前可用的模型')
    }
    return { modelDefinitionId }
  }
  if (mode === 'legacy') {
    if (!yoloVersion) {
      throw new Error('请选择 YOLO 版本')
    }
    return { yoloVersion }
  }
  throw new Error('当前模型选择不可用')
}
