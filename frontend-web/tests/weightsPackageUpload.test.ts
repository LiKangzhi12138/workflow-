import assert from 'node:assert/strict'
import test from 'node:test'

import {
  appendWeightsProtocolMetadata,
  protocolUploadReady,
  weightsUploadErrorMessage
} from '../src/utils/weightsPackageUpload.ts'

const file = (name: string, content = '{}') => new File([content], name, { type: 'application/json' })

test('V1 requires weights-only pt plus manifest and descriptor', () => {
  assert.equal(protocolUploadReady('WEIGHTS_V1', file('weights.pt'), { manifest: file('manifest.json'), descriptor: file('descriptor.json') }), true)
  assert.equal(protocolUploadReady('WEIGHTS_V1', file('best.onnx'), { manifest: file('manifest.json'), descriptor: file('descriptor.json') }), false)
  assert.equal(protocolUploadReady('WEIGHTS_V1', file('weights.pt'), { manifest: null, descriptor: file('descriptor.json') }), false)
})

test('legacy contract still needs only its model file', () => {
  assert.equal(protocolUploadReady('LEGACY_CHECKPOINT', file('best.pt'), { manifest: null, descriptor: null }), true)
})

test('V1 appends normalized JSON metadata without parsing PyTorch', async () => {
  const body = new FormData()
  await appendWeightsProtocolMetadata(body, 'WEIGHTS_V1', {
    manifest: file('manifest.json', '{"artifactType":"CLIENT_WEIGHTS"}'),
    descriptor: file('descriptor.json', '{"modelFamily":"YOLO"}')
  })
  assert.equal(body.get('manifest'), '{"artifactType":"CLIENT_WEIGHTS"}')
  assert.equal(body.get('descriptor'), '{"modelFamily":"YOLO"}')
})

test('compatibility errors are presented distinctly', () => {
  assert.match(weightsUploadErrorMessage('WEIGHTS_SHAPE_MISMATCH'), /不兼容/)
  assert.match(weightsUploadErrorMessage('WEIGHTS_SHA_MISMATCH'), /摘要/)
  assert.match(weightsUploadErrorMessage('WEIGHTS_MANIFEST_INVALID'), /manifest/)
})
