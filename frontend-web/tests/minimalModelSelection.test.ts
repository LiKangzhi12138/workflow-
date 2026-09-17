import assert from 'node:assert/strict'
import test from 'node:test'

import type { AvailableModelDefinition } from '../src/api/modelDefinition.ts'
import { buildMinimalModelSelectionPlan } from '../src/utils/minimalModelSelection.ts'

function definition(
  id: number,
  overrides: Partial<AvailableModelDefinition> = {}
): AvailableModelDefinition {
  return {
    id,
    code: `MODEL_${id}`,
    displayName: `Model ${id}`,
    modelFamily: 'YOLO',
    version: 'YOLOv8',
    variant: 'yolov8n',
    taskType: 'DETECTION',
    framework: 'Ultralytics',
    frameworkVersion: '8.4.41',
    classes: ['sheep'],
    runtimeProfileId: 'YOLO_RUNTIME_V1',
    availability: { available: true },
    ...overrides
  }
}

test('one family and one definition are selected without extra user choices', () => {
  const plan = buildMinimalModelSelectionPlan([definition(1)])

  assert.equal(plan.family.kind, 'static')
  assert.equal(plan.family.value, 'YOLO')
  assert.equal(plan.version?.kind, 'static')
  assert.equal(plan.version?.value, 'YOLOv8')
  assert.equal(plan.variant, undefined)
  assert.equal(plan.taskType, undefined)
  assert.equal(plan.definition, undefined)
  assert.equal(plan.selectedDefinition?.id, 1)
})

test('different versions introduce only the version selector', () => {
  const definitions = [definition(1), definition(2, { version: 'YOLOv11', variant: 'yolo11n' })]
  const pending = buildMinimalModelSelectionPlan(definitions)
  const selected = buildMinimalModelSelectionPlan(definitions, { version: 'YOLOv11' })

  assert.equal(pending.version?.kind, 'select')
  assert.deepEqual(pending.version?.options.map((option) => option.value), ['YOLOv8', 'YOLOv11'])
  assert.equal(selected.selectedDefinition?.id, 2)
})

test('same family and version introduce variant only when variants differ', () => {
  const definitions = [definition(1), definition(2, { variant: 'yolov8s' })]
  const pending = buildMinimalModelSelectionPlan(definitions)
  const selected = buildMinimalModelSelectionPlan(definitions, { variant: 'yolov8s' })

  assert.equal(pending.version?.kind, 'static')
  assert.equal(pending.variant?.kind, 'select')
  assert.equal(selected.selectedDefinition?.id, 2)
})

test('task type is introduced only after family version and variant remain ambiguous', () => {
  const definitions = [definition(1), definition(2, { taskType: 'SEGMENTATION' })]
  const pending = buildMinimalModelSelectionPlan(definitions)
  const selected = buildMinimalModelSelectionPlan(definitions, { taskType: 'SEGMENTATION' })

  assert.equal(pending.taskType?.kind, 'select')
  assert.equal(selected.selectedDefinition?.id, 2)
})

test('displayName is the final human-readable discriminator', () => {
  const definitions = [
    definition(1, { displayName: '羊群模型', classes: ['sheep'] }),
    definition(2, { displayName: '牛群模型', classes: ['cattle'] })
  ]
  const pending = buildMinimalModelSelectionPlan(definitions)
  const selected = buildMinimalModelSelectionPlan(definitions, { definitionId: 2 })

  assert.equal(pending.definition?.kind, 'select')
  assert.deepEqual(pending.definition?.options.map((option) => option.label), ['羊群模型', '牛群模型'])
  assert.equal(selected.selectedDefinition?.id, 2)
})

test('classes never become a default selection dimension', () => {
  const plan = buildMinimalModelSelectionPlan([
    definition(1, { classes: ['sheep'] }),
    definition(2, { classes: ['cattle'] })
  ])

  assert.equal('classes' in plan, false)
  assert.equal(plan.definition?.kind, 'select')
})

test('a null InternImage version is omitted instead of rendered as an empty field', () => {
  const plan = buildMinimalModelSelectionPlan([
    definition(3, {
      modelFamily: 'InternImage',
      version: null,
      variant: 'InternImage-T',
      framework: 'MMSegmentation'
    })
  ])

  assert.equal(plan.family.value, 'InternImage')
  assert.equal(plan.version, undefined)
  assert.equal(plan.selectedDefinition?.id, 3)
})

test('multiple families require a family choice before resolving a definition', () => {
  const definitions = [definition(1), definition(2, { modelFamily: 'InternImage', version: null })]
  const pending = buildMinimalModelSelectionPlan(definitions)
  const selected = buildMinimalModelSelectionPlan(definitions, { modelFamily: 'YOLO' })

  assert.equal(pending.family.kind, 'select')
  assert.equal(pending.selectedDefinition, undefined)
  assert.equal(selected.selectedDefinition?.id, 1)
})

test('the resolved selection remains the unique modelDefinitionId', () => {
  const plan = buildMinimalModelSelectionPlan([definition(9)])

  assert.deepEqual(
    { modelDefinitionId: plan.selectedDefinition?.id },
    { modelDefinitionId: 9 }
  )
})
