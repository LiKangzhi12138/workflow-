<template>
  <div v-if="activeView" class="validation-result">
    <header class="result-header">
      <div>
        <h2 class="result-title">
          {{ activeView.validationMode === 'WORKFLOW' ? '工作流验证结果' : '模型验证结果' }}
        </h2>
        <div class="result-model">模型：{{ activeView.modelName || activeView.algorithmType || '未指定' }}</div>
      </div>
      <div class="result-tags">
        <el-tag :type="statusTagType" effect="dark">{{ statusLabel }}</el-tag>
      </div>
    </header>

    <el-alert
      v-if="isRunning"
      type="info"
      :closable="false"
      show-icon
      class="status-alert"
      title="验证进行中"
      :description="`当前状态：${statusLabel}`"
    />

    <el-alert
      v-if="activeView.status === 'FAILED'"
      type="error"
      :closable="false"
      show-icon
      class="status-alert"
      title="验证失败"
      :description="summarizeUiErrorMessage(activeView.errorMessage, '验证任务未能完成。')"
    />

    <template v-if="activeView.status === 'COMPLETED'">
      <section v-if="presentation.value" class="metric-panel" aria-label="验证准确率">
        <div class="metric-label">{{ presentation.label }}</div>
        <div class="metric-value">{{ presentation.value }}</div>
      </section>

      <section class="sample-section" aria-label="验证样例">
        <div class="sample-toolbar">
          <div class="sample-name">
            {{ activeSample?.imageName || (activeSample ? `验证样例 ${sampleIndex + 1}` : '验证样例') }}
          </div>
          <div v-if="sampleResults.length > 1" class="sample-navigation">
            <span>{{ sampleIndex + 1 }} / {{ sampleResults.length }}</span>
            <el-button
              circle
              aria-label="上一张样例"
              title="上一张样例"
              :icon="ArrowLeft"
              :disabled="sampleIndex === 0"
              @click="moveSample(-1)"
            />
            <el-button
              circle
              aria-label="下一张样例"
              title="下一张样例"
              :icon="ArrowRight"
              :disabled="sampleIndex >= sampleResults.length - 1"
              @click="moveSample(1)"
            />
          </div>
        </div>

        <div class="sample-pair">
          <article class="sample-image-panel">
            <h3>原始图片</h3>
            <el-image
              v-if="sourceImageUrl"
              :src="sourceImageUrl"
              fit="contain"
              class="sample-image"
              :preview-src-list="[sourceImageUrl]"
              preview-teleported
            >
              <template #error><div class="image-empty">原始图片暂不可用</div></template>
            </el-image>
            <div v-else class="image-empty">暂无原始图片</div>
          </article>

          <article class="sample-image-panel">
            <h3>识别结果</h3>
            <el-image
              v-if="resultImageUrl"
              :src="resultImageUrl"
              fit="contain"
              class="sample-image"
              :preview-src-list="[resultImageUrl]"
              preview-teleported
            >
              <template #error><div class="image-empty">识别结果图片暂不可用</div></template>
            </el-image>
            <div v-else class="image-empty">暂无识别结果图片</div>
          </article>
        </div>
      </section>
    </template>
  </div>

  <div v-else class="empty-wrap">
    <el-empty description="暂无验证结果" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import type {
  StandaloneValidationVO,
  ValidationResultSample,
  ValidationResultView
} from '../api/validation'
import { VALIDATION_STATUS_LABELS, VALIDATION_TEXT } from '../constants/validation'
import { getResultPresentation } from '../utils/validationResultPresentation'
import { summarizeUiErrorMessage } from '../utils/errorMessage'

interface Props {
  result?: StandaloneValidationVO | null
  view?: ValidationResultView | null
}

const props = withDefaults(defineProps<Props>(), {
  result: null,
  view: null
})

