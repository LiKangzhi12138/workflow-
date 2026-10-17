import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const component = readFileSync(new URL('../src/components/ValidationResultCard.vue', import.meta.url), 'utf8')
const template = component.split('<script setup lang="ts">')[0]
const compose = readFileSync(new URL('../../docker-compose.yml', import.meta.url), 'utf8')
const dockerfile = readFileSync(new URL('../Dockerfile', import.meta.url), 'utf8')
const clientResultPage = readFileSync(new URL('../src/views/client/ClientValidationView.vue', import.meta.url), 'utf8')
const serverResultPage = readFileSync(new URL('../src/views/server/ServerValidationView.vue', import.meta.url), 'utf8')

test('final result card shows one metric and paired source/result images only', () => {
  assert.match(template, /准确率/)
  assert.match(template, /原始图片/)
  assert.match(template, /识别结果/)
  assert.match(template, /sampleIndex \+ 1/)
  assert.equal((template.match(/class="metric-value"/g) || []).length, 1)
  assert.doesNotMatch(template, /暂无有效指标|mAP@0\.5|mAcc|mIoU|Precision|Recall|演示数据|真实 GT/)
  assert.doesNotMatch(template, /mIoU|mAcc|aAcc|类别指标|predictionMaskUrl/)
  assert.doesNotMatch(template, /predictionCount|predictions|bbox|置信度/)
  assert.doesNotMatch(template, /result-footnote|验证时间|数据集：/)
})

test('the result card uses source and result URLs from one active sample without a display mode tag', () => {
  assert.doesNotMatch(template, /演示数据|fallbackReason|fallback === true/)
  assert.match(component, /activeSample\.value\.sourceImageUrl/)
  assert.match(component, /activeSample\.value\.imageUrl/)
  assert.match(component, /sampleIdentity/)
})

test('the retired demo score build flag is absent from frontend build configuration', () => {
  assert.doesNotMatch(compose, /VITE_VALIDATION_DEMO_SCORE_ENABLED/)
  assert.doesNotMatch(dockerfile, /VITE_VALIDATION_DEMO_SCORE_ENABLED/)
})

test('Client and Server pages continue to use the same result component', () => {
  assert.match(clientResultPage, /ValidationResultCard/)
  assert.match(serverResultPage, /ValidationResultCard/)
})
