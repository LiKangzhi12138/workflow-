import assert from 'node:assert/strict'
import test from 'node:test'

import {
  getWorkflowModelDisplayName,
  normalizeWorkflowModelMetadata,
  normalizeWorkflowModelMetadataList
} from '../src/utils/workflowModelDisplay.ts'

test('legacy workflow list keeps null definition and displays yoloVersion', () => {
  const records = normalizeWorkflowModelMetadataList([
    {
      modelDefinitionId: null,
      modelDefinitionDisplayName: null,
      yoloVersion: 'YOLOv8'
    }
  ])

  assert.equal(records[0]?.modelDefinitionId, null)
  assert.equal(getWorkflowModelDisplayName(records[0]), 'YOLOv8')
})

test('definition workflow list displays minimal trusted family and version', () => {
  const records = normalizeWorkflowModelMetadataList([
    {
      modelDefinitionId: 1,
      modelDefinitionDisplayName: 'YOLOv8n 羊群检测',
      modelDefinitionFamily: 'YOLO',
      modelDefinitionVersion: 'YOLOv8',
      yoloVersion: 'YOLOv8'
    }
  ])

  assert.equal(getWorkflowModelDisplayName(records[0]), 'YOLO / YOLOv8')
})

test('definition workflow omits an empty version', () => {
  assert.equal(
    getWorkflowModelDisplayName({
      modelDefinitionId: 2,
      modelDefinitionFamily: 'InternImage',
      modelDefinitionVersion: null
    }),
    'InternImage'
  )
})

test('progress detail normalizes null display name and falls back safely', () => {
  const detail = normalizeWorkflowModelMetadata({
    modelDefinitionId: 1,
    modelDefinitionDisplayName: null,
    yoloVersion: 'YOLOv8'
  })

  assert.equal(detail.modelDefinitionDisplayName, null)
  assert.equal(detail.modelDefinitionFamily, null)
  assert.equal(detail.modelDefinitionVersion, null)
  assert.equal(getWorkflowModelDisplayName(detail), 'YOLOv8')
})

test('missing model metadata has a stable empty-state label', () => {
  const detail = normalizeWorkflowModelMetadata({
    modelDefinitionId: null,
    modelDefinitionDisplayName: null,
    yoloVersion: null
  })

  assert.equal(getWorkflowModelDisplayName(detail), '未配置模型')
})

test('invalid list payload normalizes to an empty list', () => {
  assert.deepEqual(normalizeWorkflowModelMetadataList(undefined), [])
  assert.deepEqual(normalizeWorkflowModelMetadataList(null), [])
})
