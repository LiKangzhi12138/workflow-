<template>
  <div class="page">
    <el-page-header @back="router.back()" :title="VALIDATION_TEXT.workflowValidationTitle">
      <template #content>
        <span>{{ pageTitle }}</span>
      </template>
    </el-page-header>

    <el-card class="main-card" v-loading="workflowLoading && isWorkflowMode">
      <template v-if="isWorkflowMode">
        <div class="section-intro">
          <div class="section-title">{{ VALIDATION_TEXT.workflowValidationConsoleServer }}</div>
          <div class="section-desc">
            这里展示服务端工作流验证的运行状态和最终结果，独立本地验证会在单独区域展示。
          </div>
        </div>

        <template v-if="workflowDetail">
          <el-descriptions :column="2" border class="summary-grid">
            <el-descriptions-item label="工作流名称">
              {{ workflowDetail.workflowName }}
            </el-descriptions-item>
            <el-descriptions-item label="YOLO 版本">
              {{ workflowDetail.yoloVersion || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="workflowStatusTagType">{{ workflowStatusLabel }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="当前步骤">
              {{ workflowDetail.currentStep || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="验证模型">
              {{ workflowDetail.federatedModelAssetName || '联邦全局模型' }}
            </el-descriptions-item>
            <el-descriptions-item label="服务端数据集">
              {{ workflowDetail.serverDatasetAssetName || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="已收集模型">
              {{ workflowDetail.collectedModelCount || 0 }} / {{ workflowDetail.expectedModelCount || workflowDetail.clientModelCount || 0 }}
            </el-descriptions-item>
            <el-descriptions-item label="联邦状态">
              {{ workflowDetail.federatedStatus || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="全局模型可用性">
              <div class="availability-status">
                <el-tag :type="federatedModelAvailable ? 'success' : 'danger'">
                  {{ federatedModelAvailable ? '可用' : '不可用' }}
                </el-tag>
                <span v-if="!federatedModelAvailable" class="availability-reason">
                  {{ federatedModelUnavailableReason }}
                </span>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="Python 任务 ID">
              {{ workflowDetail.pythonJobId || '-' }}
            </el-descriptions-item>
          </el-descriptions>

          <div v-if="isStartableWorkflowStatus && !workflowDetail.pythonJobId" class="action-bar">
            <el-button
              type="primary"
              :loading="startingWorkflowValidation"
              :disabled="!canStartWorkflowValidation"
              @click="handleStartWorkflowValidation"
            >
              {{ VALIDATION_TEXT.workflowStartButton }}
            </el-button>
          </div>

          <el-alert
            v-if="workflowDetail.status === 'CREATED'"
            type="info"
            :closable="false"
            show-icon
            class="status-alert"
            :title="VALIDATION_TEXT.workflowWaitAcceptTitle"
            :description="VALIDATION_TEXT.workflowWaitAcceptDesc"
          />

          <el-alert
            v-else-if="isStartableWorkflowStatus && !workflowDetail.pythonJobId && !workflowDetail.serverDatasetAssetId"
            type="warning"
            :closable="false"
            show-icon
            class="status-alert"
            :title="VALIDATION_TEXT.workflowNeedDatasetTitle"
            :description="VALIDATION_TEXT.workflowNeedDatasetDesc"
          />

          <el-alert
            v-else-if="isStartableWorkflowStatus && !workflowDetail.pythonJobId && workflowDetail.serverDatasetAssetId && !federatedModelAvailable"
            type="warning"
            :closable="false"
            show-icon
            class="status-alert"
            title="联邦全局模型不可用"
            :description="federatedModelUnavailableReason"
          />

          <el-alert
            v-else-if="isStartableWorkflowStatus && !workflowDetail.pythonJobId && workflowDetail.serverDatasetAssetId && federatedModelAvailable"
            type="success"
            :closable="false"
            show-icon
            class="status-alert"
            :title="VALIDATION_TEXT.workflowReadyTitle"
            :description="VALIDATION_TEXT.workflowReadyDesc"
          />

          <div
            v-else-if="['PREPARING', 'TRAINING_RUNNING', 'VALIDATING'].includes(workflowDetail.status)"
            class="progress-block"
          >
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

      <StandaloneValidationWorkbench v-else role-code="SERVER" />
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
  startWorkflowValidation,
  type StandaloneValidationVO,
  type ValidationResultView
} from '../../api/validation'
import type { WorkflowDetail } from '../../api/workflow'
import { VALIDATION_COPY_PACK, VALIDATION_STATUS_LABELS, VALIDATION_TEXT } from '../../constants/validation'
import { summarizeUiErrorMessage } from '../../utils/errorMessage'

const route = useRoute()
const router = useRouter()

const workflowLoading = ref(false)
const workflowDetail = ref<WorkflowDetail | null>(null)
const workflowResultView = ref<ValidationResultView | null>(null)
const startingWorkflowValidation = ref(false)
let workflowPollTimer: number | null = null

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

const canStartWorkflowValidation = computed(() => {
  const detail = workflowDetail.value
  return Boolean(
    detail &&
      ['ACCEPTED', 'PREPARING'].includes(detail.status) &&
      detail.serverDatasetAssetId &&
      detail.federatedModelAvailable === true &&
      !detail.pythonJobId
  )
})

const isStartableWorkflowStatus = computed(() =>
  ['ACCEPTED', 'PREPARING'].includes(workflowDetail.value?.status || '')
)

const federatedModelAvailable = computed(() => workflowDetail.value?.federatedModelAvailable === true)

const federatedModelUnavailableReason = computed(() =>
  workflowDetail.value?.federatedModelUnavailableReason || '联邦全局模型不可用，无法启动验证。'
)

const workflowValidationResult = computed<StandaloneValidationVO | null>(() => {
  if (!workflowDetail.value) {
    return null
  }
  return {
    id: workflowDetail.value.id,
    validationCode: workflowDetail.value.workflowCode,
    validationMode: 'WORKFLOW',
    userId: workflowDetail.value.serverUserId,
    modelAssetId: workflowDetail.value.federatedModelAssetId || 0,
    modelAssetName: workflowDetail.value.federatedModelAssetName || '联邦全局模型',
    modelPath: '',
    modelYoloVersion: workflowDetail.value.yoloVersion,
    modelStatus: 'READY',
    datasetAssetId: workflowDetail.value.serverDatasetAssetId || 0,
    datasetAssetName: workflowDetail.value.serverDatasetAssetName,
    datasetPath: '',
    datasetStatus: 'READY',
    datasetSampleCount: undefined,
    datasetImageCount: undefined,
    algorithmType: workflowDetail.value.yoloVersion || 'YOLO',
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

async function loadWorkflowResultView(id: number) {
  console.info('[ServerValidationView] 加载工作流结果详情', {
    workflowId: id,
    copyPack: VALIDATION_COPY_PACK
  })
  try {
    const res = await getWorkflowValidationResultView(id)
    workflowResultView.value = res.data
    console.info('[ServerValidationView] 工作流结果详情已加载', {
      workflowId: id,
      status: res.data?.status,
      sampleCount: res.data?.sampleResults?.length || 0
    })
  } catch (error) {
    workflowResultView.value = null
    console.error('[ServerValidationView] 加载工作流结果详情失败', error)
  }
}

async function loadWorkflowDetail() {
  if (!isWorkflowMode.value || workflowId.value == null) {
    workflowDetail.value = null
    workflowResultView.value = null
    return
  }

  workflowLoading.value = true
  console.info('[ServerValidationView] 加载工作流验证详情', {
    workflowId: workflowId.value,
    copyPack: VALIDATION_COPY_PACK
  })
  try {
    const res = await getWorkflowDetailForValidation(workflowId.value)
    workflowDetail.value = res.data
    await loadWorkflowResultView(workflowId.value)
    console.info('[ServerValidationView] 工作流验证详情已加载', {
      workflowId: workflowId.value,
      status: res.data?.status,
      progress: res.data?.progress
    })

    if (['PREPARING', 'TRAINING_RUNNING', 'VALIDATING'].includes(res.data?.status || '')) {
      startWorkflowPolling()
    } else {
      stopWorkflowPolling()
    }
  } catch (error) {
    const message = resolveErrorMessage(error, VALIDATION_TEXT.workflowLoadFailed)
    console.error('[ServerValidationView] 加载工作流验证详情失败', error)
    ElMessage.error(message)
    workflowDetail.value = null
    workflowResultView.value = null
  } finally {
    workflowLoading.value = false
  }
}

async function handleStartWorkflowValidation() {
  if (workflowId.value == null) {
    return
  }
  if (!canStartWorkflowValidation.value) {
    ElMessage.warning(
      !workflowDetail.value?.serverDatasetAssetId
        ? '请先绑定已校验通过的服务端数据集。'
        : federatedModelUnavailableReason.value
    )
    return
  }

  startingWorkflowValidation.value = true
  console.info('[ServerValidationView] 发起工作流验证', { workflowId: workflowId.value })
  try {
    await startWorkflowValidation(workflowId.value)
    ElMessage.success(VALIDATION_TEXT.workflowStartSuccess)
    console.info('[ServerValidationView] 工作流验证已启动', { workflowId: workflowId.value })
    await loadWorkflowDetail()
    startWorkflowPolling()
  } catch (error) {
    const message = resolveErrorMessage(error, VALIDATION_TEXT.workflowStartFailed)
    console.error('[ServerValidationView] 启动工作流验证失败', error)
    ElMessage.error(message)
  } finally {
    startingWorkflowValidation.value = false
  }
}

function startWorkflowPolling() {
  if (workflowId.value == null) {
    return
  }
  stopWorkflowPolling()
  console.info('[ServerValidationView] 开始轮询工作流验证状态', { workflowId: workflowId.value })
  workflowPollTimer = window.setInterval(async () => {
    try {
      const res = await getWorkflowDetailForValidation(workflowId.value!)
      workflowDetail.value = res.data
      await loadWorkflowResultView(workflowId.value!)
      console.info('[ServerValidationView] 工作流验证轮询', {
        workflowId: workflowId.value,
        status: res.data?.status,
        progress: res.data?.progress
      })

      if (!['PREPARING', 'TRAINING_RUNNING', 'VALIDATING'].includes(res.data?.status || '')) {
        stopWorkflowPolling()
      }
    } catch (error) {
      console.error('[ServerValidationView] 工作流验证轮询失败', error)
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
    stopWorkflowPolling()
    workflowDetail.value = null
    workflowResultView.value = null
  }
})

onMounted(() => {
  console.info('[ServerValidationView] 页面挂载', {
    workflowId: workflowId.value,
    isWorkflowMode: isWorkflowMode.value,
    copyPack: VALIDATION_COPY_PACK
  })
  if (isWorkflowMode.value) {
    loadWorkflowDetail()
  }
})

onUnmounted(() => {
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

.action-bar {
  display: flex;
  justify-content: flex-start;
  margin-bottom: 16px;
}

.status-alert {
  margin-top: 12px;
}

.availability-status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.availability-reason {
  color: #b42318;
  line-height: 1.5;
  overflow-wrap: anywhere;
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
