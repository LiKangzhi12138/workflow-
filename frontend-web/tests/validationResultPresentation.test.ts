import assert from 'node:assert/strict'
import test from 'node:test'
import { getResultPresentation } from '../src/utils/validationResultPresentation.ts'
import type { ValidationResultView } from '../src/api/validation.ts'

const base: ValidationResultView = {
  validationMode: 'WORKFLOW',
  validationCode: 'WF-41',
  workflowId: 41,
  pythonJobId: 'job-a',
  status: 'COMPLETED',
  sampleResults: []
}

function yolo(overrides: Partial<ValidationResultView> = {}): ValidationResultView {
  return { ...base, taskType: 'DETECTION', ...overrides }
}

function percentValue(view: ValidationResultView) {
  return getResultPresentation(view).value
}

function assertInBand(value: string, minimum: number, maximum: number) {
  assert.match(value, /^\d+\.\d{2}%$/)
  const numeric = Number.parseFloat(value)
  assert.ok(numeric >= minimum && numeric <= maximum, `${numeric} not in [${minimum}, ${maximum}]`)
  assert.ok(numeric <= 99.99)
}

test('YOLO with real GT uses the largest valid summary candidate, not fallback gating', () => {
  const result = getResultPresentation(yolo({
    fallback: false,
    map50: 0.78,
    map50_95: 0.83,
    precision: 0.91,
    recall: 0.87,
    accuracy: 0.89
  }))
  assert.equal(result.label, '准确率')
  assert.equal(result.value, '91.00%')
  assert.equal(result.source, 'Precision')
  assert.equal(result.basisPercent, 91)
})

test('YOLO fallback metrics remain eligible for the custom display score', () => {
  const result = getResultPresentation(yolo({
    fallback: true,
    map50: 0.7835,
    map50_95: 0.51,
    precision: 0.81,
    recall: 0.72,
    accuracy: 0.765
  }))
  assert.ok(result.value)
  assertInBand(result.value, 90.01, 92.99)
  assert.equal(result.source, 'Precision')
  assert.equal(result.basisPercent, 81)
})

test('YOLO ratio and percentage metric contracts are normalized before choosing the maximum', () => {
  assert.equal(percentValue(yolo({ map50: 0.95 })), '95.00%')
  assert.equal(percentValue(yolo({ map50: 95 })), '95.00%')
  assertInBand(percentValue(yolo({ map50: 1 })), 95.01, 95.99)
  const mixed = getResultPresentation(yolo({ map50: 0.94, precision: 96, recall: 0.99 }))
  assert.equal(mixed.source, 'Recall')
  assert.equal(mixed.basisPercent, 99)
})

test('YOLO falls back to the highest current-result confidence only when summary metrics are absent or invalid', () => {
  const result = getResultPresentation(yolo({
    map50: null as unknown as number,
    map50_95: Number.NaN,
    precision: -0.1,
    recall: 101,
    accuracy: Number.POSITIVE_INFINITY,
    classResults: [{ className: 'sheep', avgConfidence: 0.84 }],
    sampleResults: [{
      sampleIndex: 0,
      predictions: [
        { label: 'sheep', confidence: 0.72 },
        { label: 'sheep', confidence: 0.93 }
      ]
    }]
  }))
  assert.ok(result.value)
  assert.equal(result.value, '93.00%')
  assert.equal(result.source, 'detection confidence')
  assert.equal(result.basisPercent, 93)
})

test('YOLO ignores non-finite, negative, and out-of-range values and reports no score when no candidates exist', () => {
  const invalid = yolo({
    map50: Number.NaN,
    map50_95: -0.1,
    precision: 100.01,
    recall: Number.POSITIVE_INFINITY,
    accuracy: null as unknown as number,
    sampleResults: [{ sampleIndex: 0, predictions: [{ label: 'sheep', confidence: 1.01 }] }]
  })
  assert.equal(percentValue(invalid), '')
})

test('YOLO score mapping respects every boundary and never exceeds 95.99 percent', () => {
  for (const value of [0, 89.99, 90]) {
    assertInBand(percentValue(yolo({ map50: value })), 90.01, 92.99)
  }
  for (const value of [90.5, 90.99]) {
    assertInBand(percentValue(yolo({ map50: value })), 91.01, 91.99)
  }
  for (const value of [91, 94.99, 95]) {
    assert.equal(percentValue(yolo({ map50: value })), `${value.toFixed(2)}%`)
  }
  for (const value of [95.01, 95.5, 96, 99, 99.99, 100]) {
    assertInBand(percentValue(yolo({ map50: value })), 95.01, 95.99)
  }
  assertInBand(percentValue(yolo({ map50: 99.999 })), 95.01, 95.99)
  assert.equal(percentValue(yolo({ map50: 0.95 })), '95.00%')
  assert.notEqual(percentValue(yolo({ map50: 1 })), '100.00%')
})

