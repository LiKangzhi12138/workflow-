import assert from 'node:assert/strict'
import test from 'node:test'
import {
  isCurrentWorkflowResponse,
  normalizeDatasetId,
  reconcileDatasetSelection
} from '../src/utils/datasetSelectionState.ts'

test('draft survives repeated polling; persisted binding returns after commit or reload', () => {
  let draft = normalizeDatasetId(15)
  for (let index = 0; index < 3; index++) {
    draft = reconcileDatasetSelection(draft, null, true)
    assert.equal(draft, 15)
  }
  assert.equal(reconcileDatasetSelection(draft, 15, false), 15)
  assert.equal(reconcileDatasetSelection(null, '15', false), 15)
})

test('failed binding restores server state and legitimate unbind is reflected', () => {
  assert.equal(reconcileDatasetSelection(18, 15, false), 15)
  assert.equal(reconcileDatasetSelection(15, null, false), null)
  assert.equal(reconcileDatasetSelection(15, 19, false), 19)
})

test('late responses cannot overwrite a new selection or another workflow', () => {
  assert.equal(isCurrentWorkflowResponse(4, 5, 40, 40), false)
  assert.equal(isCurrentWorkflowResponse(5, 5, 40, 41), false)
  assert.equal(isCurrentWorkflowResponse(5, 5, 41, 41), true)
})
