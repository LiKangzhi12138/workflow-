<template>
  <div class="page">
    <el-row :gutter="16">
      <el-col :xs="24" :lg="7">
        <el-card shadow="never" class="sidebar-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">{{ WORKFLOW_PROGRESS_TEXT.latestWorkflows }}</div>
                <div class="card-desc">{{ WORKFLOW_PROGRESS_TEXT.latestWorkflowsDescClient }}</div>
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
            <el-empty v-else :description="WORKFLOW_PROGRESS_TEXT.emptyClient" />
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="17">
        <el-card shadow="never" class="main-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">{{ WORKFLOW_PROGRESS_TEXT.clientPageTitle }}</div>
                <div class="card-desc">{{ WORKFLOW_PROGRESS_TEXT.clientVisualDesc }}</div>
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
                <div class="hero-status">
                  <el-tag :type="getWorkflowStatusTagType(workflowDetail.status)" effect="dark">
                    {{ getWorkflowStatusLabel(workflowDetail.status) }}
                  </el-tag>
                </div>
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
                <el-descriptions-item label="联邦聚合">
                  {{ workflowDetail.federatedStatus === 'COMPLETED' ? '已完成' : workflowDetail.federatedStatus || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="全局模型">
                  <div class="model-availability">
                    <span>{{ workflowDetail.federatedModelAssetName || '尚未生成' }}</span>
                    <el-tag :type="workflowDetail.federatedModelAvailable ? 'success' : 'danger'" size="small">
                      {{ workflowDetail.federatedModelAvailable ? '可用' : '不可用' }}
                    </el-tag>
                    <span v-if="!workflowDetail.federatedModelAvailable" class="availability-reason">
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
                <el-descriptions-item label="当前结果">
                  <el-tag :type="resultRetentionTagType">
                    {{ resultRetentionLabel }}
                  </el-tag>
                </el-descriptions-item>
              </el-descriptions>

              <div class="result-action-bar">
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

              <el-card shadow="never" class="section-card">
                <template #header>
                  <div class="section-title-row">
                    <div>
                      <div class="section-title">客户端模型上传与洗牌加密</div>
                      <div class="section-desc">选择模型文件并上传至服务端，文件会自动进行洗牌加密处理</div>
                    </div>
                  </div>
                </template>

                <el-alert
                  :title="uploadPanelSummaryTitle"
                  :description="uploadPanelSummaryDescription"
                  :type="uploadPanelSummaryType"
                  :closable="false"
                  show-icon
                  style="margin-bottom: 16px"
                />

                <el-upload
                  ref="uploadRef"
                  drag
                  multiple
                  :auto-upload="false"
                  :accept="uploadContract.uploadProtocol === 'WEIGHTS_V1' ? '.pt' : '.pt,.weights,.onnx'"
                  :show-file-list="false"
                  :on-change="handleUploadFileChange"
                  :on-remove="handleUploadFileRemove"
                  class="upload-dropzone"
                >
                  <div class="upload-dropzone-title">
                    {{ uploadContract.uploadProtocol === 'WEIGHTS_V1' ? '选择 weights-only 文件' : '点击或拖拽选择模型文件' }}
                  </div>
                  <div class="upload-dropzone-desc">
                    {{ uploadContract.uploadProtocol === 'WEIGHTS_V1'
                      ? '仅接受 packaging tool 生成的 weights.pt'
                      : '支持选择 .pt / .weights / .onnx 格式模型文件，可点击或拖拽上传' }}
                  </div>
                </el-upload>
                <div v-if="uploadContract.uploadProtocol === 'WEIGHTS_V1'" class="upload-file-meta" style="margin-bottom: 16px">
                  <div>仅接受 packaging tool 生成的 weights-only 文件；每次上传一个包。</div>
                  <label>Manifest <input type="file" accept=".json,application/json" @change="handleManifestFileChange" /></label>
                  <label>Descriptor <input type="file" accept=".json,application/json" @change="handleDescriptorFileChange" /></label>
                </div>

                <div class="upload-action-bar">
                  <div class="upload-file-meta">
                    <div>工作流 ID：{{ currentWorkflowIdText }}</div>
                    <div>已选文件数：{{ selectedUploadFiles.length }}</div>
                    <div>剩余可上传名额：{{ remainingUploadSlots }}</div>
                  </div>
                  <div class="upload-action-buttons">
                    <el-button text :disabled="uploadSubmitting || !selectedUploadFiles.length" @click="handleUploadFileRemove">
                      清除文件
                    </el-button>
                    <el-button
                      type="primary"
                      :loading="uploadSubmitting"
                      :disabled="!canStartClientUpload"
                      @click="startWorkflowUpload"
                    >
                      {{ uploadActionLabel }}
                    </el-button>
                  </div>
                </div>

                <el-table
                  v-if="selectedUploadFiles.length"
                  :data="selectedUploadFiles"
                  size="small"
                  border
                  style="margin-bottom: 16px"
                >
                  <el-table-column prop="name" label="文件名" min-width="220" />
                  <el-table-column label="文件大小" width="120">
                    <template #default="{ row }">
                      {{ formatFileSize(row.size) }}
                    </template>
                  </el-table-column>
                  <el-table-column label="上传状态" width="160">
                    <template #default="{ row }">
                      <el-tag :type="getLocalUploadStatusTagType(row.status)">
                        {{ getLocalUploadStatusLabel(row.status) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="说明" min-width="220">
                    <template #default="{ row }">
                      <span :class="{ 'status-error': row.error }">
                        {{ row.error || row.statusText }}
                      </span>
                    </template>
                  </el-table-column>
                </el-table>

                <template v-if="uploadUiStep > 0">
                  <el-progress
                    :percentage="uploadUiStagePercent"
                    :stroke-width="12"
                    style="margin-bottom: 16px"
                  />
                  <el-steps :active="uploadUiStep" finish-status="success" style="margin-bottom: 16px">
                    <el-step title="准备上传" />
                    <el-step title="申请上传令牌" />
                    <el-step title="正在进行洗牌加密" />
                    <el-step title="正在上传模型" />
                    <el-step title="上传完成" />
                  </el-steps>
                  <el-alert :closable="false" type="info" show-icon>
                    <template #default>
                      <div style="font-weight: 600">{{ uploadUiStageLabel }}</div>
                      <div style="margin-top: 4px">{{ uploadUiStageDescription }}</div>
                      <div v-if="fileSha256Preview" style="margin-top: 6px; color: #909399">
                        文件摘要：{{ fileSha256Preview }}
                      </div>
                    </template>
                  </el-alert>
                </template>

                <el-alert
                  v-if="uploadError"
                  :title="uploadError"
                  type="error"
                  :closable="false"
                  show-icon
                  style="margin-top: 16px"
                />
              </el-card>

              <el-card shadow="never" class="section-card">
                <template #header>
                  <div class="section-title-row">
                    <div>
                      <div class="section-title">{{ WORKFLOW_PROGRESS_TEXT.clientVisualTitle }}</div>
                      <div class="section-desc">{{ WORKFLOW_PROGRESS_TEXT.phaseProgressHint }}</div>
                    </div>
                    <el-progress
                      :percentage="clientPhasePercent"
                      :stroke-width="10"
                      class="phase-progress"
                    />
                  </div>
                </template>

                <div class="phase-grid">
                  <div
                    v-for="phase in clientPhases"
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
                  :title="clientSummaryTitle"
                  :description="clientSummaryDescription"
                  :type="clientSummaryType"
                  :closable="false"
                  show-icon
                  class="summary-alert"
                />
              </el-card>

              <el-card shadow="never" class="section-card">
                <template #header>
                  <div class="section-title-row">
                    <div>
                      <div class="section-title">{{ WORKFLOW_PROGRESS_TEXT.uploadRecords }}</div>
                      <div class="section-desc">
                        {{ WORKFLOW_PROGRESS_TEXT.waitServerAccept }}
                      </div>
                    </div>
                  </div>
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
                  <el-table-column label="上传状态" width="220">
                    <template #default="{ row }">
                      <div class="status-cell">
                        <el-tag :type="getUploadStatusTagType(row.uploadStatus)">
                          {{ getUploadStatusLabel(row.uploadStatus) }}
                        </el-tag>
                        <span v-if="row.errorMessage" class="status-error">
                          {{ row.errorMessage }}
                        </span>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="聚合状态" width="120">
                    <template #default="{ row }">
                      {{ row.aggregationStatus || '-' }}
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
              :description="workflowList.length ? WORKFLOW_PROGRESS_TEXT.noWorkflowSelected : WORKFLOW_PROGRESS_TEXT.emptyClient"
            />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog
      v-model="uploadDialogVisible"
      :title="WORKFLOW_PROGRESS_TEXT.uploadDialogTitle"
      width="720px"
      :destroy-on-close="false"
      :show-close="uploadDialogCanClose"
      :close-on-click-modal="uploadDialogCanClose"
      :before-close="handleUploadDialogClose"
    >
      <div class="progress-dialog-body">
        <el-alert
          :title="uploadUiStageLabel"
          :description="uploadUiStep === 5 ? WORKFLOW_PROGRESS_TEXT.uploadDialogWaitingClose : uploadUiStageDescription"
          :type="uploadError ? 'error' : uploadUiStep === 5 ? 'success' : 'info'"
          :closable="false"
          show-icon
          style="margin-bottom: 16px"
        />

        <el-descriptions :column="1" border size="small" class="dialog-info-card">
          <el-descriptions-item label="工作流 ID">{{ currentWorkflowIdText }}</el-descriptions-item>
          <el-descriptions-item label="当前文件">{{ currentUploadingFileName }}</el-descriptions-item>
          <el-descriptions-item label="当前阶段">{{ uploadUiStageLabel }}</el-descriptions-item>
          <el-descriptions-item label="批量进度">
            {{ completedLocalUploadCount }} / {{ selectedUploadFiles.length || 0 }}
          </el-descriptions-item>
        </el-descriptions>

        <el-progress
          :percentage="uploadUiStagePercent"
          :stroke-width="14"
          style="margin: 18px 0"
        />

        <el-steps :active="uploadUiStep" finish-status="success">
          <el-step title="准备上传" />
          <el-step title="申请上传令牌" />
          <el-step title="正在进行洗牌加密" />
          <el-step title="正在上传模型" />
          <el-step title="上传完成" />
        </el-steps>

        <div v-if="fileSha256Preview" class="dialog-tip-text">
          文件摘要：{{ fileSha256Preview }}
        </div>

        <div v-if="uploadError" class="dialog-tip-text dialog-error-text">
          失败原因：{{ uploadError }}
        </div>
      </div>

      <template #footer>
        <el-button
          type="primary"
          :disabled="!uploadDialogCanClose"
          @click="forceCloseUploadDialog"
        >
          {{ WORKFLOW_PROGRESS_TEXT.uploadDialogClose }}
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
import { getWorkflowModelDisplayName } from '@/utils/workflowModelDisplay'
import {
  deleteSavedWorkflowResultApi,
  getWorkflowDetailApi,
  getWorkflowUploadProgress,
  getWorkflowUploadContract,
  initModelUpload,
  listAllWorkflowsApi,
  saveWorkflowResultApi,
  uploadEncryptedModelFile,
  type UploadProgressVO,
  type WorkflowDetail,
  type WorkflowListItem,
  type WorkflowStatus,
  type WorkflowUploadContract
} from '@/api/workflow'
import {
  CLIENT_VISUAL_STAGES,
  WORKFLOW_PROGRESS_TEXT,
  computeServerProcessPercent,
  computeUploadDialogPercent,
  formatMechanismEnabled,
  getUploadDialogStageDescription,
  getUploadDialogStageLabel,
  getPrivacyStatusLabel,
  getUploadStatusLabel,
  getServerProcessDescription,
  getServerProcessTitle,
  getWorkflowStatusLabel,
  resolveServerProcessPhase,
  sortWorkflowsByLatest
} from '@/constants/workflowProgress'
import { summarizeUiErrorMessage } from '@/utils/errorMessage'
import {
  appendWeightsProtocolMetadata,
  protocolUploadReady,
  weightsUploadErrorMessage
} from '@/utils/weightsPackageUpload'

type PhaseState = 'done' | 'current' | 'pending' | 'error'
type LocalUploadStatus = 'pending' | 'encrypting' | 'initializing' | 'uploading' | 'completed' | 'failed'

interface LocalUploadFileItem {
  uid: string
  name: string
  size: number
  raw: File
  status: LocalUploadStatus
  statusText: string
  error?: string
}

function resolveUploadClientErrorMessage(stage: string, error: unknown) {
  const rawMessage =
    error instanceof Error
      ? error.message
      : typeof error === 'string'
        ? error
        : ''

  if (/digest|subtle|importKey|encrypt/i.test(rawMessage)) {
    return '当前浏览器不支持必要的加密功能，请更新浏览器或使用其他浏览器重试。'
  }

  return summarizeUiErrorMessage(rawMessage, `模型${stage}失败，请稍后重试。`)
}

const route = useRoute()
const router = useRouter()

const listLoading = ref(false)
const detailLoading = ref(false)
const workflowList = ref<WorkflowListItem[]>([])
const activeWorkflowId = ref<number | null>(null)
const workflowDetail = ref<WorkflowDetail | null>(null)
const uploadProgress = ref<UploadProgressVO | null>(null)
const savingResult = ref(false)
const deletingResult = ref(false)
const uploadRef = ref<any>(null)
const selectedUploadFiles = ref<LocalUploadFileItem[]>([])
const currentUploadingFileUid = ref<string | null>(null)
const uploadSubmitting = ref(false)
const uploadUiStep = ref(0)
const uploadTransferProgress = ref(0)
const uploadError = ref('')
const fileSha256Preview = ref('')
const uploadDialogVisible = ref(false)
const uploadContract = ref<WorkflowUploadContract>({
  workflowId: 0,
  uploadProtocol: 'LEGACY_CHECKPOINT',
  manifestRequired: false,
  descriptorRequired: false,
  acceptedArtifactType: 'FULL_CHECKPOINT'
})
const uploadContractLoaded = ref(false)
const uploadManifestFile = ref<File | null>(null)
const uploadDescriptorFile = ref<File | null>(null)
let pollingTimer: number | null = null

const routeWorkflowId = computed(() => {
  const id = route.query.id || route.params.id
  const value = Number(id)
  return Number.isFinite(value) && value > 0 ? value : null
})

const selectedWorkflowItem = computed(() => {
  if (!activeWorkflowId.value) return null
  return workflowList.value.find((item) => item.id === activeWorkflowId.value) || null
})

const resolvedWorkflowId = computed(() => workflowDetail.value?.id || activeWorkflowId.value || null)

const resolvedClientModelAssetId = computed(
  () => workflowDetail.value?.clientModelAssetId || selectedWorkflowItem.value?.clientModelAssetId || null
)

const requiredModelCount = computed(() => {
  const count = Number(
    workflowDetail.value?.expectedModelCount ||
      workflowDetail.value?.clientModelCount ||
      uploadProgress.value?.requiredModelCount ||
      1
  )
  return count > 0 ? count : 1
})

const uploadRecords = computed(() => uploadProgress.value?.uploadRecords || [])
const currentWorkflowIdText = computed(() => String(resolvedWorkflowId.value || '-'))
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
const currentUploadingFile = computed(
  () => selectedUploadFiles.value.find((item) => item.uid === currentUploadingFileUid.value) || null
)
const currentUploadingFileName = computed(() => currentUploadingFile.value?.name || '-')
const completedLocalUploadCount = computed(
  () => selectedUploadFiles.value.filter((item) => item.status === 'completed').length
)
const hasExistingUpload = computed(() => {
  const progress = uploadProgress.value
  if (!progress) return false
  return Boolean(
    progress.activeUploadCount ||
      progress.receivedModelCount ||
      progress.collectedModelCount ||
      progress.latestUploadStatus
  )
})
const shouldReplaceExistingUpload = computed(
  () => requiredModelCount.value === 1 && hasExistingUpload.value
)
const remainingUploadSlots = computed(() => {
  if (requiredModelCount.value <= 1) {
    return shouldReplaceExistingUpload.value ? 1 : Math.max(0, requiredModelCount.value - uploadRecords.value.length)
  }
  const occupied = uploadProgress.value?.activeUploadCount ?? uploadRecords.value.length
  return Math.max(0, requiredModelCount.value - occupied)
})
const canStartClientUpload = computed(() => {
  if (!uploadContractLoaded.value) {
    return false
  }
  if (!workflowDetail.value || !resolvedWorkflowId.value || selectedUploadFiles.value.length === 0) {
    return false
  }
  if (!['CREATED', 'ACCEPTED'].includes(workflowDetail.value.status)) {
    return false
  }
  if (!resolvedClientModelAssetId.value) {
    return false
  }
  if (!selectedUploadFiles.value.every((item) => protocolUploadReady(
    uploadContract.value.uploadProtocol,
    item.raw,
    { manifest: uploadManifestFile.value, descriptor: uploadDescriptorFile.value }
  ))) {
    return false
  }
  if (requiredModelCount.value <= 1) {
    return selectedUploadFiles.value.length === 1
  }
  return remainingUploadSlots.value > 0
})
const uploadDialogCanClose = computed(() => !uploadSubmitting.value)
const uploadActionLabel = computed(() =>
  shouldReplaceExistingUpload.value ? '替换上传' : '开始上传'
)
const uploadUiStagePercent = computed(() =>
  computeUploadDialogPercent(uploadUiStep.value, uploadTransferProgress.value)
)
const uploadUiStageLabel = computed(() => getUploadDialogStageLabel(uploadUiStep.value))
const uploadUiStageDescription = computed(() => getUploadDialogStageDescription(uploadUiStep.value))
const federatedModelUnavailable = computed(
  () => workflowDetail.value?.federatedStatus === 'COMPLETED' && workflowDetail.value?.federatedModelAvailable !== true
)
const uploadPanelSummaryType = computed(() => {
  if (uploadError.value) return 'error'
  if (federatedModelUnavailable.value) return 'warning'
  if (['ready', 'dataset', 'validation', 'completed'].includes(serverProcessPhase.value)) return 'success'
  return 'info'
})
const serverProcessPhase = computed(() => resolveServerProcessPhase(workflowDetail.value, uploadProgress.value))
const serverProcessingPhases = ['accepted', 'downloading', 'decrypting', 'registering', 'secure-shuffle', 'dp', 'secure-aggregation', 'federated']
const uploadPanelSummaryTitle = computed(() => {
  if (uploadError.value) return '上传链路出现异常'
  if (federatedModelUnavailable.value) return '联邦聚合已完成，但全局模型当前不可用'
  if (serverProcessPhase.value === 'waiting-federated') return getServerProcessTitle('waiting-federated')
  if (['ready', 'dataset', 'validation', 'completed'].includes(serverProcessPhase.value)) return '当前工作流已完成联邦聚合，可继续进入验证流程'
  if (serverProcessingPhases.includes(serverProcessPhase.value)) {
    return '服务端正在处理已上传模型'
  }
  return '当前工作流可继续上传模型'
})
const uploadPanelSummaryDescription = computed(() => {
  if (uploadError.value) return uploadError.value
  if (federatedModelUnavailable.value) {
    return workflowDetail.value?.federatedModelUnavailableReason || '联邦全局模型不可用，无法再次启动验证。'
  }
  if (serverProcessPhase.value === 'waiting-federated') {
    return getServerProcessDescription('waiting-federated')
  }
  if ([...serverProcessingPhases, 'ready', 'dataset', 'validation', 'completed'].includes(serverProcessPhase.value)) {
    return getServerProcessDescription(serverProcessPhase.value)
  }
  return `当前可以上传 ${requiredModelCount.value} 个模型文件，完成后将自动进入洗牌加密与上传阶段`
})

const clientPhaseKey = computed(() => {
  const detail = workflowDetail.value
  const records = uploadRecords.value
  const statuses = records.map((item) => item.uploadStatus)
  const localStatuses = selectedUploadFiles.value.map((item) => item.status)
  if (detail?.status === 'FAILED' || detail?.federatedStatus === 'FAILED' || statuses.includes('FAILED') || localStatuses.includes('failed')) {
    return 'server-processing'
  }
  if (localStatuses.includes('uploading')) {
    return 'uploading'
  }
  if (localStatuses.includes('encrypting') || localStatuses.includes('initializing')) {
    return 'encrypting'
  }
  if (['ready', 'dataset', 'validation', 'completed'].includes(serverProcessPhase.value)) {
    return 'ready'
  }
  if (serverProcessPhase.value === 'waiting-federated') {
    return 'waiting-federated'
  }
  if (['secure-shuffle', 'dp', 'secure-aggregation', 'federated'].includes(serverProcessPhase.value)) {
    return 'federated'
  }
  if (['accepted', 'downloading', 'decrypting', 'registering'].includes(serverProcessPhase.value)) {
    return 'server-processing'
  }
  if (statuses.includes('ENCRYPTED_STORED')) {
    return 'waiting-server'
  }
  return 'created'
})

const clientPhases = computed(() =>
  CLIENT_VISUAL_STAGES.map((phase, index) => {
    const currentIndex = CLIENT_VISUAL_STAGES.findIndex((item) => item.key === clientPhaseKey.value)
    const isFailed = workflowDetail.value?.status === 'FAILED' || workflowDetail.value?.federatedStatus === 'FAILED'
    let status: PhaseState = 'pending'
    if (serverProcessPhase.value === 'completed') {
      status = 'done'
    } else if (isFailed && ['waiting-server', 'server-processing', 'federated', 'ready'].includes(phase.key)) {
      status = 'error'
    } else if (index < currentIndex) {
      status = 'done'
    } else if (index === currentIndex) {
      status = isFailed ? 'error' : 'current'
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

const clientPhasePercent = computed(() => {
  const phaseIndex = CLIENT_VISUAL_STAGES.findIndex((item) => item.key === clientPhaseKey.value)
  const stageCount = CLIENT_VISUAL_STAGES.length
  const base = phaseIndex <= 0 ? 10 : Math.round((phaseIndex / Math.max(1, stageCount - 1)) * 100)
  if (clientPhaseKey.value === 'uploading') {
    return Math.min(90, Math.max(55, Math.round(40 + uploadTransferProgress.value * 0.5)))
  }
  if (clientPhaseKey.value === 'ready') {
    return 100
  }
  if (clientPhaseKey.value === 'federated') {
    return Math.max(85, computeServerProcessPercent(serverProcessPhase.value))
  }
  if (clientPhaseKey.value === 'waiting-federated') {
    return Math.max(85, computeServerProcessPercent(serverProcessPhase.value))
  }
  if (clientPhaseKey.value === 'server-processing') {
    return Math.max(70, computeServerProcessPercent(serverProcessPhase.value))
  }
  return Math.min(100, base)
})

const clientSummaryType = computed(() => {
  if (workflowDetail.value?.status === 'FAILED' || workflowDetail.value?.federatedStatus === 'FAILED') return 'error'
  if (federatedModelUnavailable.value) return 'warning'
  if (serverProcessPhase.value === 'waiting-federated') return 'info'
  if (['ready', 'dataset', 'validation', 'completed'].includes(serverProcessPhase.value)) return 'success'
  if (serverProcessingPhases.includes(serverProcessPhase.value)) return 'warning'
  return 'info'
})

const clientSummaryTitle = computed(() => {
  if (workflowDetail.value?.status === 'FAILED' || workflowDetail.value?.federatedStatus === 'FAILED') return '当前流程出现异常'
  if (federatedModelUnavailable.value) return '联邦聚合已完成，但全局模型当前不可用'
  if (serverProcessPhase.value === 'waiting-federated') return getServerProcessTitle('waiting-federated')
  if ([...serverProcessingPhases, 'ready', 'dataset', 'validation', 'completed'].includes(serverProcessPhase.value)) {
    return getServerProcessTitle(serverProcessPhase.value)
  }
  return '当前已进入洗牌加密与上传阶段'
})

const clientSummaryDescription = computed(() => {
  if (workflowDetail.value?.status === 'FAILED' || workflowDetail.value?.federatedStatus === 'FAILED') {
    return summarizeUiErrorMessage(
      workflowDetail.value?.errorMessage,
      '当前工作流处理失败，请查看错误日志了解详情。'
    )
  }
  if (federatedModelUnavailable.value) {
    return workflowDetail.value?.federatedModelUnavailableReason || '联邦全局模型不可用，无法再次启动验证。'
  }
  if (serverProcessPhase.value === 'waiting-federated') {
    return getServerProcessDescription('waiting-federated')
  }
  if (uploadSubmitting.value && uploadUiStep.value > 0) {
    return uploadUiStageDescription.value
  }
  if ([...serverProcessingPhases, 'ready', 'dataset', 'validation', 'completed'].includes(serverProcessPhase.value)) {
    return getServerProcessDescription(serverProcessPhase.value)
  }
  return WORKFLOW_PROGRESS_TEXT.waitServerAccept
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
    case 'UPLOADING':
    case 'PENDING':
    case 'ENCRYPTED_STORED':
    case 'DECRYPTING':
      return 'warning'
    default:
      return 'info'
  }
}

function getLocalUploadStatusLabel(status: LocalUploadStatus) {
  switch (status) {
    case 'pending':
      return '等待上传'
    case 'encrypting':
    case 'initializing':
      return '正在洗牌加密'
    case 'uploading':
      return '正在上传'
    case 'completed':
      return '上传完成'
    case 'failed':
      return '上传失败'
    default:
      return '-'
  }
}

function getLocalUploadStatusTagType(status: LocalUploadStatus): TagProps['type'] {
  switch (status) {
    case 'completed':
      return 'success'
    case 'failed':
      return 'danger'
    case 'encrypting':
    case 'initializing':
    case 'uploading':
      return 'warning'
    default:
      return 'info'
  }
}

function formatFileSize(size?: number) {
  if (!size || size <= 0) return '-'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`
}

function formatDateTime(value?: string) {
  if (!value) return '-'
  return value.replace('T', ' ')
}

function resetUploadUi() {
  selectedUploadFiles.value = []
  currentUploadingFileUid.value = null
  uploadSubmitting.value = false
  uploadUiStep.value = 0
  uploadTransferProgress.value = 0
  uploadError.value = ''
  fileSha256Preview.value = ''
  uploadDialogVisible.value = false
  uploadRef.value?.clearFiles?.()
}

function handleUploadDialogClose(done: () => void) {
  if (!uploadDialogCanClose.value) {
    ElMessage.warning('请等待上传进度达到 100% 后再关闭')
    return
  }
  console.info('[client-workflow-progress] upload dialog closed manually', {
    workflowId: resolvedWorkflowId.value,
    finalStep: uploadUiStep.value,
    finalPercent: uploadUiStagePercent.value
  })
  done()
  resetUploadUi()
}

function forceCloseUploadDialog() {
  handleUploadDialogClose(() => {
    uploadDialogVisible.value = false
  })
}

function updateLocalUploadFileStatus(
  uid: string,
  status: LocalUploadStatus,
  statusText: string,
  error = ''
) {
  selectedUploadFiles.value = selectedUploadFiles.value.map((item) =>
    item.uid === uid
      ? {
          ...item,
          status,
          statusText,
          error: error || undefined
        }
      : item
  )
}

async function loadWorkflowList() {
  listLoading.value = true
  try {
    const records = await listAllWorkflowsApi()
    workflowList.value = sortWorkflowsByLatest(records)
    console.info('[client-workflow-progress] latest workflow list loaded', {
      workflowIds: workflowList.value.map((item) => item.id),
      total: workflowList.value.length
    })
  } catch (error: any) {
    ElMessage.error(error?.message || '加载工作流列表失败')
    workflowList.value = []
  } finally {
    listLoading.value = false
  }
}

async function loadWorkflowDetail(targetWorkflowId: number) {
  detailLoading.value = true
  uploadContractLoaded.value = false
  try {
    const [detailRes, uploadRes, contractRes] = await Promise.all([
      getWorkflowDetailApi(targetWorkflowId),
      getWorkflowUploadProgress(targetWorkflowId).catch(() => null),
      getWorkflowUploadContract(targetWorkflowId).catch(() => null)
    ])
    workflowDetail.value = detailRes.data
    uploadProgress.value = uploadRes?.data ?? null
    if (contractRes?.data) {
      uploadContract.value = contractRes.data
      uploadContractLoaded.value = true
    } else {
      uploadError.value = '无法读取当前工作流上传协议，上传功能已暂时停用。'
    }
    console.info('[client-workflow-progress] workflow detail synced from backend', {
      workflowId: targetWorkflowId,
      workflowStatus: workflowDetail.value?.status,
      currentStep: workflowDetail.value?.currentStep,
      latestUploadStatus: uploadProgress.value?.latestUploadStatus || null,
      managedModelCount: uploadProgress.value?.collectedModelCount || 0,
      localUploadOverrideActive: uploadDialogVisible.value && uploadUiStep.value > 0,
      persistentLocalCacheActive: false
    })
  } catch (error: any) {
    ElMessage.error(error?.message || '加载工作流详情失败')
    workflowDetail.value = null
    uploadProgress.value = null
  } finally {
    detailLoading.value = false
  }
}

async function handleSaveWorkflowResult() {
  const workflowId = resolvedWorkflowId.value
  if (!workflowId) return
  savingResult.value = true
  try {
    await saveWorkflowResultApi(workflowId)
    ElMessage.success('工作流结果已保存，后续工作流不会自动替换它。')
    await loadWorkflowDetail(workflowId)
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
    await loadWorkflowDetail(workflowId)
  } catch (error: any) {
    ElMessage.error(error?.message || WORKFLOW_PROGRESS_TEXT.actionFailed)
  } finally {
    deletingResult.value = false
  }
}

async function selectWorkflow(targetWorkflowId: number, syncRoute = false) {
  if (!targetWorkflowId) return
  activeWorkflowId.value = targetWorkflowId
  resetUploadUi()
  if (syncRoute) {
    await router.replace({
      name: 'client-progress',
      query: { id: String(targetWorkflowId) }
    })
  }
  await loadWorkflowDetail(targetWorkflowId)
}

async function refreshCurrentWorkflow(
  targetWorkflowId = resolvedWorkflowId.value,
  options: { preserveUploadUi?: boolean } = {}
) {
  const preserveUploadUi = options.preserveUploadUi === true
  await loadWorkflowList()
  const nextWorkflowId =
    targetWorkflowId && workflowList.value.some((item) => item.id === targetWorkflowId)
      ? targetWorkflowId
      : workflowList.value[0]?.id || null

  if (!nextWorkflowId) {
    activeWorkflowId.value = null
    workflowDetail.value = null
    uploadProgress.value = null
    resetUploadUi()
    stopPolling()
    return
  }

  if (preserveUploadUi && activeWorkflowId.value === nextWorkflowId) {
    if (route.query.id !== String(nextWorkflowId)) {
      await router.replace({
        name: 'client-progress',
        query: { id: String(nextWorkflowId) }
      })
    }
    await loadWorkflowDetail(nextWorkflowId)
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
    resetUploadUi()
    stopPolling()
    return
  }

  if (routeWorkflowId.value && !workflowList.value.some((item) => item.id === routeWorkflowId.value)) {
    console.warn('[client-workflow-progress] route workflow id is not visible, fallback to latest workflow', {
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
    console.info(WORKFLOW_PROGRESS_TEXT.defaultSelectLogClient, {
      workflowId: targetWorkflowId,
      source: 'latest-workflow'
    })
  }
  await selectWorkflow(targetWorkflowId, true)
}

function handleUploadFileChange(_uploadFileObj: any, uploadFiles: any[]) {
  if (uploadSubmitting.value) {
    ElMessage.warning('正在上传中，请稍候或等待上传完成')
    return
  }

  const limit = uploadContract.value.uploadProtocol === 'WEIGHTS_V1'
    ? 1
    : requiredModelCount.value <= 1 ? 1 : Math.max(0, remainingUploadSlots.value)
  const nextFiles = uploadFiles
    .map((item) => item?.raw as File | undefined)
    .filter((item): item is File => Boolean(item))
    .slice(0, limit)
    .map((file) => ({
      uid: `${file.name}-${file.size}-${file.lastModified}`,
      name: file.name,
      size: file.size,
      raw: file,
      status: 'pending' as LocalUploadStatus,
      statusText: '等待上传'
    }))

  selectedUploadFiles.value = nextFiles
  uploadRef.value?.clearFiles?.()
  console.info('[client-workflow-progress] upload files selected', {
    workflowId: activeWorkflowId.value,
    filenames: nextFiles.map((item) => item.name),
    expectedModelCount: requiredModelCount.value,
    remainingUploadSlots: remainingUploadSlots.value
  })

  if (uploadFiles.length > nextFiles.length) {
    ElMessage.warning(`最多只能选择 ${limit} 个文件，已自动丢弃多余文件`)
  }
}

function handleUploadFileRemove() {
  if (uploadSubmitting.value) return
  selectedUploadFiles.value = []
  currentUploadingFileUid.value = null
  console.info('[client-workflow-progress] upload file selection cleared', {
    workflowId: activeWorkflowId.value
  })
}

async function legacyStartWorkflowUpload() {
  const workflowId = resolvedWorkflowId.value
  const workflow = workflowDetail.value
  const modelAssetId = resolvedClientModelAssetId.value

  console.info('[client-workflow-progress] upload button clicked', {
    workflowId,
    routeWorkflowId: routeWorkflowId.value,
    activeWorkflowId: activeWorkflowId.value,
    detailWorkflowId: workflow?.id || null,
    fileCount: selectedUploadFiles.value.length,
    modelAssetId
  })

  if (!workflowId || !workflow || workflow.id !== workflowId) {
    uploadError.value = '工作流信息加载失败，请重新选择'
    return
  }
  if (!selectedUploadFiles.value.length) {
    uploadError.value = '请先选择要上传的模型文件'
    return
  }
  if (!modelAssetId) {
    uploadError.value = '无法识别客户端模型资产，请稍后重试'
    return
  }

  uploadDialogVisible.value = true
  uploadSubmitting.value = true
  uploadError.value = ''
  uploadTransferProgress.value = 0

  let successCount = 0
  let failureCount = 0
  try {
    const { sha256File, encryptFileAES } = await import('@/utils/crypto')

    for (const [index, fileItem] of selectedUploadFiles.value.entries()) {
      currentUploadingFileUid.value = fileItem.uid
      fileSha256Preview.value = ''
      uploadTransferProgress.value = 0

      try {
        uploadUiStep.value = 1
        updateLocalUploadFileStatus(fileItem.uid, 'encrypting', '正在计算文件摘要')
        const fileSha256 = await sha256File(fileItem.raw)
        fileSha256Preview.value = `${fileSha256.slice(0, 16)}...`

        uploadUiStep.value = 2
        updateLocalUploadFileStatus(fileItem.uid, 'initializing', '正在申请上传令牌')
        const initResp = await initModelUpload({
          workflowId,
          modelAssetId,
          originalFilename: fileItem.name,
          fileSize: fileItem.size,
          fileSha256,
          replaceExisting: shouldReplaceExistingUpload.value && index === 0
        })

        const initData = initResp.data
        if (!initData?.uploadId || !initData.uploadToken || !initData.aesKeyBase64 || !initData.aesIvBase64) {
          throw new Error('申请上传令牌失败，请检查网络连接后重试')
        }

        uploadUiStep.value = 3
        updateLocalUploadFileStatus(fileItem.uid, 'encrypting', '正在执行洗牌加密')
        const encryptedBuffer = await encryptFileAES(fileItem.raw, initData.aesKeyBase64, initData.aesIvBase64)
        const encryptedBlob = new Blob([encryptedBuffer], { type: 'application/octet-stream' })

        uploadUiStep.value = 4
        updateLocalUploadFileStatus(fileItem.uid, 'uploading', '正在上传加密后的模型文件')
        const formData = new FormData()
        formData.append('file', encryptedBlob, `${fileItem.name}.enc`)
        await uploadEncryptedModelFile(initData.uploadId, initData.uploadToken, formData, (percent) => {
          uploadTransferProgress.value = percent
        })

        uploadUiStep.value = 5
        updateLocalUploadFileStatus(fileItem.uid, 'completed', '上传完成，等待服务端接收')
        successCount += 1
        console.info('[client-workflow-progress] single file upload completed', {
          workflowId,
          filename: fileItem.name,
          uploadId: initData.uploadId
        })
      } catch (error: any) {
        failureCount += 1
        uploadError.value = error?.message || '文件上传失败'
        updateLocalUploadFileStatus(fileItem.uid, 'failed', '上传失败', uploadError.value)
        console.error('[client-workflow-progress] single file upload failed', {
          workflowId,
          filename: fileItem.name,
          message: uploadError.value
        })
      }
    }
  } catch (error: any) {
    uploadError.value = error?.message || '模型上传初始化失败'
    console.error('[client-workflow-progress] batch upload bootstrap failed', {
      workflowId,
      message: uploadError.value
    })
  } finally {
    uploadSubmitting.value = false
    await refreshCurrentWorkflow(workflowId, { preserveUploadUi: true })
    console.info('[client-workflow-progress] batch upload finished', {
      workflowId,
      successCount,
      failureCount,
      latestUploadStatus: uploadProgress.value?.latestUploadStatus || null
    })

    if (successCount > 0 && failureCount === 0) {
      ElMessage.success('所有模型文件上传成功，等待服务端处理')
    } else if (successCount > 0) {
      ElMessage.warning('部分模型文件上传成功，部分失败，请查看错误日志')
    } else if (uploadError.value) {
      ElMessage.error(uploadError.value || '模型上传失败，请稍后重试')
    }
  }
}

async function startWorkflowUpload() {
  const workflowId = resolvedWorkflowId.value
  const workflow = workflowDetail.value
  const modelAssetId = resolvedClientModelAssetId.value

  console.info('[client-workflow-progress] upload button clicked', {
    workflowId,
    routeWorkflowId: routeWorkflowId.value,
    activeWorkflowId: activeWorkflowId.value,
    detailWorkflowId: workflow?.id || null,
    fileCount: selectedUploadFiles.value.length,
    modelAssetId
  })

  if (!workflowId || !workflow || workflow.id !== workflowId) {
    uploadError.value = '工作流信息加载失败，请重新选择'
    return
  }
  if (!selectedUploadFiles.value.length) {
    uploadError.value = '请先选择要上传的模型文件'
    return
  }
  if (!modelAssetId) {
    uploadError.value = '无法识别客户端模型资产，请稍后重试'
    return
  }

  uploadDialogVisible.value = true
  uploadSubmitting.value = true
  uploadError.value = ''
  uploadTransferProgress.value = 0

  let successCount = 0
  let failureCount = 0
  let compatibilityModeNotified = false

  try {
    const { getWorkflowCryptoDiagnostics, prepareWorkflowUploadPayload, sha256File } = await import('@/utils/crypto')

    for (const [index, fileItem] of selectedUploadFiles.value.entries()) {
      currentUploadingFileUid.value = fileItem.uid
      fileSha256Preview.value = ''
      uploadTransferProgress.value = 0

      let currentStage = '初始化'

      try {
        const cryptoDiagnostics = getWorkflowCryptoDiagnostics()
        let clientCryptoMode: 'WEB_CRYPTO' | 'SERVER_COMPAT' = cryptoDiagnostics.preferredMode
        let fileSha256: string | undefined

        uploadUiStep.value = 1
        console.info('[client-workflow-progress] upload crypto environment', {
          workflowId,
          filename: fileItem.name,
          ...cryptoDiagnostics
        })

        if (clientCryptoMode === 'WEB_CRYPTO') {
          updateLocalUploadFileStatus(fileItem.uid, 'encrypting', '正在计算文件摘要')
          try {
            fileSha256 = await sha256File(fileItem.raw)
            fileSha256Preview.value = `${fileSha256.slice(0, 16)}...`
          } catch (error) {
            clientCryptoMode = 'SERVER_COMPAT'
            fileSha256Preview.value = '兼容模式'
            console.warn('[client-workflow-progress] native digest failed, switching to compatibility mode', {
              workflowId,
              filename: fileItem.name,
              error
            })
          }
        } else {
          fileSha256Preview.value = '兼容模式'
          updateLocalUploadFileStatus(fileItem.uid, 'encrypting', '正在准备兼容上传模式')
        }

        if (clientCryptoMode === 'SERVER_COMPAT' && !compatibilityModeNotified) {
          compatibilityModeNotified = true
          ElMessage.warning('当前环境已切换至兼容上传模式，由服务端完成加密处理')
        }

        uploadUiStep.value = 2
        currentStage = '申请令牌'
        updateLocalUploadFileStatus(fileItem.uid, 'initializing', '正在申请上传令牌')
        const initResp = await initModelUpload({
          workflowId,
          modelAssetId,
          originalFilename: fileItem.name,
          fileSize: fileItem.size,
          fileSha256,
          replaceExisting: shouldReplaceExistingUpload.value && index === 0
        })

        const initData = initResp.data
        if (!initData?.uploadId || !initData.uploadToken || !initData.aesKeyBase64 || !initData.aesIvBase64) {
          throw new Error('申请上传令牌失败，请检查网络连接后重试')
        }

        uploadUiStep.value = 3
        currentStage = '加密准备'
        updateLocalUploadFileStatus(
          fileItem.uid,
          'encrypting',
          clientCryptoMode === 'WEB_CRYPTO'
            ? '正在执行洗牌加密'
            : '当前环境使用兼容上传模式，由服务端完成摘要与加密'
        )
        const preparedPayload = await prepareWorkflowUploadPayload(
          fileItem.raw,
          initData.aesKeyBase64,
          initData.aesIvBase64,
          clientCryptoMode
        )

        if (preparedPayload.noticeMessage && !compatibilityModeNotified) {
          compatibilityModeNotified = true
          ElMessage.warning(preparedPayload.noticeMessage)
        }

        console.info('[client-workflow-progress] upload payload prepared', {
          workflowId,
          filename: fileItem.name,
          cryptoMode: preparedPayload.cryptoMode,
          uploadFilename: preparedPayload.uploadFilename,
          uploadBytes: preparedPayload.uploadBlob.size,
          diagnostics: preparedPayload.diagnostics
        })

        uploadUiStep.value = 4
        currentStage = '文件上传'
        updateLocalUploadFileStatus(
          fileItem.uid,
          'uploading',
          preparedPayload.cryptoMode === 'WEB_CRYPTO'
            ? '正在上传加密后的模型文件'
            : '正在上传兼容模式模型文件'
        )
        const formData = new FormData()
        formData.append('file', preparedPayload.uploadBlob, preparedPayload.uploadFilename)
        await appendWeightsProtocolMetadata(formData, initData.uploadProtocol, {
          manifest: uploadManifestFile.value,
          descriptor: uploadDescriptorFile.value
        })
        await uploadEncryptedModelFile(
          initData.uploadId,
          initData.uploadToken,
          formData,
          (percent) => {
            uploadTransferProgress.value = percent
          },
          preparedPayload.cryptoMode
        )

        uploadUiStep.value = 5
        updateLocalUploadFileStatus(fileItem.uid, 'completed', '上传完成，等待服务端接收')
        successCount += 1
        console.info('[client-workflow-progress] single file upload completed', {
          workflowId,
          filename: fileItem.name,
          uploadId: initData.uploadId,
          cryptoMode: preparedPayload.cryptoMode
        })
      } catch (error) {
        failureCount += 1
        const raw = error instanceof Error ? error.message : String(error || '')
        uploadError.value = uploadContract.value.uploadProtocol === 'WEIGHTS_V1'
          ? weightsUploadErrorMessage(raw)
          : resolveUploadClientErrorMessage(currentStage, error)
        updateLocalUploadFileStatus(fileItem.uid, 'failed', '上传失败', uploadError.value)
        console.error('[client-workflow-progress] single file upload failed', {
          workflowId,
          filename: fileItem.name,
          stage: currentStage,
          message: uploadError.value,
          error
        })
      }
    }
  } catch (error) {
    uploadError.value = resolveUploadClientErrorMessage('初始化上传', error)
    console.error('[client-workflow-progress] batch upload bootstrap failed', {
      workflowId,
      message: uploadError.value,
      error
    })
  } finally {
    uploadSubmitting.value = false
    await refreshCurrentWorkflow(workflowId, { preserveUploadUi: true })
    console.info('[client-workflow-progress] batch upload finished', {
      workflowId,
      successCount,
      failureCount,
      latestUploadStatus: uploadProgress.value?.latestUploadStatus || null
    })

    if (successCount > 0 && failureCount === 0) {
      ElMessage.success('所有模型文件上传成功，等待服务端处理')
    } else if (successCount > 0) {
      ElMessage.warning('部分模型文件上传成功，部分失败，请查看错误日志')
    } else if (uploadError.value) {
      ElMessage.error(uploadError.value || '模型上传失败，请稍后重试')
    }
  }
}

function shouldPoll() {
  const status = workflowDetail.value?.status
  if (!status) return false
  return ['CREATED', 'ACCEPTED', 'PREPARING', 'TRAINING_RUNNING', 'VALIDATING'].includes(status)
}

function stopPolling() {
  if (pollingTimer !== null) {
    window.clearInterval(pollingTimer)
    pollingTimer = null
  }
}

function startPollingIfNeeded() {
  stopPolling()
  if (!activeWorkflowId.value || !shouldPoll()) {
    return
  }
  pollingTimer = window.setInterval(async () => {
    console.info('[client-workflow-progress] polling workflow progress', {
      workflowId: activeWorkflowId.value,
      currentStep: workflowDetail.value?.currentStep,
      status: workflowDetail.value?.status
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
  () => clientPhaseKey.value,
  (value, previous) => {
    if (value && value !== previous) {
      console.info(WORKFLOW_PROGRESS_TEXT.uploadSwitchLog, {
        workflowId: activeWorkflowId.value,
        phase: value,
        label: CLIENT_VISUAL_STAGES.find((item) => item.key === value)?.title || value,
        workflowStatus: workflowDetail.value?.status,
        latestUploadStatus: uploadProgress.value?.latestUploadStatus || null
        ,
        renderedPhaseStatuses: clientPhases.value.map((phase) => ({
          key: phase.key,
          status: phase.status
        })),
        localUploadOverrideActive: uploadDialogVisible.value && uploadUiStep.value > 0
      })
    }
  },
  { immediate: true }
)

watch(
  () => serverProcessPhase.value,
  (value, previous) => {
    if (value !== previous) {
      console.info('[client-workflow-progress] server sync phase changed', {
        workflowId: resolvedWorkflowId.value,
        phase: value,
        currentStep: workflowDetail.value?.currentStep,
        latestUploadStatus: uploadProgress.value?.latestUploadStatus || null,
        managedModelCount: uploadProgress.value?.collectedModelCount || 0
      })
    }
  },
  { immediate: true }
)

watch(
  () => uploadUiStagePercent.value,
  (value, previous) => {
    if (value === 100 && previous !== 100) {
      console.info('[client-workflow-progress] upload dialog reached 100%', {
        workflowId: resolvedWorkflowId.value,
        step: uploadUiStep.value,
        canClose: uploadDialogCanClose.value
      })
    }
  }
)

watch(
  () => [workflowDetail.value?.status, uploadProgress.value?.latestUploadStatus, uploadProgress.value?.collectedModelCount],
  () => {
    startPollingIfNeeded()
  }
)

function handleManifestFileChange(event: Event) {
  uploadManifestFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

function handleDescriptorFileChange(event: Event) {
  uploadDescriptorFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

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

.hero-status {
  flex-shrink: 0;
}

.info-card {
  margin-bottom: 18px;
}

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

.upload-action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin: 16px 0;
  flex-wrap: wrap;
}

.upload-file-meta {
  color: #5e7082;
  font-size: 13px;
  line-height: 1.8;
}

.upload-action-buttons {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.upload-action-buttons :deep(.el-button + .el-button),
.result-action-bar :deep(.el-button + .el-button) {
  margin-left: 0;
}

.summary-alert {
  margin-top: 16px;
}

.result-action-bar {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin: 12px 0 0;
  flex-wrap: wrap;
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