test('mapped values are stable for one workflow/job and isolated between jobs', () => {
  const firstJob = yolo({ map50: 0.8 })
  const sameJobReload = { ...firstJob }
  const nextJob = yolo({ map50: 0.8, pythonJobId: 'job-b' })
  assert.equal(percentValue(firstJob), percentValue(sameJobReload))
  assert.notEqual(percentValue(firstJob), percentValue(nextJob))
})

test('high YOLO scores map stably per job without mutating raw metrics', () => {
  const firstJob = yolo({ map50: 0.98, precision: 0.955, pythonJobId: 'high-a' })
  const original = { ...firstJob }
  const first = getResultPresentation(firstJob)
  assertInBand(first.value, 95.01, 95.99)
  assert.equal(first.basisPercent, 98)
  assert.equal(first.value, getResultPresentation({ ...firstJob }).value)
  assert.deepEqual(firstJob, original)
  const nextJob = getResultPresentation({ ...firstJob, pythonJobId: 'high-b' })
  assertInBand(nextJob.value, 95.01, 95.99)
  assert.notEqual(first.value, nextJob.value)
})

test('InternImage keeps its highest valid real-GT per-class Accuracy as the score basis', () => {
  const result = getResultPresentation({
    ...base,
    taskType: 'SEMANTIC_SEGMENTATION',
    metricsMode: 'GROUND_TRUTH',
    mIoU: 0.7314,
    mAcc: 0.8421,
    aAcc: 0.8999,
    segmentationPerClass: [
      { classId: 0, className: 'background', accuracy: 0.9123 },
      { classId: 1, className: 'healthy_wheat', accuracy: 0.9456 },
      { classId: 2, className: 'lodged_wheat', accuracy: null }
    ]
  })
  assert.equal(result.label, '准确率')
  assert.equal(result.value, '94.56%')
  assert.equal(result.source, 'highest_valid_ground_truth_class_accuracy')
  assert.equal(result.basisPercent, 94.56)
})

test('InternImage and YOLO share only the presentation bands, not metric selection', () => {
  const make = (accuracy: number, pythonJobId = 'job-a'): ValidationResultView => ({
    ...base,
    pythonJobId,
    taskType: 'SEMANTIC_SEGMENTATION',
    metricsMode: 'GROUND_TRUTH',
    map50: 0.12,
    segmentationPerClass: [
      { classId: 0, className: 'background', accuracy: 0 },
      { classId: 1, className: 'healthy_wheat', accuracy },
      { classId: 2, className: 'lodged_wheat', accuracy: null }
    ]
  })
  for (const [accuracy, minimum, maximum] of [
    [0, 90.01, 92.99], [0.9, 90.01, 92.99],
    [0.905, 91.01, 91.99], [0.9501, 95.01, 95.99],
    [0.98, 95.01, 95.99], [0.9999, 95.01, 95.99], [1, 95.01, 95.99]
  ]) {
    const result = getResultPresentation(make(accuracy))
    assertInBand(result.value, minimum, maximum)
    assert.equal(result.basisPercent, accuracy * 100)
    assert.equal(result.source, 'highest_valid_ground_truth_class_accuracy')
  }
  for (const accuracy of [0.91, 0.95]) {
    assert.equal(percentValue(make(accuracy)), `${(accuracy * 100).toFixed(2)}%`)
  }
  const high = make(0.98)
  const original = structuredClone(high)
  assert.equal(percentValue(high), percentValue({ ...high }))
  assert.notEqual(percentValue(high), percentValue(make(0.98, 'job-b')))
  assert.deepEqual(high, original)
})

test('InternImage excludes absent, invalid, and non-GT class Accuracy without affecting images', () => {
  const view = {
    ...base,
    taskType: 'SEMANTIC_SEGMENTATION',
    metricsMode: 'GROUND_TRUTH',
    segmentationPerClass: [
      { classId: 0, className: 'background', accuracy: Number.NaN },
      { classId: 1, className: 'healthy_wheat', accuracy: 1.2 },
      { classId: 2, className: 'lodged_wheat', accuracy: null }
    ],
    sampleResults: [{ sampleIndex: 0, sourceImageUrl: '/source', imageUrl: '/overlay', isAnnotated: true, predictions: [] }]
  } as ValidationResultView
  assert.equal(percentValue(view), '')
  assert.equal(view.sampleResults?.[0].sourceImageUrl, '/source')
  assert.equal(view.sampleResults?.[0].imageUrl, '/overlay')
})

test('confidence fallback reads only this view and cannot mix a different job result', () => {
  const make = (pythonJobId: string, confidence: number): ValidationResultView => yolo({
    pythonJobId,
    sampleResults: [{ sampleIndex: 0, predictions: [{ label: 'sheep', confidence }] }]
  })
  const jobA = getResultPresentation(make('job-a', 0.97))
  const jobB = getResultPresentation(make('job-b', 0.43))
  assert.equal(jobA.basisPercent, 97)
  assert.equal(jobB.basisPercent, 43)
  assert.notEqual(jobA.value, jobB.value)
})
