import assert from 'node:assert/strict'
import test from 'node:test'

import {
  WORKFLOW_CURRENT_STEP_TEXT,
  resolveServerProcessPhase
} from '../src/constants/workflowProgress.ts'

const managedProgress = {
  latestUploadStatus: 'COMPLETED',
  collectedModelCount: 1,
  requiredModelCount: 1
}

test('inspected V1 weights do not look like an aggregation that already started', () => {
  const phase = resolveServerProcessPhase(
    {
      status: 'ACCEPTED',
      currentStep: WORKFLOW_CURRENT_STEP_TEXT.weightsInspectedWaitingFederated,
      serverDatasetAssetId: null,
      pythonJobId: null,
      errorMessage: null,
      federatedStatus: 'PENDING'
    },
    managedProgress
  )

  assert.equal(phase, 'waiting-federated')
})

test('only an actually running aggregation is rendered as federated', () => {
  const phase = resolveServerProcessPhase(
    {
      status: 'ACCEPTED',
      currentStep: WORKFLOW_CURRENT_STEP_TEXT.federatedAggregating,
      serverDatasetAssetId: null,
      pythonJobId: null,
      errorMessage: null,
      federatedStatus: 'RUNNING'
    },
    managedProgress
  )

  assert.equal(phase, 'federated')
})
