export interface WorkflowModelMetadata {
  modelDefinitionId?: number | null
  modelDefinitionCode?: string | null
  modelDefinitionDisplayName?: string | null
  modelDefinitionFamily?: string | null
  modelDefinitionVersion?: string | null
  yoloVersion?: string | null
}

function normalizeOptionalText(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const normalized = value.trim()
  return normalized || null
}

export function normalizeWorkflowModelMetadata<T extends WorkflowModelMetadata>(workflow: T): T {
  if (!workflow) return workflow
  const modelDefinitionId = workflow.modelDefinitionId
  return {
    ...workflow,
    modelDefinitionId:
      typeof modelDefinitionId === 'number' && Number.isSafeInteger(modelDefinitionId)
        ? modelDefinitionId
        : null,
    modelDefinitionCode: normalizeOptionalText(workflow.modelDefinitionCode),
    modelDefinitionDisplayName: normalizeOptionalText(workflow.modelDefinitionDisplayName),
    modelDefinitionFamily: normalizeOptionalText(workflow.modelDefinitionFamily),
    modelDefinitionVersion: normalizeOptionalText(workflow.modelDefinitionVersion),
    yoloVersion: normalizeOptionalText(workflow.yoloVersion)
  }
}

export function normalizeWorkflowModelMetadataList<T extends WorkflowModelMetadata>(
  workflows: T[] | null | undefined
): T[] {
  return Array.isArray(workflows) ? workflows.map(normalizeWorkflowModelMetadata) : []
}

export function getWorkflowModelDisplayName(
  workflow: WorkflowModelMetadata | null | undefined
): string {
  const family = normalizeOptionalText(workflow?.modelDefinitionFamily)
  const version = normalizeOptionalText(workflow?.modelDefinitionVersion)
  if (family) {
    return version ? `${family} / ${version}` : family
  }

  return (
    normalizeOptionalText(workflow?.modelDefinitionDisplayName) ||
    normalizeOptionalText(workflow?.yoloVersion) ||
    '未配置模型'
  )
}
