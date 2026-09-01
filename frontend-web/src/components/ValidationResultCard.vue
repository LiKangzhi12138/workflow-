<template>
  <div v-if="activeView" class="validation-result">
    <div class="result-header">
      <div>
        <div class="eyebrow">
          {{ activeView.validationMode === 'WORKFLOW' ? VALIDATION_TEXT.workflowResult : VALIDATION_TEXT.standaloneResult }}
        </div>
        <div class="result-title">{{ resultTitle }}</div>
        <div class="result-code">{{ activeView.validationCode || '-' }}</div>
      </div>

      <div class="result-tags">
        <el-tag :type="statusTagType" effect="dark">{{ statusLabel }}</el-tag>
        <el-tag v-if="activeView.qualityLabel" :type="qualityTagType">{{ activeView.qualityLabel }}</el-tag>
        <el-tag v-if="activeView.fallback" type="warning">回退指标</el-tag>
      </div>
    </div>

    <el-alert
      v-if="isRunning"
      type="info"
      :closable="false"
      show-icon
      class="status-alert"
      :title="VALIDATION_TEXT.runningTitle"
      :description="runningDescription"
    />

    <el-alert
      v-if="activeView.fallback"
      type="warning"
      :closable="false"
      show-icon
      class="status-alert"
      :title="VALIDATION_TEXT.fallbackTitle"
      :description="activeView.fallbackReason || VALIDATION_TEXT.fallbackDescription"
    />

    <el-alert
      v-if="activeView.status === 'FAILED'"
      type="error"
      :closable="false"
      show-icon
      class="status-alert"
      :title="VALIDATION_TEXT.failedTitle"
      :description="activeView.errorMessage || VALIDATION_TEXT.failedDescription"
    />

    <div class="metrics-shell">
      <div class="hero-metric">
        <div class="hero-label">{{ VALIDATION_TEXT.accuracyLabel }}</div>
        <div class="hero-value">{{ formatMetric(activeView.accuracy) }}</div>
        <div class="hero-tip">
          {{ activeView.accuracy == null ? VALIDATION_TEXT.accuracyTipEmpty : VALIDATION_TEXT.accuracyTip }}
        </div>
      </div>

      <div class="secondary-metrics">
        <div v-for="metric in secondaryMetrics" :key="metric.label" class="metric-card">
          <div class="metric-label">{{ metric.label }}</div>
          <div class="metric-value">{{ formatMetric(metric.value) }}</div>
        </div>
      </div>
    </div>

    <div class="section">
      <div class="section-title">{{ VALIDATION_TEXT.summaryTitle }}</div>
      <el-descriptions :column="2" border size="small" class="meta-grid">
        <el-descriptions-item :label="VALIDATION_TEXT.modelLabel">
          {{ activeView.modelName || '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="VALIDATION_TEXT.datasetLabel">
          {{ activeView.datasetName || '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="VALIDATION_TEXT.algorithmLabel">
          {{ activeView.algorithmType || '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="VALIDATION_TEXT.statusLabel">
          {{ statusLabel }}
        </el-descriptions-item>
        <el-descriptions-item :label="VALIDATION_TEXT.startedAtLabel">
          {{ formatDateTime(activeView.startedAt) }}
        </el-descriptions-item>
        <el-descriptions-item :label="VALIDATION_TEXT.finishedAtLabel">
          {{ formatDateTime(activeView.finishedAt) }}
        </el-descriptions-item>
        <el-descriptions-item :label="VALIDATION_TEXT.datasetSampleCountLabel">
          {{ activeView.datasetSampleCount ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="VALIDATION_TEXT.datasetImageCountLabel">
          {{ activeView.datasetImageCount ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="VALIDATION_TEXT.pythonJobIdLabel">
          {{ activeView.pythonJobId || '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="VALIDATION_TEXT.visualizationLabel">
          {{ visualizationModeLabel }}
        </el-descriptions-item>
        <el-descriptions-item :label="VALIDATION_TEXT.errorMessageLabel" :span="2">
          {{ activeView.errorMessage || '-' }}
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="section">
      <div class="section-title">{{ VALIDATION_TEXT.sampleTitle }}</div>
      <div v-if="sampleResults.length > 0" class="sample-grid">
        <div v-for="sample in sampleResults" :key="sample.sampleIndex" class="sample-card">
          <div class="sample-media">
            <el-image
              v-if="sample.imageUrl"
              :src="sample.imageUrl"
              fit="contain"
              class="sample-image"
              :preview-src-list="[sample.imageUrl]"
              preview-teleported
              @error="handleImageError(sample)"
            />
            <div v-else class="sample-empty">
              {{ VALIDATION_TEXT.sampleImageMissing }}
            </div>
            <div v-if="sample.isAnnotated ?? sample.annotatedImageAvailable" class="sample-badge">带框结果图</div>
          </div>

          <div class="sample-body">
            <div class="sample-name">
              {{ sample.imageName || `${VALIDATION_TEXT.samplePrefix} #${(sample.sampleIndex ?? 0) + 1}` }}
            </div>
            <div class="sample-meta">
              <span>{{ sample.predictionCount ?? sample.predictions.length }}{{ VALIDATION_TEXT.predictionSuffix }}</span>
              <span>{{ (sample.isAnnotated ?? sample.annotatedImageAvailable) ? VALIDATION_TEXT.sampleRendered : VALIDATION_TEXT.sampleSource }}</span>
            </div>

            <div v-if="sample.predictions.length > 0" class="prediction-list">
              <div
                v-for="prediction in sample.predictions"
                :key="`${sample.sampleIndex}-${prediction.label}-${prediction.confidence}-${prediction.bbox?.join('-') || 'no-bbox'}`"
                class="prediction-chip"
              >
                <span class="prediction-label">{{ prediction.label }}</span>
                <span class="prediction-confidence">{{ formatMetric(prediction.confidence, false) }}</span>
                <span v-if="prediction.bbox?.length === 4" class="prediction-bbox">
                  [{{ prediction.bbox.map((value) => Number(value).toFixed(0)).join(', ') }}]
                </span>
              </div>
            </div>

            <el-empty
              v-else
              :image-size="48"
              :description="VALIDATION_TEXT.samplePredictionEmpty"
            />
          </div>
        </div>
      </div>

      <el-empty
        v-else
        :description="activeView.emptyVisualizationReason || VALIDATION_TEXT.sampleEmpty"
      />
    </div>

    <div v-if="classResults.length > 0" class="section">
      <div class="section-title">{{ VALIDATION_TEXT.classTitle }}</div>
      <el-table :data="classResults" size="small" stripe>
        <el-table-column prop="className" :label="VALIDATION_TEXT.classNameLabel" min-width="140" />
        <el-table-column :label="VALIDATION_TEXT.categoryLabel" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="categoryTagType(row.category)">
              {{ categoryLabel(row.category) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="count" :label="VALIDATION_TEXT.countLabel" width="110" />
        <el-table-column :label="VALIDATION_TEXT.avgConfidenceLabel" width="140">
          <template #default="{ row }">
            {{ formatMetric(row.avgConfidence, false) }}
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>

  <div v-else class="empty-wrap">
    <el-empty :description="VALIDATION_TEXT.emptyResult" />
  </div>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import type {
  StandaloneValidationVO,
  ValidationResultClassItem,
  ValidationResultSample,
  ValidationResultView
} from '../api/validation'
import {
  VALIDATION_COPY_PACK,
  VALIDATION_STATUS_LABELS,
  VALIDATION_TEXT,
  VALIDATION_VISUALIZATION_LABELS
} from '../constants/validation'

interface Props {
  result?: StandaloneValidationVO | null
  view?: ValidationResultView | null
}

const props = withDefaults(defineProps<Props>(), {
  result: null,
  view: null
})

const activeView = computed<ValidationResultView | null>(() => {
  if (props.view) {
    return props.view
  }
  if (!props.result) {
    return null
  }

  let metrics: Record<string, any> = {}
  if (props.result.metricsJson) {
    try {
      metrics = JSON.parse(props.result.metricsJson)
    } catch (error) {
      console.error('[ValidationResultCard] 解析 metricsJson 失败', error)
    }
  }

  const classResults = metrics.perClassResults
    ? Object.entries(metrics.perClassResults).map(([className, info]: any) => ({
        className,
        category: info?.category,
        count: info?.count,
        avgConfidence: info?.avgConfidence ?? info?.avg_confidence
      }))
    : []

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
    fallback: metrics.fallback,
    fallbackReason: metrics.fallbackReason,
    qualityLabel: props.result.qualityLabel,
    errorMessage: props.result.errorMessage,
    resultFileAvailable: Boolean(props.result.resultFilePath),
    visualizationMode: 'METRICS_ONLY',
    emptyVisualizationReason: '当前任务暂时还没有可展示的识别样例图片。',
    startedAt: props.result.createdAt,
    finishedAt: props.result.finishedAt || props.result.updatedAt,
    classResults,
    sampleResults: []
  }
})

const statusLabel = computed(
  () => VALIDATION_STATUS_LABELS[activeView.value?.status || ''] || activeView.value?.status || VALIDATION_TEXT.unknown
)

const statusTagType = computed(() => {
  switch (activeView.value?.status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'VALIDATING':
    case 'TRAINING_RUNNING':
      return 'warning'
    default:
      return 'info'
  }
})

const qualityTagType = computed(() => {
  switch (activeView.value?.qualityLabel?.toLowerCase()) {
    case 'high':
    case '优秀':
      return 'success'
    case 'medium':
    case '良好':
      return 'warning'
    case 'low':
    case '待优化':
      return 'danger'
    default:
      return 'info'
  }
})

const resultTitle = computed(() =>
  activeView.value?.validationMode === 'WORKFLOW'
    ? VALIDATION_TEXT.workflowResult
    : VALIDATION_TEXT.standaloneResult
)

const isRunning = computed(() =>
  ['CREATED', 'ACCEPTED', 'PREPARING', 'TRAINING_RUNNING', 'VALIDATING', 'INITIATED'].includes(
    activeView.value?.status || ''
  )
)

const runningDescription = computed(() => {
  if (!activeView.value) {
    return VALIDATION_TEXT.runningDescription
  }
  return `当前状态：${statusLabel.value}，当前进度：${activeView.value.progress ?? 0}%。${VALIDATION_TEXT.runningDescription}`
})

const secondaryMetrics = computed(() => [
  { label: VALIDATION_TEXT.precisionLabel, value: activeView.value?.precision },
  { label: VALIDATION_TEXT.recallLabel, value: activeView.value?.recall },
  { label: VALIDATION_TEXT.map50Label, value: activeView.value?.map50 },
  { label: VALIDATION_TEXT.map5095Label, value: activeView.value?.map50_95 }
])

const sampleResults = computed<ValidationResultSample[]>(() => activeView.value?.sampleResults || [])
const classResults = computed<ValidationResultClassItem[]>(() => activeView.value?.classResults || [])

const visualizationModeLabel = computed(() => {
  const mode = activeView.value?.visualizationMode || 'METRICS_ONLY'
  return VALIDATION_VISUALIZATION_LABELS[mode] || mode
})

watch(
  () => [props.view, props.result],
  ([view, result]) => {
    const rawView = view as ValidationResultView | null | undefined
    console.info('[ValidationResultCard] 收到的原始数据', {
      view: rawView,
      result,
      viewSampleResults: rawView?.sampleResults || []
    })
  },
  { immediate: true, deep: true }
)

watch(
  () => activeView.value,
  (value) => {
    console.info('[ValidationResultCard] 使用文案常量', {
      copyPack: VALIDATION_COPY_PACK,
      validationMode: value?.validationMode,
      validationId: value?.validationId,
      workflowId: value?.workflowId,
      status: value?.status,
      sampleResultCount: value?.sampleResults?.length || 0
    })
  },
  { immediate: true }
)

watch(
  () => sampleResults.value,
  (items) => {
    console.info('[ValidationResultCard] 识别样例渲染', {
      count: items.length,
      imageUrlCount: items.filter((item) => Boolean(item.imageUrl)).length,
      imageUrls: items.map((item) => item.imageUrl || '')
    })
  },
  { immediate: true, deep: true }
)

watch(
  () => activeView.value?.emptyVisualizationReason,
  (reason) => {
    if (reason) {
      console.info('[ValidationResultCard] 识别样例空状态', { reason })
    }
  },
  { immediate: true }
)

function formatMetric(value?: number | null, asPercent: boolean = true) {
  if (value == null || Number.isNaN(Number(value))) {
    return '-'
  }
  const numberValue = asPercent ? Number(value) * 100 : Number(value)
  const suffix = asPercent ? '%' : ''
  return `${numberValue.toFixed(2)}${suffix}`
}

function formatDateTime(value?: string) {
  if (!value) {
    return '-'
  }
  try {
    return new Date(value).toLocaleString('zh-CN')
  } catch {
    return value
  }
}

function categoryLabel(category?: string) {
  switch (category) {
    case 'crop':
      return VALIDATION_TEXT.crop
    case 'livestock':
      return VALIDATION_TEXT.livestock
    default:
      return VALIDATION_TEXT.other
  }
}

function categoryTagType(category?: string) {
  if (category === 'crop') {
    return 'success'
  }
  if (category === 'livestock') {
    return 'warning'
  }
  return 'info'
}

function handleImageError(sample: ValidationResultSample) {
  console.error('[ValidationResultCard] 图片加载失败', {
    sampleIndex: sample.sampleIndex,
    imageName: sample.imageName,
    imageUrl: sample.imageUrl
  })
}
</script>

<style scoped>
.validation-result {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.eyebrow {
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #64748b;
}

.result-title {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.35;
}

.result-code {
  margin-top: 6px;
  font-size: 13px;
  color: #64748b;
}

.result-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.status-alert {
  margin-top: 0;
}

.metrics-shell {
  display: grid;
  grid-template-columns: minmax(240px, 1.1fr) minmax(0, 1.9fr);
  gap: 16px;
}

.hero-metric {
  padding: 24px;
  border-radius: 22px;
  background:
    radial-gradient(circle at top right, rgba(59, 130, 246, 0.2), transparent 38%),
    linear-gradient(135deg, #0f172a 0%, #1d4ed8 100%);
  color: #f8fafc;
  min-height: 190px;
}

.hero-label {
  font-size: 14px;
  opacity: 0.82;
}

.hero-value {
  margin-top: 18px;
  font-size: 54px;
  line-height: 1;
  font-weight: 800;
}

.hero-tip {
  margin-top: 18px;
  font-size: 13px;
  line-height: 1.6;
  color: rgba(248, 250, 252, 0.84);
}

.secondary-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.metric-card {
  padding: 18px;
  border-radius: 18px;
  border: 1px solid #dbe3f0;
  background: linear-gradient(180deg, #f8fafc 0%, #eef4ff 100%);
  min-height: 118px;
}

.metric-label {
  font-size: 13px;
  color: #64748b;
}

.metric-value {
  margin-top: 10px;
  font-size: 30px;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.2;
}

.section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-title {
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
}

.meta-grid {
  width: 100%;
}

.sample-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
}

.sample-card {
  overflow: hidden;
  border-radius: 18px;
  border: 1px solid #dbe3f0;
  background: #ffffff;
}

.sample-media {
  position: relative;
  background: linear-gradient(180deg, #f8fafc 0%, #eef2ff 100%);
  min-height: 220px;
  aspect-ratio: 16 / 10;
}

.sample-image {
  display: block;
  width: 100%;
  height: 100%;
  min-height: 220px;
  background: #f8fafc;
}

.sample-badge {
  position: absolute;
  top: 12px;
  right: 12px;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.76);
  color: #f8fafc;
  font-size: 12px;
}

.sample-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 220px;
  padding: 20px;
  color: #64748b;
  text-align: center;
}

.sample-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
}

.sample-name {
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
  word-break: break-all;
}

.sample-meta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
  color: #64748b;
}

.prediction-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.prediction-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  border-radius: 999px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  flex-wrap: wrap;
}

.prediction-label {
  font-size: 12px;
  font-weight: 600;
  color: #1d4ed8;
}

.prediction-confidence,
.prediction-bbox {
  font-size: 12px;
  color: #475569;
}

.empty-wrap {
  padding: 12px 0;
}

@media (max-width: 900px) {
  .metrics-shell {
    grid-template-columns: 1fr;
  }

  .secondary-metrics {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 640px) {
  .result-header {
    flex-direction: column;
  }

  .hero-value {
    font-size: 42px;
  }

  .secondary-metrics {
    grid-template-columns: 1fr;
  }
}
</style>
