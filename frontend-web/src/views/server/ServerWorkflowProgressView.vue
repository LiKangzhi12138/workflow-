<template>
  <div class="page">
    <el-row :gutter="16">
      <el-col :xs="24" :lg="7">
        <el-card shadow="never" class="sidebar-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">{{ WORKFLOW_PROGRESS_TEXT.latestWorkflows }}</div>
                <div class="card-desc">{{ WORKFLOW_PROGRESS_TEXT.latestWorkflowsDescServer }}</div>
              </div>
              <el-button text @click="initializePage">
                {{ WORKFLOW_PROGRESS_TEXT.refresh }}
              </el-button>
            </div>
          </template>

          <div v-loading="listLoading" class="sidebar-content">
            <template v-if="workflowList.length">
              <div
                v-for="item in workflowList"
                :key="item.id"
                class="workflow-item"
                :class="{ active: item.id === activeWorkflowId }"
                @click="selectWorkflow(item.id, true)"
              >
                <div class="workflow-item-head">
                  <div class="workflow-item-title">{{ item.workflowName }}</div>
                  <el-tag v-if="item.id === activeWorkflowId" size="small" type="primary">
                    {{ WORKFLOW_PROGRESS_TEXT.selectedBadge }}
                  </el-tag>
                </div>
                <div class="workflow-item-code">{{ item.workflowCode }}</div>
                <div class="workflow-item-meta">
                  <span>{{ getWorkflowStatusLabel(item.status) }}</span>
                  <span>{{ formatDateTime(item.updatedAt || item.createdAt) }}</span>
                </div>
              </div>
            </template>
            <el-empty v-else :description="WORKFLOW_PROGRESS_TEXT.emptyServer" />
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="17">
        <el-card shadow="never" class="main-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">{{ WORKFLOW_PROGRESS_TEXT.serverPageTitle }}</div>
                <div class="card-desc">{{ WORKFLOW_PROGRESS_TEXT.serverVisualDesc }}</div>
              </div>
            </div>
          </template>

          <div v-loading="detailLoading">
            <template v-if="workflowDetail">
              <div class="hero-card">
                <div>
                  <div class="hero-title">{{ workflowDetail.workflowName }}</div>
                  <div class="hero-code">{{ workflowDetail.workflowCode }}</div>
                </div>
                <el-tag :type="getWorkflowStatusTagType(workflowDetail.status)" effect="dark">
                  {{ getWorkflowStatusLabel(workflowDetail.status) }}
                </el-tag>
              </div>

              <el-descriptions :column="2" border class="info-card">
                <el-descriptions-item :label="WORKFLOW_PROGRESS_TEXT.workflowStatus">
                  {{ getWorkflowStatusLabel(workflowDetail.status) }}
                </el-descriptions-item>
                <el-descriptions-item :label="WORKFLOW_PROGRESS_TEXT.currentStep">
                  {{ workflowDetail.currentStep || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="模型定义">
                  {{ getWorkflowModelDisplayName(workflowDetail) }}
                </el-descriptions-item>
                <el-descriptions-item :label="WORKFLOW_PROGRESS_TEXT.currentProgress">
                  <el-progress :percentage="workflowDetail.progress || 0" />
                </el-descriptions-item>
                <el-descriptions-item :label="WORKFLOW_PROGRESS_TEXT.modelCountLabel">
                  {{ requiredModelCount }}
                </el-descriptions-item>
                <el-descriptions-item :label="WORKFLOW_PROGRESS_TEXT.receivedCountLabel">
                  {{ uploadProgress?.receivedModelCount ?? workflowDetail.receivedModelCount ?? 0 }}
                </el-descriptions-item>
                <el-descriptions-item :label="WORKFLOW_PROGRESS_TEXT.managedCountLabel">
                  {{ uploadProgress?.collectedModelCount ?? workflowDetail.collectedModelCount ?? 0 }}
                </el-descriptions-item>
                <el-descriptions-item label="联邦状态">
                  {{ federatedStatusLabel }}
                </el-descriptions-item>
                <el-descriptions-item label="全局模型">
                  <div class="model-availability">
                    <span>{{ workflowDetail.federatedModelAssetName || '尚未生成' }}</span>
                    <el-tag :type="federatedReady ? 'success' : 'danger'" size="small">
                      {{ federatedReady ? '可用' : '不可用' }}
                    </el-tag>
                    <span v-if="!federatedReady" class="availability-reason">
                      {{ workflowDetail.federatedModelUnavailableReason || '联邦全局模型不可用。' }}
                    </span>
                  </div>
                </el-descriptions-item>
                <el-descriptions-item label="差分隐私">
                  {{ formatMechanismEnabled(workflowDetail.dpEnabled) }} / {{ getPrivacyStatusLabel(workflowDetail.dpStatus) }}
                </el-descriptions-item>
                <el-descriptions-item label="DP 参数">
                  ε={{ workflowDetail.dpEpsilon ?? '-' }}，δ={{ workflowDetail.dpDelta ?? '-' }}，裁剪={{ workflowDetail.dpClipNorm ?? '-' }}，噪声={{ workflowDetail.dpNoiseMultiplier ?? '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="安全洗牌">
                  {{ formatMechanismEnabled(workflowDetail.shuffleEnabled) }} / {{ getPrivacyStatusLabel(workflowDetail.shuffleStatus) }}
                </el-descriptions-item>
                <el-descriptions-item label="洗牌批次">
                  {{ workflowDetail.shuffleBatchNo || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="安全聚合">
                  {{ formatMechanismEnabled(workflowDetail.secureAggregationEnabled) }} / {{ getPrivacyStatusLabel(workflowDetail.secureAggregationStatus) }}
                </el-descriptions-item>
                <el-descriptions-item label="聚合模式">
                  {{ workflowDetail.secureAggregationMode || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="机制摘要" :span="2">
                  {{ workflowDetail.dpSummary || '-' }}；{{ workflowDetail.shuffleOrderSummary || '-' }}；{{ workflowDetail.secureAggregationSummary || '-' }}
                </el-descriptions-item>
                <el-descriptions-item :label="WORKFLOW_PROGRESS_TEXT.serverDatasetLabel">
                  {{ workflowDetail.serverDatasetAssetName || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="Python Job ID">
                  {{ workflowDetail.pythonJobId || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="当前结果">
                  <el-tag :type="resultRetentionTagType">
                    {{ resultRetentionLabel }}
                  </el-tag>
                </el-descriptions-item>
              </el-descriptions>

              <el-card shadow="never" class="section-card">
                <template #header>
                  <div class="section-title-row">
                    <div>
                      <div class="section-title">{{ WORKFLOW_PROGRESS_TEXT.serverVisualTitle }}</div>
                      <div class="section-desc">{{ WORKFLOW_PROGRESS_TEXT.phaseProgressHint }}</div>
                    </div>
                    <el-progress
                      :percentage="serverPhasePercent"
                      :stroke-width="10"
                      class="phase-progress"
                    />
                  </div>
                </template>

                <div class="phase-grid">
                  <div
                    v-for="phase in serverPhases"
                    :key="phase.key"
                    class="phase-card"
                    :class="phase.status"
                  >
                    <div class="phase-state">{{ phase.statusText }}</div>
                    <div class="phase-name">{{ phase.title }}</div>
                    <div class="phase-desc">{{ phase.description }}</div>
                  </div>
                </div>

                <el-alert
                  :title="serverSummaryTitle"
                  :description="serverSummaryDescription"
                  :type="serverSummaryType"
                  :closable="false"
                  show-icon
                  class="summary-alert"
                />
              </el-card>

              <el-card shadow="never" class="section-card">
                <template #header>
                  <div class="section-title">{{ WORKFLOW_PROGRESS_TEXT.serverPageTitle }}操作区</div>
                </template>

                <div class="action-grid">
                  <el-button
                    type="success"
                    :loading="accepting"
                    :disabled="!canAcceptWorkflow"
                    @click="handleAcceptWorkflow"
                  >
                    {{ WORKFLOW_PROGRESS_TEXT.acceptWorkflow }}
                  </el-button>

                  <div class="dataset-bind-box">
                    <el-select
                      v-model="selectedDatasetId"
                      filterable
                      clearable
                      :placeholder="WORKFLOW_PROGRESS_TEXT.bindDatasetPlaceholder"
                    >
                      <el-option
                        v-for="item in datasetOptions"
                        :key="item.id"
                        :label="`${item.assetName}（${item.dataFormat}）`"
                        :value="item.id"
                      />
                    </el-select>
                    <el-button
                      type="warning"
                      :loading="bindingDataset"
                      :disabled="!canBindDataset || !selectedDatasetId"
                      @click="handleBindDataset"
                    >
                      {{ WORKFLOW_PROGRESS_TEXT.bindDataset }}
                    </el-button>
                  </div>

                  <el-button
                    type="primary"
                    :loading="startingValidation"
                    :disabled="!canStartValidation"
                    @click="handleStartValidation"
                  >
                    {{ WORKFLOW_PROGRESS_TEXT.startValidation }}
                  </el-button>

                  <el-button
                    v-if="workflowDetail.pythonJobId || workflowDetail.status === 'COMPLETED'"
                    text
                    type="primary"
                    @click="openValidationPage"
                  >
                    {{ WORKFLOW_PROGRESS_TEXT.openValidation }}
                  </el-button>

                  <el-button
                    v-if="canSaveWorkflowResult"
                    type="success"
                    plain
                    :loading="savingResult"
                    @click="handleSaveWorkflowResult"
                  >
                    保存结果
                  </el-button>

                  <el-button
                    v-if="canDeleteSavedWorkflowResult"
                    type="danger"
                    plain
                    :loading="deletingResult"
                    @click="handleDeleteSavedWorkflowResult"
                  >
                    删除已保存结果
                  </el-button>
                </div>

                <el-alert
                  v-if="!canStartValidation && validationReadinessHint"
                  :title="validationReadinessHint"
                  type="info"
                  :closable="false"
                  show-icon
                  class="action-hint"
                />
              </el-card>

              <el-card shadow="never" class="section-card">
                <template #header>
                  <div class="section-title">{{ WORKFLOW_PROGRESS_TEXT.uploadRecords }}</div>
                </template>

                <el-table
                  v-if="uploadRecords.length"
                  :data="uploadRecords"
                  size="small"
                  border
                >
                  <el-table-column label="上传用户" width="140">
                    <template #default="{ row }">
                      {{ row.clientDisplayName || row.uploaderName || '-' }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="originalFilename" label="模型文件" min-width="220" />
                  <el-table-column label="处理状态" width="240">
                    <template #default="{ row }">
                      <div class="status-cell">
                        <el-tag :type="getUploadStatusTagType(row.uploadStatus)">
                          {{ getUploadStatusLabel(row.uploadStatus) }}
                        </el-tag>
                        <span v-if="row.errorMessage" class="status-error">{{ row.errorMessage }}</span>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="聚合状态" width="120">
                    <template #default="{ row }">
                      <el-tag :type="getAggregationStatusTagType(row.aggregationStatus)">
                        {{ getAggregationStatusLabel(row.aggregationStatus) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="服务端模型资产" width="160">
                    <template #default="{ row }">
                      {{ row.serverModelAssetId || '-' }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="uploadedAt" label="上传时间" width="180">
                    <template #default="{ row }">
                      {{ formatDateTime(row.uploadedAt) }}
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-else :description="WORKFLOW_PROGRESS_TEXT.uploadListEmpty" />
              </el-card>

              <el-card shadow="never" class="section-card">
                <template #header>
                  <div class="section-title">{{ WORKFLOW_PROGRESS_TEXT.detailSteps }}</div>
                </template>
                <el-timeline v-if="workflowDetail.steps?.length">
                  <el-timeline-item
                    v-for="step in workflowDetail.steps"
                    :key="`${step.stepNo}-${step.createdAt}`"
                    :timestamp="formatDateTime(step.createdAt)"
                    placement="top"
                  >
                    <div class="step-title">{{ step.stepNo }}. {{ step.stepName }}</div>
                    <div class="step-text">步骤编码：{{ step.stepCode }}</div>
                    <div class="step-text">状态流转：{{ step.fromStatus || '-' }} -> {{ step.toStatus }}</div>
                    <div class="step-text">说明：{{ step.message || '-' }}</div>
                  </el-timeline-item>
                </el-timeline>
                <el-empty v-else description="暂无步骤记录" />
              </el-card>
            </template>

            <el-empty
              v-else
              :description="workflowList.length ? WORKFLOW_PROGRESS_TEXT.noWorkflowSelected : WORKFLOW_PROGRESS_TEXT.emptyServer"
            />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog
      v-model="decryptDialogVisible"
      :title="WORKFLOW_PROGRESS_TEXT.decryptDialogTitle"
      width="720px"
      :show-close="decryptDialogCanClose"
      :close-on-click-modal="decryptDialogCanClose"
      :before-close="handleDecryptDialogClose"
    >
      <div class="progress-dialog-body">
        <el-alert
          :title="getServerProcessTitle(serverProcessPhase)"
          :description="decryptDialogDescription"
          :type="hasProcessFailure ? 'error' : decryptDialogCanClose ? 'success' : 'info'"
          :closable="false"
          show-icon
          style="margin-bottom: 16px"
        />

        <el-descriptions :column="1" border size="small" class="dialog-info-card">
          <el-descriptions-item label="工作流 ID">{{ resolvedWorkflowId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="当前步骤">{{ workflowDetail?.currentStep || '-' }}</el-descriptions-item>
          <el-descriptions-item label="处理阶段">{{ getServerProcessTitle(serverProcessPhase) }}</el-descriptions-item>
          <el-descriptions-item label="已接收 / 总数">
            {{ receivedModelCount }} / {{ requiredModelCount }}
          </el-descriptions-item>
          <el-descriptions-item label="已纳管 / 总数">
            {{ managedModelCount }} / {{ requiredModelCount }}
          </el-descriptions-item>
          <el-descriptions-item label="联邦状态">
            {{ federatedStatusLabel }}
          </el-descriptions-item>
        </el-descriptions>

        <el-progress :percentage="serverPhasePercent" :stroke-width="14" style="margin: 18px 0" />

        <el-steps :active="Math.min(SERVER_VISUAL_STAGES.findIndex((item) => item.key === serverPhaseKey) + 1, SERVER_VISUAL_STAGES.length)" finish-status="success">
          <el-step v-for="phase in SERVER_VISUAL_STAGES" :key="phase.key" :title="phase.title" />
        </el-steps>

        <div v-if="hasProcessFailure" class="dialog-tip-text dialog-error-text">
          失败原因：{{ workflowDetail?.errorMessage || uploadRecords.find((item) => item.errorMessage)?.errorMessage || '未知错误' }}
        </div>
      </div>

      <template #footer>
        <el-button
          type="primary"
          :disabled="!decryptDialogCanClose"
          @click="forceCloseDecryptDialog"
        >
          {{ WORKFLOW_PROGRESS_TEXT.decryptDialogClose }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { TagProps } from 'element-plus'
import {
  advanceWorkflowApi,
  bindServerDatasetApi,
  deleteSavedWorkflowResultApi,
  getWorkflowDetailApi,
  getWorkflowUploadProgress,
  listAllWorkflowsApi,
  saveWorkflowResultApi,
  startPythonJobApi,
  type UploadProgressVO,
  type WorkflowDetail,
  type WorkflowListItem,
  type WorkflowStatus
} from '@/api/workflow'
import { listAllDatasetAssetsApi, type DatasetAssetItem } from '@/api/dataset'
import { summarizeUiErrorMessage } from '@/utils/errorMessage'
import { getWorkflowModelDisplayName } from '@/utils/workflowModelDisplay'
import {
  computeServerProcessPercent,
  formatMechanismEnabled,
  getServerProcessDescription,
  getServerProcessTitle,
  getPrivacyStatusLabel,
  SERVER_VISUAL_STAGES,
  WORKFLOW_PROGRESS_TEXT,
  getUploadStatusLabel,
  getWorkflowStatusLabel,
  isServerProcessDialogClosable,
  resolveServerProcessPhase,
  sortWorkflowsByLatest
} from '@/constants/workflowProgress'

type PhaseState = 'done' | 'current' | 'pending' | 'error'

const route = useRoute()
const router = useRouter()

const listLoading = ref(false)
const detailLoading = ref(false)
const accepting = ref(false)
const bindingDataset = ref(false)
const startingValidation = ref(false)
const savingResult = ref(false)
const deletingResult = ref(false)

const workflowList = ref<WorkflowListItem[]>([])
const activeWorkflowId = ref<number | null>(null)
const workflowDetail = ref<WorkflowDetail | null>(null)
const uploadProgress = ref<UploadProgressVO | null>(null)
const datasetOptions = ref<DatasetAssetItem[]>([])
const selectedDatasetId = ref<number | null>(null)
const decryptDialogVisible = ref(false)
const decryptDialogTriggeredWorkflowId = ref<number | null>(null)

let pollingTimer: number | null = null

const routeWorkflowId = computed(() => {
  const id = route.query.id || route.params.id
  const value = Number(id)
  return Number.isFinite(value) && value > 0 ? value : null
})

const resolvedWorkflowId = computed(() => workflowDetail.value?.id || activeWorkflowId.value || null)

const uploadRecords = computed(() => uploadProgress.value?.uploadRecords || [])

const requiredModelCount = computed(() => {
  const count = Number(
    workflowDetail.value?.expectedModelCount ||
      workflowDetail.value?.clientModelCount ||
      uploadProgress.value?.requiredModelCount ||
      1
  )
  return count > 0 ? count : 1
})

const receivedModelCount = computed(
  () => uploadProgress.value?.receivedModelCount ?? workflowDetail.value?.receivedModelCount ?? 0
)
const managedModelCount = computed(
  () => uploadProgress.value?.collectedModelCount ?? workflowDetail.value?.collectedModelCount ?? 0
)
const federatedReady = computed(() => workflowDetail.value?.federatedModelAvailable === true)
const canAcceptWorkflow = computed(() => workflowDetail.value?.status === 'CREATED')
const hasProcessFailure = computed(
  () =>
    workflowDetail.value?.status === 'FAILED' ||
    workflowDetail.value?.federatedStatus === 'FAILED' ||
    workflowDetail.value?.currentStep?.includes('失败') ||
    uploadRecords.value.some((item) => item.uploadStatus === 'FAILED' || item.aggregationStatus === 'FAILED')
)

const serverProcessPhase = computed(() => resolveServerProcessPhase(workflowDetail.value, uploadProgress.value))
const decryptDialogCanClose = computed(() => isServerProcessDialogClosable(serverProcessPhase.value))

const canBindDataset = computed(() => {
  if (!workflowDetail.value) return false
  return (
    federatedReady.value &&
    ['ACCEPTED', 'PREPARING', 'TRAINING_RUNNING', 'VALIDATING', 'COMPLETED'].includes(workflowDetail.value.status) &&
    !workflowDetail.value.pythonJobId
  )
})

const canStartValidation = computed(() => {
  if (!workflowDetail.value) return false
  return (
    federatedReady.value &&
    !!workflowDetail.value.serverDatasetAssetId &&
    ['ACCEPTED', 'PREPARING'].includes(workflowDetail.value.status) &&
    !workflowDetail.value.pythonJobId
  )
})

const validationReadinessHint = computed(() => {
  const workflow = workflowDetail.value
  if (!workflow) return ''
  if (!['ACCEPTED', 'PREPARING'].includes(workflow.status)) {
    return '当前工作流状态暂不允许启动验证；请先完成服务端接收、模型纳管和联邦聚合。'
  }
  if (workflow.pythonJobId) return 'Python 验证任务已启动，请在验证页查看进度和结果。'
  if (!workflow.serverDatasetAssetId) return '请先绑定已校验通过的服务端数据集。'
  if (!federatedReady.value) {
    return workflow.federatedModelUnavailableReason || '联邦全局模型不可用，无法启动验证。'
  }
  return ''
})

const resultRetentionStatus = computed(() => workflowDetail.value?.resultRetentionStatus || 'TEMPORARY')
const resultRetentionLabel = computed(() => {
  if (resultRetentionStatus.value === 'SAVED') return '当前结果：已保存'
  if (resultRetentionStatus.value === 'DELETED') return '当前结果：已删除'
  return '当前结果：临时'
})
const resultRetentionTagType = computed(() => {
  if (resultRetentionStatus.value === 'SAVED') return 'success'
  if (resultRetentionStatus.value === 'DELETED') return 'info'
  return 'warning'
})
const canSaveWorkflowResult = computed(() => {
  if (!workflowDetail.value) return false
  return Boolean(workflowDetail.value.resultFilePath) && resultRetentionStatus.value === 'TEMPORARY'
})
const canDeleteSavedWorkflowResult = computed(() => {
  if (!workflowDetail.value) return false
  return Boolean(workflowDetail.value.resultFilePath) && resultRetentionStatus.value === 'SAVED'
})

const federatedStatusLabel = computed(() => {
  switch (workflowDetail.value?.federatedStatus) {
    case 'STARTING':
      return '权重检查完成，等待联邦聚合'
    case 'RUNNING':
      return '联邦学习聚合中'
    case 'COMPLETED':
      return '联邦学习完成'
    case 'FAILED':
      return '联邦学习失败'
    case 'PENDING':
      return '待联邦学习'
    default:
      return '-'
  }
})

const serverPhaseKey = computed(() => {
  if (serverProcessPhase.value !== 'failed') {
    return serverProcessPhase.value === 'completed' ? 'validation' : serverProcessPhase.value
  }
  if (workflowDetail.value?.federatedStatus === 'FAILED') return 'federated'
  if (managedModelCount.value > 0) return 'registering'
  if (receivedModelCount.value > 0) return 'decrypting'
  return 'accepted'
})

const serverPhases = computed(() =>
  SERVER_VISUAL_STAGES.map((phase, index) => {
    const currentIndex = Math.max(0, SERVER_VISUAL_STAGES.findIndex((item) => item.key === serverPhaseKey.value))
    let status: PhaseState = 'pending'
    if (workflowDetail.value?.status === 'COMPLETED') {
      status = 'done'
    } else if (hasProcessFailure.value) {
      if (index < currentIndex) {
        status = 'done'
      } else if (index === currentIndex) {
        status = 'error'
      }
    } else if (index < currentIndex) {
      status = 'done'
    } else if (index === currentIndex) {
      status = 'current'
    }
    return {
      ...phase,
      status,
      statusText:
        status === 'done'
          ? '已完成'
          : status === 'current'
            ? '进行中'
            : status === 'error'
              ? '失败'
              : '待开始'
    }
  })
)

const serverPhasePercent = computed(() => {
  return computeServerProcessPercent(serverPhaseKey.value)
})

const serverSummaryType = computed(() => {
  if (hasProcessFailure.value) return 'error'
  if (workflowDetail.value?.federatedStatus === 'COMPLETED' && !federatedReady.value) return 'warning'
  if (workflowDetail.value?.status === 'COMPLETED') return 'success'
  if (workflowDetail.value?.pythonJobId) return 'info'
  if (federatedReady.value) return 'success'
  return workflowDetail.value?.status === 'CREATED' ? 'info' : 'warning'
})

const serverSummaryTitle = computed(() => {
  if (hasProcessFailure.value) return '服务端处理出现异常'
  if (workflowDetail.value?.federatedStatus === 'COMPLETED' && !federatedReady.value) return '联邦全局模型不可用'
  if (serverPhaseKey.value === 'created') return '等待服务端接收工作流'
  return getServerProcessTitle(serverPhaseKey.value)
})

const serverSummaryDescription = computed(() => {
  if (hasProcessFailure.value) {
    return summarizeUiErrorMessage(
      workflowDetail.value?.errorMessage || uploadRecords.value.find((item) => item.errorMessage)?.errorMessage,
      '服务端处理失败，请查看服务端日志。'
    )
  }
  if (workflowDetail.value?.federatedStatus === 'COMPLETED' && !federatedReady.value) {
    return workflowDetail.value.federatedModelUnavailableReason || '联邦全局模型不可用，无法启动验证。'
  }
  if (serverPhaseKey.value === 'created') return '进入进度页只会展示状态，不会自动弹出处理弹窗；只有点击“接受工作流”后，才会显示服务端处理弹窗。'
  return getServerProcessDescription(serverPhaseKey.value)
})

const decryptDialogDescription = computed(() => {
  if (hasProcessFailure.value) return serverSummaryDescription.value
  if (decryptDialogCanClose.value) return WORKFLOW_PROGRESS_TEXT.decryptDialogWaitingClose
  return getServerProcessDescription(serverProcessPhase.value)
})

function getWorkflowStatusTagType(status?: string): TagProps['type'] {
  switch (status as WorkflowStatus | undefined) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'WITHDRAWN':
      return 'info'
    case 'CREATED':
    case 'ACCEPTED':
    case 'PREPARING':
    case 'TRAINING_RUNNING':
    case 'VALIDATING':
      return 'warning'
    default:
      return 'info'
  }
}

function getUploadStatusTagType(status?: string): TagProps['type'] {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'PENDING':
    case 'UPLOADING':
    case 'ENCRYPTED_STORED':
    case 'DECRYPTING':
      return 'warning'
    default:
      return 'info'
  }
}

function getAggregationStatusLabel(status?: string) {
  switch (status) {
    case 'PENDING':
      return '待聚合'
    case 'READY':
      return '可聚合'
    case 'RUNNING':
      return '聚合中'
    case 'COMPLETED':
      return '已聚合'
    case 'FAILED':
      return '聚合失败'
    default:
      return '-'
  }
}

function getAggregationStatusTagType(status?: string): TagProps['type'] {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'RUNNING':
      return 'warning'
    case 'READY':
      return 'primary'
    default:
      return 'info'
  }
}

function formatDateTime(value?: string) {
  if (!value) return '-'
  return value.replace('T', ' ')
}

async function loadWorkflowList() {
  listLoading.value = true
  try {
    workflowList.value = sortWorkflowsByLatest(await listAllWorkflowsApi())
    console.info('[server-workflow-progress] latest workflow list loaded', {
      workflowIds: workflowList.value.map((item) => item.id),
      total: workflowList.value.length
    })
  } catch (error: any) {
    ElMessage.error(error?.message || '加载工作流列表失败。')
    workflowList.value = []
  } finally {
    listLoading.value = false
  }
}

async function loadDatasetOptions() {
  try {
    datasetOptions.value = await listAllDatasetAssetsApi()
  } catch (error: any) {
    ElMessage.error(error?.message || '加载服务端数据集失败。')
    datasetOptions.value = []
  }
}

async function loadWorkflowDetail(targetWorkflowId: number) {
  detailLoading.value = true
  try {
    const [detailRes, uploadRes] = await Promise.all([
      getWorkflowDetailApi(targetWorkflowId),
      getWorkflowUploadProgress(targetWorkflowId).catch(() => null),
      loadDatasetOptions()
    ])
    workflowDetail.value = detailRes.data
    uploadProgress.value = uploadRes?.data ?? null
    selectedDatasetId.value = detailRes.data?.serverDatasetAssetId || null
    console.info('[server-workflow-progress] workflow detail synced from backend', {
      workflowId: targetWorkflowId,
      workflowStatus: workflowDetail.value?.status,
      currentStep: workflowDetail.value?.currentStep,
      receivedModelCount: receivedModelCount.value,
      managedModelCount: managedModelCount.value,
      federatedStatus: workflowDetail.value?.federatedStatus || null
    })
  } catch (error: any) {
    ElMessage.error(error?.message || '加载工作流进度详情失败。')
    workflowDetail.value = null
    uploadProgress.value = null
  } finally {
    detailLoading.value = false
  }
}

async function selectWorkflow(targetWorkflowId: number, syncRoute = false) {
  if (!targetWorkflowId) return
  if (decryptDialogTriggeredWorkflowId.value && decryptDialogTriggeredWorkflowId.value !== targetWorkflowId) {
    decryptDialogTriggeredWorkflowId.value = null
    setDecryptDialogVisible(false, 'switch-workflow')
  }
  activeWorkflowId.value = targetWorkflowId
  if (syncRoute) {
    await router.replace({
      name: 'server-progress',
      query: { id: String(targetWorkflowId) }
    })
  }
  await loadWorkflowDetail(targetWorkflowId)
}

function handleDecryptDialogClose(done: () => void) {
  if (!decryptDialogCanClose.value) {
    ElMessage.warning('当前仍在执行服务端处理链路，请等待进度达到可关闭阶段后手动关闭。')
    return
  }
  decryptDialogTriggeredWorkflowId.value = null
  console.info('[server-workflow-progress] decrypt dialog closed manually', {
    workflowId: resolvedWorkflowId.value,
    phase: serverProcessPhase.value,
    percent: serverPhasePercent.value,
    reason: 'manual'
  })
  done()
}

function forceCloseDecryptDialog() {
  handleDecryptDialogClose(() => {
    setDecryptDialogVisible(false, 'manual-close')
  })
}

function setDecryptDialogVisible(nextVisible: boolean, reason: string) {
  if (decryptDialogVisible.value === nextVisible) {
    return
  }
  decryptDialogVisible.value = nextVisible
  console.info('[server-workflow-progress] decrypt dialog visibility changed', {
    workflowId: resolvedWorkflowId.value,
    phase: serverProcessPhase.value,
    visible: nextVisible,
    reason
  })
}

async function refreshCurrentWorkflow(targetWorkflowId = resolvedWorkflowId.value) {
  await loadWorkflowList()
  const nextWorkflowId =
    targetWorkflowId && workflowList.value.some((item) => item.id === targetWorkflowId)
      ? targetWorkflowId
      : workflowList.value[0]?.id || null

  if (!nextWorkflowId) {
    activeWorkflowId.value = null
    workflowDetail.value = null
    uploadProgress.value = null
    selectedDatasetId.value = null
    decryptDialogTriggeredWorkflowId.value = null
    setDecryptDialogVisible(false, 'no-workflow')
    stopPolling()
    return
  }

  await selectWorkflow(nextWorkflowId, true)
}

async function initializePage() {
  await loadWorkflowList()
  if (!workflowList.value.length) {
    activeWorkflowId.value = null
    workflowDetail.value = null
    uploadProgress.value = null
    stopPolling()
    return
  }

  if (routeWorkflowId.value && !workflowList.value.some((item) => item.id === routeWorkflowId.value)) {
    console.warn('[server-workflow-progress] route workflow id is not visible, fallback to latest workflow', {
      routeWorkflowId: routeWorkflowId.value,
      availableWorkflowIds: workflowList.value.map((item) => item.id)
    })
  }

  const targetWorkflowId =
    routeWorkflowId.value && workflowList.value.some((item) => item.id === routeWorkflowId.value)
      ? routeWorkflowId.value
      : workflowList.value[0]?.id || null
  if (!targetWorkflowId) return

  if (!routeWorkflowId.value || routeWorkflowId.value !== targetWorkflowId) {
    console.info(WORKFLOW_PROGRESS_TEXT.defaultSelectLogServer, {
      workflowId: targetWorkflowId,
      source: 'latest-workflow'
    })
  }
  await selectWorkflow(targetWorkflowId, true)
}

async function handleAcceptWorkflow() {
  const workflowId = resolvedWorkflowId.value
  if (!workflowDetail.value || !workflowId) return
  try {
    await ElMessageBox.confirm(
      `确认接收工作流“${workflowDetail.value.workflowName}”吗？系统会在接收后自动执行多模型接收、反洗牌解密、模型纳管和联邦学习聚合。`,
      '接收工作流',
      { type: 'warning' }
    )
    console.info('[server-workflow-progress] accept workflow clicked', {
      workflowId,
      currentStep: workflowDetail.value.currentStep,
      receivedModelCount: receivedModelCount.value,
      managedModelCount: managedModelCount.value
    })
    accepting.value = true
    decryptDialogTriggeredWorkflowId.value = workflowId
    setDecryptDialogVisible(true, 'manual-accept')
    await advanceWorkflowApi(workflowId)
    ElMessage.success(WORKFLOW_PROGRESS_TEXT.acceptSuccess)
    await refreshCurrentWorkflow(workflowId)
  } catch (error: any) {
    if (error === 'cancel' || error?.message === 'cancel') return
    decryptDialogTriggeredWorkflowId.value = null
    setDecryptDialogVisible(false, 'accept-failed')
    ElMessage.error(error?.message || WORKFLOW_PROGRESS_TEXT.actionFailed)
  } finally {
    accepting.value = false
  }
}

async function handleBindDataset() {
  const workflowId = resolvedWorkflowId.value
  if (!workflowDetail.value || !selectedDatasetId.value || !workflowId) return
  bindingDataset.value = true
  try {
    console.info('[server-workflow-progress] bind dataset clicked', {
      workflowId,
      selectedDatasetId: selectedDatasetId.value,
      federatedStatus: workflowDetail.value.federatedStatus
    })
    await bindServerDatasetApi(workflowId, {
      serverDatasetAssetId: selectedDatasetId.value
    })
    ElMessage.success(WORKFLOW_PROGRESS_TEXT.bindDatasetSuccess)
    await refreshCurrentWorkflow(workflowId)
  } catch (error: any) {
    ElMessage.error(error?.message || WORKFLOW_PROGRESS_TEXT.actionFailed)
  } finally {
    bindingDataset.value = false
  }
}

async function handleStartValidation() {
  const workflowId = resolvedWorkflowId.value
  if (!workflowDetail.value || !workflowId) return
  if (!canStartValidation.value) {
    ElMessage.warning(validationReadinessHint.value || '当前工作流不满足验证启动条件。')
    return
  }
  startingValidation.value = true
  try {
    console.info('[server-workflow-progress] start validation clicked', {
      workflowId,
      serverDatasetAssetId: workflowDetail.value.serverDatasetAssetId || null,
      federatedModelAssetId: workflowDetail.value.federatedModelAssetId || null
    })
    await startPythonJobApi(workflowId)
    ElMessage.success(WORKFLOW_PROGRESS_TEXT.startValidationSuccess)
    await refreshCurrentWorkflow(workflowId)
  } catch (error: any) {
    ElMessage.error(error?.message || WORKFLOW_PROGRESS_TEXT.actionFailed)
  } finally {
    startingValidation.value = false
  }
}

async function handleSaveWorkflowResult() {
  const workflowId = resolvedWorkflowId.value
  if (!workflowId) return
  savingResult.value = true
  try {
    await saveWorkflowResultApi(workflowId)
    ElMessage.success('工作流结果已保存，后续工作流不会自动替换它。')
    await refreshCurrentWorkflow(workflowId)
  } catch (error: any) {
    ElMessage.error(error?.message || WORKFLOW_PROGRESS_TEXT.actionFailed)
  } finally {
    savingResult.value = false
  }
}

async function handleDeleteSavedWorkflowResult() {
  const workflowId = resolvedWorkflowId.value
  if (!workflowId) return
  try {
    await ElMessageBox.confirm(
      '确认删除这个已保存的工作流结果吗？只会删除结果文件和识别样例，不会删除工作流、模型或数据集。',
      '删除已保存结果',
      { type: 'warning' }
    )
  } catch {
    return
  }
  deletingResult.value = true
  try {
    await deleteSavedWorkflowResultApi(workflowId)
    ElMessage.success('已删除保存的工作流结果。')
    await refreshCurrentWorkflow(workflowId)
  } catch (error: any) {
    ElMessage.error(error?.message || WORKFLOW_PROGRESS_TEXT.actionFailed)
  } finally {
    deletingResult.value = false
  }
}

function openValidationPage() {
  if (!workflowDetail.value || !resolvedWorkflowId.value) return
  router.push({
    name: 'server-validation',
    query: { workflowId: String(resolvedWorkflowId.value) }
  })
}

function shouldPoll() {
  const status = workflowDetail.value?.status
  if (!status) return false
  return ['ACCEPTED', 'PREPARING', 'TRAINING_RUNNING', 'VALIDATING'].includes(status)
}

function stopPolling() {
  if (pollingTimer !== null) {
    window.clearInterval(pollingTimer)
    pollingTimer = null
  }
}

function startPollingIfNeeded() {
  stopPolling()
  if (!activeWorkflowId.value || !shouldPoll()) return
  pollingTimer = window.setInterval(async () => {
    console.info(WORKFLOW_PROGRESS_TEXT.decryptPollingLog, {
      workflowId: activeWorkflowId.value,
      currentStep: workflowDetail.value?.currentStep,
      uploadStatuses: uploadRecords.value.map((item) => item.uploadStatus),
      federatedStatus: workflowDetail.value?.federatedStatus || null
    })
    await loadWorkflowDetail(activeWorkflowId.value!)
  }, 4000)
}

watch(
  () => routeWorkflowId.value,
  async (value, previous) => {
    if (value && value !== previous) {
      if (workflowList.value.some((item) => item.id === value)) {
        await selectWorkflow(value)
        return
      }
      await initializePage()
    }
  }
)

watch(
  () => serverProcessPhase.value,
  (phase, previousPhase) => {
    console.info('[server-workflow-progress] process phase changed', {
      workflowId: resolvedWorkflowId.value,
      phase,
      currentStep: workflowDetail.value?.currentStep,
      latestUploadStatus: uploadProgress.value?.latestUploadStatus || null,
      percent: serverPhasePercent.value,
      dialogTriggeredWorkflowId: decryptDialogTriggeredWorkflowId.value
    })
    if (computeServerProcessPercent(phase) === 100 && previousPhase !== phase) {
      console.info('[server-workflow-progress] server processing reached terminal state', {
        workflowId: resolvedWorkflowId.value,
        phase,
        canClose: decryptDialogCanClose.value
      })
    }
  },
  { immediate: true }
)

watch(
  () => [workflowDetail.value?.status, workflowDetail.value?.currentStep, workflowDetail.value?.federatedStatus, uploadProgress.value?.latestUploadStatus, uploadProgress.value?.collectedModelCount],
  () => {
    startPollingIfNeeded()
  }
)

watch(
  () => resolvedWorkflowId.value,
  (workflowId, previousWorkflowId) => {
    if (previousWorkflowId && workflowId !== previousWorkflowId && decryptDialogTriggeredWorkflowId.value === previousWorkflowId) {
      decryptDialogTriggeredWorkflowId.value = null
      setDecryptDialogVisible(false, 'workflow-changed')
    }
  }
)

onMounted(() => {
  initializePage()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped>
.page {
  padding: 16px;
}

.sidebar-card,
.main-card,
.section-card,
.info-card {
  border-radius: 8px;
}

.card-header,
.section-title-row,
.hero-card,
.workflow-item-head,
.workflow-item-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-title,
.section-title,
.hero-title {
  font-size: 18px;
  font-weight: 700;
  color: #17324d;
}

.card-desc,
.section-desc,
.hero-code,
.workflow-item-code,
.workflow-item-meta {
  color: #6b7b8c;
  font-size: 12px;
  line-height: 1.6;
}

.sidebar-content {
  min-height: 320px;
}

.workflow-item {
  padding: 14px 16px;
  border-radius: 8px;
  border: 1px solid #dbe5ef;
  background: #f8fbfd;
  cursor: pointer;
  transition: all 0.2s ease;
}

.workflow-item + .workflow-item {
  margin-top: 10px;
}

.workflow-item:hover {
  border-color: #7eb6ff;
  transform: translateY(-1px);
}

.workflow-item.active {
  border-color: #2f7cf6;
  background: #ecf4ff;
  box-shadow: 0 8px 22px rgba(47, 124, 246, 0.14);
}

.workflow-item-title {
  font-size: 15px;
  font-weight: 600;
  color: #17324d;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-card {
  margin-bottom: 18px;
  padding: 18px 20px;
  border-radius: 8px;
  background: linear-gradient(135deg, #f4fbff 0%, #eef5ff 100%);
  border: 1px solid #d8e7ff;
}

.info-card,
.section-card {
  margin-bottom: 18px;
}

.phase-progress {
  width: 220px;
  max-width: 100%;
}

.phase-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.phase-card {
  padding: 16px;
  border-radius: 8px;
  border: 1px solid #dbe5ef;
  background: #f7fafc;
}

.phase-card.done {
  border-color: #8fd0a6;
  background: #f1fbf4;
}

.phase-card.current {
  border-color: #76a9fa;
  background: #edf5ff;
  box-shadow: 0 10px 24px rgba(47, 124, 246, 0.12);
}

.phase-card.error {
  border-color: #f5a2a2;
  background: #fff3f3;
}

.phase-state {
  display: inline-flex;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  background: #e7eef5;
  color: #49617b;
}

.phase-card.done .phase-state {
  background: #dff5e4;
  color: #2f7a43;
}

.phase-card.current .phase-state {
  background: #dbeafe;
  color: #1d4ed8;
}

.phase-card.error .phase-state {
  background: #fde2e2;
  color: #b42318;
}

.phase-name {
  margin-top: 12px;
  font-size: 15px;
  font-weight: 700;
  color: #17324d;
}

.phase-desc {
  margin-top: 8px;
  color: #5e7082;
  line-height: 1.7;
  font-size: 13px;
}

.summary-alert {
  margin-top: 16px;
}

.progress-dialog-body {
  display: flex;
  flex-direction: column;
}

.dialog-info-card {
  margin-bottom: 4px;
}

.dialog-tip-text {
  margin-top: 14px;
  color: #5e7082;
  line-height: 1.7;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.dialog-error-text {
  color: #c23030;
}

.action-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.action-grid :deep(.el-button + .el-button) {
  margin-left: 0;
}

.action-hint {
  margin-top: 12px;
}

.dataset-bind-box {
  display: flex;
  flex: 1;
  min-width: 320px;
  gap: 12px;
  flex-wrap: wrap;
}

.dataset-bind-box :deep(.el-select) {
  flex: 1;
  min-width: 220px;
}

.status-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.status-error {
  color: #c23030;
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.model-availability {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.availability-reason {
  flex-basis: 100%;
  color: #b42318;
  line-height: 1.5;
}

.step-title {
  font-size: 15px;
  font-weight: 700;
  color: #17324d;
  margin-bottom: 8px;
}

.step-text {
  color: #5e7082;
  line-height: 1.8;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.info-card :deep(.el-descriptions__content),
.dialog-info-card :deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}
</style>
