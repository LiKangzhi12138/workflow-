import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildWorkflowModelSelectionPayload,
  normalizeAvailableModelDefinitions,
  resolveModelSelectionFailure,
  resolveModelSelectionMode
} from '../src/utils/modelDefinitionSelection.ts'

const availableDefinition = {
  id: 1,
  code: 'YOLOV8N_SHEEP_V1',
  displayName: 'YOLOv8n 羊群检测',
  modelFamily: 'YOLO',
  version: 'YOLOv8',
  variant: 'yolov8n',
  taskType: 'DETECTION',
  framework: 'Ultralytics',
  frameworkVersion: '8.4.41',
  classes: ['sheep'],
  runtimeProfileId: 'YOLO_RUNTIME_V1',
  availability: { available: true }
}

test('uses available definitions returned by the server', () => {
  assert.equal(resolveModelSelectionMode(true), 'definition')
  assert.deepEqual(normalizeAvailableModelDefinitions([availableDefinition]), [availableDefinition])
})

test('keeps an empty available response empty without legacy fallback', () => {
  assert.deepEqual(normalizeAvailableModelDefinitions([]), [])
})

test('offers Stage 5B InternImage definitions when the server marks them selectable', () => {
  const internImageDefinition = {
    ...availableDefinition,
    id: 2,
    code: 'INTERNIMAGE_T_UPERNET_WHEAT_V1',
    modelFamily: 'INTERNIMAGE',
    workflowSelectable: true
  }
  assert.deepEqual(
    normalizeAvailableModelDefinitions([availableDefinition, internImageDefinition]),
    [availableDefinition, internImageDefinition]
  )
})

test('continues to hide available definitions that are not workflow selectable', () => {
  const runtimeOnlyDefinition = {
    ...availableDefinition,
    id: 3,
    code: 'FUTURE_RUNTIME_ONLY_V1',
    workflowSelectable: false
  }
  assert.deepEqual(
    normalizeAvailableModelDefinitions([availableDefinition, runtimeOnlyDefinition]),
    [availableDefinition]
  )
})

test('represents an API failure as fail-closed error mode', () => {
  assert.deepEqual(resolveModelSelectionFailure('runtime unavailable'), {
    mode: 'error',
    message: 'runtime unavailable'
  })
})

test('definition selection submits only modelDefinitionId', () => {
  assert.deepEqual(buildWorkflowModelSelectionPayload('definition', 1, 'YOLOv11'), {
    modelDefinitionId: 1
  })
})

test('registry-disabled legacy mode remains compatible', () => {
  assert.equal(resolveModelSelectionMode(false), 'legacy')
  assert.deepEqual(buildWorkflowModelSelectionPayload('legacy', undefined, 'YOLOv10'), {
    yoloVersion: 'YOLOv10'
  })
})