const activeView = computed<ValidationResultView | null>(() => {
  if (props.view) return props.view
  if (!props.result) return null

  let metrics: Record<string, any> = {}
  if (props.result.metricsJson) {
    try {
      metrics = JSON.parse(props.result.metricsJson)
    } catch (error) {
      console.error('[ValidationResultCard] 解析 metricsJson 失败', error)
    }
  }

  return {
    validationMode: props.result.validationMode,
    validationId: props.result.id,
    validationCode: props.result.validationCode,
    modelName: props.result.modelAssetName,
    datasetName: props.result.datasetAssetName,
    algorithmType: props.result.algorithmType || props.result.modelYoloVersion,
    status: props.result.status,
    progress: props.result.progress,
    pythonJobId: props.result.pythonJobId,
    datasetSampleCount: props.result.datasetSampleCount,
    datasetImageCount: props.result.datasetImageCount ?? metrics.totalImages,
    accuracy: metrics.accuracy,
    precision: metrics.precision,
    recall: metrics.recall,
    map50: metrics.map50 ?? metrics.mAP,
    map50_95: metrics.map50_95,
    taskType: metrics.taskType,
    metricsMode: metrics.metricsMode,
    mIoU: metrics.mIoU,
    mAcc: metrics.mAcc,
    aAcc: metrics.aAcc,
    segmentationPerClass: metrics.perClass,
    fallback: metrics.fallback,
    fallbackReason: metrics.fallbackReason,
    qualityLabel: props.result.qualityLabel,
    errorMessage: props.result.errorMessage,
    resultFileAvailable: Boolean(props.result.resultFilePath),
    visualizationMode: 'METRICS_ONLY',
    emptyVisualizationReason: VALIDATION_TEXT.sampleEmpty,
    startedAt: props.result.createdAt,
    finishedAt: props.result.finishedAt || props.result.updatedAt,
    sampleResults: []
  }
})

const statusLabel = computed(
  () => VALIDATION_STATUS_LABELS[activeView.value?.status || ''] || activeView.value?.status || VALIDATION_TEXT.unknown
)
const statusTagType = computed(() => {
  switch (activeView.value?.status) {
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'danger'
    case 'VALIDATING':
    case 'TRAINING_RUNNING': return 'warning'
    default: return 'info'
  }
})
const isRunning = computed(() =>
  ['CREATED', 'ACCEPTED', 'PREPARING', 'TRAINING_RUNNING', 'VALIDATING', 'INITIATED'].includes(
    activeView.value?.status || ''
  )
)
const sampleResults = computed<ValidationResultSample[]>(() => activeView.value?.sampleResults || [])
const sampleIndex = ref(0)
const sampleIdentity = computed(() => {
  const view = activeView.value
  if (!view) return ''
  return `${view.workflowId ?? view.validationId ?? ''}:${view.pythonJobId ?? ''}:${sampleResults.value.length}`
})

watch(sampleIdentity, () => {
  sampleIndex.value = 0
})

const activeSample = computed(() => sampleResults.value[sampleIndex.value] || null)
const sourceImageUrl = computed(() => {
  if (!activeSample.value) return undefined
  if (activeSample.value.sourceImageUrl) return activeSample.value.sourceImageUrl
  return hasAnnotatedResult(activeSample.value) ? undefined : activeSample.value.imageUrl
})
const resultImageUrl = computed(() =>
  activeSample.value && hasAnnotatedResult(activeSample.value) ? activeSample.value.imageUrl : undefined
)
const presentation = computed(() => getResultPresentation(activeView.value!))

function hasAnnotatedResult(sample: ValidationResultSample) {
  return sample.isAnnotated ?? sample.annotatedImageAvailable ?? false
}

function moveSample(offset: number) {
  const nextIndex = sampleIndex.value + offset
  if (nextIndex >= 0 && nextIndex < sampleResults.value.length) sampleIndex.value = nextIndex
}

</script>

<style scoped>
.validation-result {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.result-header,
.sample-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.result-title {
  margin: 0;
  color: #172b3a;
  font-size: 18px;
  font-weight: 650;
  line-height: 1.35;
}

.result-model {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  color: #627484;
  font-size: 13px;
}

.result-model { margin-top: 5px; }

.result-tags,
.sample-navigation {
  display: flex;
  align-items: center;
  gap: 8px;
}

.metric-panel {
  padding: 18px 0;
  border-top: 1px solid #e4e9ed;
  border-bottom: 1px solid #e4e9ed;
  text-align: center;
}

.metric-label { color: #627484; font-size: 14px; }
.metric-value { margin-top: 5px; color: #17394d; font-size: 34px; font-weight: 700; line-height: 1.2; }
.status-alert { margin: 0; }
.sample-section { display: flex; flex-direction: column; gap: 12px; }
.sample-name { min-width: 0; overflow-wrap: anywhere; color: #34495a; font-size: 14px; font-weight: 600; }
.sample-navigation > span { color: #758593; font-size: 12px; }

.sample-pair {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.sample-image-panel { min-width: 0; }
.sample-image-panel h3 { margin: 0 0 7px; color: #627484; font-size: 13px; font-weight: 550; }
.sample-image,
.image-empty { width: 100%; aspect-ratio: 16 / 10; min-height: 180px; background: #f4f6f7; }
.image-empty { display: flex; align-items: center; justify-content: center; padding: 16px; color: #7b8994; text-align: center; }
.empty-wrap { padding: 12px 0; }

@media (max-width: 680px) {
  .result-header { align-items: flex-start; flex-direction: column; }
  .sample-pair { grid-template-columns: 1fr; }
  .metric-value { font-size: 30px; }
}
</style>
