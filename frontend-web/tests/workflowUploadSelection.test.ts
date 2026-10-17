import assert from 'node:assert/strict'
import test from 'node:test'
import { nextTick } from 'vue'
import {
  isUploadableClientModelAsset,
  resolveUploadableModelAsset,
  resolveUploadModelAssetId,
  stageUploadFiles
} from '../src/utils/workflowUploadSelection.ts'

const file = (name: string) => new File(['weights'], name)

test('V1 selects the current uploader asset instead of inheriting a prior client binding', () => {
  assert.equal(resolveUploadModelAssetId('WEIGHTS_V1', null, 78), null)
  assert.equal(resolveUploadModelAssetId('WEIGHTS_V1', 79, 78), 79)
  assert.equal(resolveUploadModelAssetId('LEGACY_CHECKPOINT', null, 78), 78)
})

test('asset eligibility matches the backend upload policy for path registry and formal assets', () => {
  const pathRegistry = {
    id: 79,
    recordMode: 'PATH_REGISTRY',
    sourceType: 'CLIENT_PATH_REGISTRY',
    status: 'PENDING',
    filePathValidated: 0
  }
  const formalReady = {
    id: 80,
    recordMode: 'FORMAL_ASSET',
    sourceType: 'CLIENT_UPLOAD',
    status: 'READY',
    filePathValidated: 1
  }
  assert.equal(isUploadableClientModelAsset(pathRegistry), true)
  assert.equal(isUploadableClientModelAsset(formalReady), true)
  assert.equal(isUploadableClientModelAsset({ ...pathRegistry, sourceType: 'LEGACY_PATH' }), false)
  assert.equal(isUploadableClientModelAsset({ ...formalReady, filePathValidated: 0 }), false)
  assert.equal(isUploadableClientModelAsset({ ...formalReady, status: 'FAILED' }), false)
})

test('asset ID resolution canonicalizes string IDs and never substitutes another client asset', () => {
  const ownAssets = [
    { id: 79, recordMode: 'PATH_REGISTRY', sourceType: 'CLIENT_PATH_REGISTRY', status: 'PENDING', filePathValidated: 0 }
  ]
  assert.equal(resolveUploadableModelAsset(ownAssets, '79')?.id, 79)
  assert.equal(resolveUploadableModelAsset(ownAssets, 78), null)
  assert.equal(resolveUploadModelAssetId('WEIGHTS_V1', null, 78), null)
})

test('multiple queued onChange callbacks cannot erase a valid batch selection', async () => {
  const queue = [{ raw: file('a.pt') }, { raw: file('b.pt') }]
  const selections: string[][] = []
  const onChange = () => selections.push(
    stageUploadFiles(queue, 2, () => { queue.splice(0) }).map((item) => item.name)
  )

  void nextTick(onChange)
  void nextTick(onChange)
  await nextTick()
  assert.deepEqual(selections, [['a.pt', 'b.pt'], ['a.pt', 'b.pt']])
  await nextTick()
  assert.equal(queue.length, 0)
})

test('one weights package takes one file while legacy batches respect their remaining slots', async () => {
  const queue = [{ raw: file('a.pt') }, { raw: file('b.pt') }, { raw: file('c.pt') }]
  assert.deepEqual(stageUploadFiles(queue, 1, () => { queue.splice(0) }).map((item) => item.name), ['a.pt'])
  await nextTick()
  assert.equal(queue.length, 0)
  const legacyQueue = [{ raw: file('a.pt') }, { raw: file('b.pt') }, { raw: file('c.pt') }]
  assert.deepEqual(stageUploadFiles(legacyQueue, 2, () => { legacyQueue.splice(0) }).map((item) => item.name), ['a.pt', 'b.pt'])
  await nextTick()
})
