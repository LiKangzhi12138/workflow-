<template>
  <div class="page">
    <el-page-header @back="router.back()" :title="VALIDATION_TEXT.workflowValidationTitle">
      <template #content>
        <span>{{ pageTitle }}</span>
      </template>
    </el-page-header>

    <el-card class="main-card" v-loading="workflowLoading && isWorkflowMode">
      <template v-if="isWorkflowMode">
        <template v-if="workflowDetail">
          <el-descriptions :column="2" border class="summary-grid">
            <el-descriptions-item label="工作流名称">
              {{ workflowDetail.workflowName }}
            </el-descriptions-item>
            <el-descriptions-item label="模型">
              {{ workflowDetail.modelDefinitionDisplayName || workflowDetail.yoloVersion || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="workflowStatusTagType">{{ workflowStatusLabel }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="服务端数据集">
              {{ workflowDetail.serverDatasetAssetName || '-' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="['CREATED', 'ACCEPTED', 'PREPARING'].includes(workflowDetail.status)"
            type="info"
            :closable="false"
            show-icon
            class="status-alert"
            :title="VALIDATION_TEXT.workflowPreparingTitle"
            :description="VALIDATION_TEXT.workflowPreparingDesc"
          />

          <div v-else-if="['TRAINING_RUNNING', 'VALIDATING'].includes(workflowDetail.status)" class="progress-block">
            <div class="progress-title">{{ VALIDATION_TEXT.workflowRunningTitle }}</div>
            <el-progress :percentage="workflowDetail.progress || 0" />
          </div>

          <ValidationResultCard
            v-else-if="['COMPLETED', 'FAILED'].includes(workflowDetail.status)"
            :result="workflowValidationResult"
            :view="workflowResultView"
          />
        </template>

        <el-empty v-else :description="VALIDATION_TEXT.workflowDetailEmpty" />
      </template>

      <StandaloneValidationWorkbench v-else role-code="CLIENT" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ValidationResultCard from '../../components/ValidationResultCard.vue'
import StandaloneValidationWorkbench from '../../components/StandaloneValidationWorkbench.vue'
import {
  getWorkflowDetailForValidation,
  getWorkflowValidationResultView,
  type StandaloneValidationVO,
  type ValidationResultView
} from '../../api/validation'
import type { WorkflowDetail } from '../../api/workflow'
import { VALIDATION_STATUS_LABELS, VALIDATION_TEXT } from '../../constants/validation'
import { summarizeUiErrorMessage } from '../../utils/errorMessage'
import { isCurrentRequest } from '../../utils/workflowProgressState'

const route = useRoute()
const router = useRouter()

const workflowLoading = ref(false)
const workflowDetail = ref<WorkflowDetail | null>(null)
const workflowResultView = ref<ValidationResultView | null>(null)
let workflowPollTimer: number | null = null
let workflowRequestSequence = 0

const workflowId = computed(() => {
  const raw = route.query.workflowId
  return raw ? Number(raw) : null
})

const isWorkflowMode = computed(() => workflowId.value != null && !Number.isNaN(workflowId.value))

const pageTitle = computed(() =>
  isWorkflowMode.value ? VALIDATION_TEXT.workflowValidationTitle : VALIDATION_TEXT.standaloneValidationTitle
)

const workflowStatusLabel = computed(() =>
  VALIDATION_STATUS_LABELS[workflowDetail.value?.status || ''] || workflowDetail.value?.status || '-'
)

const workflowStatusTagType = computed(() => {
  switch (workflowDetail.value?.status) {
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

const workflowValidationResult = computed<StandaloneValidationVO | null>(() => {
  if (!workflowDetail.value) {
    return null
  }
  return {
    id: workflowDetail.value.id,
    validationCode: workflowDetail.value.workflowCode,
    validationMode: 'WORKFLOW',
    userId: workflowDetail.value.initiatorUserId,
    modelAssetId: workflowDetail.value.federatedModelAssetId || workflowDetail.value.clientModelAssetId || 0,
    modelAssetName: workflowDetail.value.federatedModelAssetName || workflowDetail.value.clientModelAssetName,
    modelPath: '',
    modelYoloVersion: workflowDetail.value.yoloVersion,
    modelStatus: 'READY',
    datasetAssetId: workflowDetail.value.serverDatasetAssetId || 0,
    datasetAssetName: workflowDetail.value.serverDatasetAssetName,
    datasetPath: '',
    datasetStatus: 'READY',
    datasetSampleCount: undefined,
    datasetImageCount: undefined,
    algorithmType: workflowDetail.value.modelDefinitionFamily || workflowDetail.value.yoloVersion || '未指定',
    status: workflowDetail.value.status,
    progress: workflowDetail.value.progress,
    pythonJobId: workflowDetail.value.pythonJobId,
    metricsJson: workflowDetail.value.metricsJson,
    resultFilePath: workflowDetail.value.resultFilePath,
    qualityLabel: undefined,
    errorMessage: summarizeUiErrorMessage(workflowDetail.value.errorMessage, '验证任务失败，请查看服务端日志'),
    finishedAt: ['COMPLETED', 'FAILED'].includes(workflowDetail.value.status) ? workflowDetail.value.updatedAt : undefined,
    createdAt: workflowDetail.value.createdAt,
    updatedAt: workflowDetail.value.updatedAt
  }
})

function resolveErrorMessage(error: any, fallback: string) {
  return summarizeUiErrorMessage(error?.response?.data?.message || error?.message, fallback)
}

async function loadWorkflowResultView(id: number, requestSequence: number) {
  console.info('[ClientValidationView] 加载工作流结果详情', {
    workflowId: id
  })
  try {
    const res = await getWorkflowValidationResultView(id)
    if (!isCurrentRequest(requestSequence, workflowRequestSequence) || workflowId.value !== id) return
    workflowResultView.value = res.data
    console.info('[ClientValidationView] 工作流结果详情已加载', {
      workflowId: id,
      status: res.data?.status,
      sampleCount: res.data?.sampleResults?.length || 0
    })
  } catch (error) {
    if (!isCurrentRequest(requestSequence, workflowRequestSequence) || workflowId.value !== id) return
    workflowResultView.value = null
    console.error('[ClientValidationView] 加载工作流结果详情失败', error)
  }
}

async function loadWorkflowDetail() {
  const requestSequence = ++workflowRequestSequence
  if (!isWorkflowMode.value || workflowId.value == null) {
    workflowDetail.value = null
    workflowResultView.value = null
    return
  }

  workflowLoading.value = true
  const requestedWorkflowId = workflowId.value
  console.info('[ClientValidationView] 加载工作流验证详情', {
    workflowId: workflowId.value
  })
  try {
    const res = await getWorkflowDetailForValidation(requestedWorkflowId)
    if (!isCurrentRequest(requestSequence, workflowRequestSequence) || workflowId.value !== requestedWorkflowId) return
    workflowDetail.value = res.data
    await loadWorkflowResultView(requestedWorkflowId, requestSequence)
    if (!isCurrentRequest(requestSequence, workflowRequestSequence) || workflowId.value !== requestedWorkflowId) return
    console.info('[ClientValidationView] 工作流验证详情已加载', {
      workflowId: workflowId.value,
      status: res.data?.status,
      progress: res.data?.progress
    })

    if (['TRAINING_RUNNING', 'VALIDATING', 'PREPARING'].includes(res.data?.status || '')) {
      startWorkflowPolling()
    } else {
      stopWorkflowPolling()
    }
  } catch (error) {
    if (!isCurrentRequest(requestSequence, workflowRequestSequence) || workflowId.value !== requestedWorkflowId) return
    const message = resolveErrorMessage(error, VALIDATION_TEXT.workflowLoadFailed)
    console.error('[ClientValidationView] 加载工作流验证详情失败', error)
    ElMessage.error(message)
    workflowDetail.value = null
    workflowResultView.value = null
  } finally {
    if (isCurrentRequest(requestSequence, workflowRequestSequence)) workflowLoading.value = false
  }
}

function startWorkflowPolling() {
  if (workflowId.value == null) {
    return
  }
  stopWorkflowPolling()
  console.info('[ClientValidationView] 开始轮询工作流验证状态', { workflowId: workflowId.value })
  workflowPollTimer = window.setInterval(async () => {
    const requestedWorkflowId = workflowId.value
    if (requestedWorkflowId == null) return
    const requestSequence = ++workflowRequestSequence
    try {
      const res = await getWorkflowDetailForValidation(requestedWorkflowId)
      if (!isCurrentRequest(requestSequence, workflowRequestSequence) || workflowId.value !== requestedWorkflowId) return
      workflowDetail.value = res.data
      await loadWorkflowResultView(requestedWorkflowId, requestSequence)
      if (!isCurrentRequest(requestSequence, workflowRequestSequence) || workflowId.value !== requestedWorkflowId) return
      console.info('[ClientValidationView] 工作流验证轮询', {
        workflowId: workflowId.value,
        status: res.data?.status,
        progress: res.data?.progress
      })

      if (!['TRAINING_RUNNING', 'VALIDATING', 'PREPARING'].includes(res.data?.status || '')) {
        stopWorkflowPolling()
      }
    } catch (error) {
      if (!isCurrentRequest(requestSequence, workflowRequestSequence) || workflowId.value !== requestedWorkflowId) return
      console.error('[ClientValidationView] 工作流验证轮询失败', error)
    }
  }, 4000)
}

function stopWorkflowPolling() {
  if (workflowPollTimer != null) {
    window.clearInterval(workflowPollTimer)
    workflowPollTimer = null
  }
}

watch(workflowId, () => {
  if (isWorkflowMode.value) {
    loadWorkflowDetail()
  } else {
    workflowRequestSequence++
    stopWorkflowPolling()
    workflowDetail.value = null
    workflowResultView.value = null
  }
})

onMounted(() => {
  console.info('[ClientValidationView] 页面挂载', {
    workflowId: workflowId.value,
    isWorkflowMode: isWorkflowMode.value
  })
  if (isWorkflowMode.value) {
    loadWorkflowDetail()
  }
})

onUnmounted(() => {
  workflowRequestSequence++
  stopWorkflowPolling()
})
</script>

<style scoped>
.page {
  padding: 20px;
}

.main-card {
  margin-top: 20px;
  border-radius: 18px;
}

.section-intro {
  margin-bottom: 16px;
}

.section-title {
  font-size: 20px;
  font-weight: 700;
  color: #111827;
}

.section-desc {
  margin-top: 8px;
  color: #64748b;
  line-height: 1.7;
}

.summary-grid {
  margin-bottom: 16px;
}

.status-alert {
  margin-top: 12px;
}

.progress-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 12px;
}

.progress-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}
</style>
