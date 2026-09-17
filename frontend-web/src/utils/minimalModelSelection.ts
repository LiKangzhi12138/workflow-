import type { AvailableModelDefinition } from '@/api/modelDefinition'

export type ModelSelectionDimension = 'modelFamily' | 'version' | 'variant' | 'taskType'

export interface MinimalModelSelectionAnswers {
  modelFamily?: string
  version?: string
  variant?: string
  taskType?: string
  definitionId?: number
}

export interface ModelSelectionOption<T extends string | number = string> {
  label: string
  value: T
}

export interface ModelSelectionControl<T extends string | number = string> {
  kind: 'static' | 'select'
  value?: T
  options: ModelSelectionOption<T>[]
}

export interface MinimalModelSelectionPlan {
  family: ModelSelectionControl<string>
  version?: ModelSelectionControl<string>
  variant?: ModelSelectionControl<string>
  taskType?: ModelSelectionControl<string>
  definition?: ModelSelectionControl<number>
  selectedDefinition?: AvailableModelDefinition
}

function normalizedText(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const normalized = value.trim()
  return normalized || undefined
}

function uniqueValues(
  definitions: AvailableModelDefinition[],
  dimension: ModelSelectionDimension
): string[] {
  return Array.from(
    new Set(
      definitions
        .map((definition) => normalizedText(definition[dimension]))
        .filter((value): value is string => Boolean(value))
    )
  )
}

function hasCompleteValues(
  definitions: AvailableModelDefinition[],
  dimension: ModelSelectionDimension
): boolean {
  return definitions.every((definition) => Boolean(normalizedText(definition[dimension])))
}

function selectByDimension(
  definitions: AvailableModelDefinition[],
  dimension: ModelSelectionDimension,
  answer: string | undefined
): {
  definitions: AvailableModelDefinition[]
  control?: ModelSelectionControl<string>
  pending: boolean
} {
  const values = uniqueValues(definitions, dimension)

  if (definitions.length === 1) {
    return { definitions, pending: false }
  }

  if (!hasCompleteValues(definitions, dimension) || values.length <= 1) {
    return { definitions, pending: false }
  }

  const options = values.map((value) => ({ label: value, value }))
  if (!answer || !values.includes(answer)) {
    return {
      definitions: [],
      control: { kind: 'select', options },
      pending: true
    }
  }

  return {
    definitions: definitions.filter(
      (definition) => normalizedText(definition[dimension]) === answer
    ),
    control: { kind: 'select', value: answer, options },
    pending: false
  }
}

function definitionOptions(
  definitions: AvailableModelDefinition[]
): ModelSelectionOption<number>[] {
  const labelCounts = new Map<string, number>()
  for (const definition of definitions) {
    const label = normalizedText(definition.displayName) || definition.code
    labelCounts.set(label, (labelCounts.get(label) || 0) + 1)
  }

  return definitions.map((definition) => {
    const baseLabel = normalizedText(definition.displayName) || definition.code
    const label = labelCounts.get(baseLabel) === 1
      ? baseLabel
      : `${baseLabel} (${definition.code})`
    return { label, value: definition.id }
  })
}

export function buildMinimalModelSelectionPlan(
  definitions: AvailableModelDefinition[],
  answers: MinimalModelSelectionAnswers = {}
): MinimalModelSelectionPlan {
  const familyValues = uniqueValues(definitions, 'modelFamily')
  const familyOptions = familyValues.map((value) => ({ label: value, value }))
  const selectedFamily = familyValues.length === 1
    ? familyValues[0]
    : normalizedText(answers.modelFamily)
  const family: ModelSelectionControl<string> = familyValues.length === 1
    ? { kind: 'static', value: selectedFamily, options: familyOptions }
    : { kind: 'select', value: selectedFamily, options: familyOptions }

  if (!selectedFamily || !familyValues.includes(selectedFamily)) {
    return { family }
  }

  let candidates = definitions.filter(
    (definition) => normalizedText(definition.modelFamily) === selectedFamily
  )
  const plan: MinimalModelSelectionPlan = { family }

  const commonVersion = uniqueValues(candidates, 'version')
  if (commonVersion.length === 1 && hasCompleteValues(candidates, 'version')) {
    plan.version = {
      kind: 'static',
      value: commonVersion[0],
      options: [{ label: commonVersion[0]!, value: commonVersion[0]! }]
    }
  }

  for (const dimension of ['version', 'variant', 'taskType'] as const) {
    const result = selectByDimension(candidates, dimension, answers[dimension])
    if (result.control) {
      plan[dimension] = result.control
    }
    if (result.pending) {
      return plan
    }
    candidates = result.definitions
  }

  if (candidates.length === 1) {
    plan.selectedDefinition = candidates[0]
    return plan
  }

  if (candidates.length > 1) {
    const options = definitionOptions(candidates)
    const selectedId = answers.definitionId
    plan.definition = {
      kind: 'select',
      value: options.some((option) => option.value === selectedId) ? selectedId : undefined,
      options
    }
    plan.selectedDefinition = candidates.find((definition) => definition.id === selectedId)
  }

  return plan
}
