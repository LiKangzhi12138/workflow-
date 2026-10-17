import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { computed, nextTick, reactive } from 'vue'
import { evaluateUploadReadiness, type UploadReadinessInput } from '../src/utils/workflowUploadReadiness.ts'

const file = (name: string) => ({ name }) as File
const readyInput = (): UploadReadinessInput => ({
  workflowId: 42,
  workflowStatus: 'ACCEPTED',
  progressAvailable: true,
  requiredModelCount: 2,
  remainingSlots: 1,
  protocolLoaded: true,
  contractWorkflowId: 42,
  protocol: 'WEIGHTS_V1',
  modelAssetId: 79,
  assetOptionsAvailable: true,
  assetOptionCount: 1,
  selectedAssetAvailable: true,
  weightsFiles: [file('weights.pt')],
  manifest: file('manifest.json'),
  descriptor: file('descriptor.json'),
  uploading: false
})
const codes = (input: UploadReadinessInput) => evaluateUploadReadiness(input).reasons.map((reason) => reason.code)

test('second client can submit its own complete weights package in an open slot', () => {
  const input = readyInput()
  assert.deepEqual(evaluateUploadReadiness(input), { ready: true, reasons: [] })
  assert.equal(input.modelAssetId, 79)
  assert.equal(input.requiredModelCount, 2)
  assert.equal(input.remainingSlots, 1)
})

test('every independent blocked condition has a specific reason', () => {
  const cases: Array<[Partial<UploadReadinessInput>, string]> = [
    [{ uploading: true }, 'UPLOAD_IN_PROGRESS'],
    [{ workflowId: null }, 'WORKFLOW_NOT_LOADED'],
    [{ workflowStatus: 'FEDERATING' }, 'WORKFLOW_STATUS_INVALID'],
    [{ progressAvailable: false }, 'UPLOAD_PROGRESS_UNAVAILABLE'],
    [{ remainingSlots: 0 }, 'UPLOAD_SLOTS_FULL'],
    [{ protocolLoaded: false }, 'UPLOAD_PROTOCOL_UNAVAILABLE'],
    [{ contractWorkflowId: 41 }, 'UPLOAD_PROTOCOL_MISMATCH'],
    [{ modelAssetId: null }, 'MODEL_ASSET_NOT_SELECTED'],
    [{ assetOptionsAvailable: false }, 'MODEL_ASSETS_UNAVAILABLE'],
    [{ assetOptionCount: 0, modelAssetId: null }, 'MODEL_ASSETS_NONE_AVAILABLE'],
    [{ selectedAssetAvailable: false }, 'MODEL_ASSET_NOT_AVAILABLE'],
    [{ weightsFiles: [] }, 'WEIGHTS_NOT_SELECTED'],
    [{ weightsFiles: [file('weights.bin')] }, 'WEIGHTS_FORMAT_INVALID'],
    [{ weightsFiles: [file('one.pt'), file('two.pt')] }, 'WEIGHTS_COUNT_INVALID'],
    [{ manifest: null }, 'MANIFEST_NOT_SELECTED'],
    [{ descriptor: null }, 'DESCRIPTOR_NOT_SELECTED']
  ]
  for (const [change, expected] of cases) {
    const result = evaluateUploadReadiness({ ...readyInput(), ...change })
    assert.equal(result.ready, false, expected)
    assert.ok(result.reasons.some((reason) => reason.code === expected && reason.message), expected)
  }
})

test('reactive file selection enables the same computed state used by the button', async () => {
  const input = reactive(readyInput())
  input.weightsFiles = []
  input.manifest = null
  input.descriptor = null
  const readiness = computed(() => evaluateUploadReadiness(input))
  assert.deepEqual(readiness.value.reasons.map((reason) => reason.code), [
    'WEIGHTS_NOT_SELECTED', 'MANIFEST_NOT_SELECTED', 'DESCRIPTOR_NOT_SELECTED'
  ])
  input.weightsFiles = [file('weights.pt')]
  await nextTick()
  assert.deepEqual(readiness.value.reasons.map((reason) => reason.code), [
    'MANIFEST_NOT_SELECTED', 'DESCRIPTOR_NOT_SELECTED'
  ])
  input.manifest = file('manifest.json')
  input.descriptor = file('descriptor.json')
  await nextTick()
  assert.equal(readiness.value.ready, true)
  input.progressAvailable = false
  await nextTick()
  assert.deepEqual(readiness.value.reasons.map((reason) => reason.code), ['UPLOAD_PROGRESS_UNAVAILABLE'])
})

test('legacy package still requires asset and server slot but not V1 metadata', () => {
  const input = { ...readyInput(), protocol: 'LEGACY_CHECKPOINT' as const, manifest: null, descriptor: null, assetOptionCount: 0 }
  assert.equal(evaluateUploadReadiness(input).ready, true)
  assert.deepEqual(codes({ ...input, modelAssetId: null }), ['MODEL_ASSET_NOT_SELECTED'])
})

test('the live progress button and click guard use the same readiness result', () => {
  const component = readFileSync(new URL('../src/views/client/ClientWorkflowProgressView.vue', import.meta.url), 'utf8')
  assert.match(component, /:disabled="!canStartClientUpload"/)
  assert.match(component, /const canStartClientUpload = computed\(\(\) => uploadReadiness\.value\.ready\)/)
  assert.match(component, /v-for="reason in uploadReadiness\.reasons"/)
  assert.match(component, /if \(!canStartClientUpload\.value\) \{\s*uploadError\.value = uploadReadiness\.value\.reasons/)
  assert.match(component, /const initResp = await initModelUpload\(\{\s*workflowId,\s*modelAssetId,/)
})
