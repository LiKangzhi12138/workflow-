<template>
  <div class="workbench">
    <el-alert type="info" :closable="false" show-icon class="intro-alert">
      <template #title>{{ headerTitle }}</template>
      <div>{{ headerDesc }}</div>
    </el-alert>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="intro-alert"
      title="保留策略提示"
      description="独立验证按当前登录用户隔离，只保留最近一次结果；再次发起会自动覆盖并清理你上一次的结果文件、日志引用和识别样例，不影响其他用户。"
    />

    <el-alert
      v-if="hintText"
      type="warning"
      :closable="false"
      show-icon
      class="intro-alert"
    >
      <template #title>已从资产管理页进入</template>
      <div>{{ hintText }}</div>
    </el-alert>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="12">
        <el-card class="panel-card">
          <template #header>
            <div class="card-title">发起独立验证</div>
          </template>

          <el-radio-group
            v-if="isServer"
            v-model="submitMode"
            class="mode-switch"
          >
            <el-radio-button label="registry">正式资产验证</el-radio-button>
            <el-radio-button label="temp">临时上传验证</el-radio-button>
          </el-radio-group>

          <el-form label-width="120px" class="selection-form">
            <template v-if="submitMode === 'registry'">
              <el-alert type="success" :closable="false" show-icon class="mode-alert">
                从服务端正式资产库中选择已校验通过的模型和数据集执行独立验证。
              </el-alert>

              <el-form-item label="模型资产">
                <el-select
                  v-model="registryForm.modelAssetId"
                  filterable
                  clearable
                  style="width: 100%"
                  :loading="modelsLoading"
                  placeholder="选择已通过校验的正式模型资产"
                >
                  <el-option
                    v-for="item in modelOptions"
                    :key="item.id"
                    :label="formatModelOptionLabel(item)"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>

              <el-form-item label="数据集资产">
                <el-select
                  v-model="registryForm.datasetAssetId"
                  filterable
                  clearable
                  style="width: 100%"
                  :loading="datasetsLoading"
                  placeholder="选择已通过校验的正式数据集资产"
                >
                  <el-option
                    v-for="item in datasetOptions"
                    :key="item.id"
                    :label="formatDatasetOptionLabel(item)"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
            </template>

            <template v-else>
              <el-alert type="warning" :closable="false" show-icon class="mode-alert">
                本次验证会临时上传模型文件和数据集 zip 包。验证完成后只保留结果、日志和识别样例，不保留这次临时输入。
              </el-alert>

              <el-form-item label="模型文件">
                <el-upload
                  v-model:file-list="modelFileList"
                  :auto-upload="false"
                  :limit="1"
                  accept=".pt,.pth,.weights,.onnx,.bin"
                  :on-change="handleModelFileChange"
                  :on-remove="handleModelFileRemove"
                >
                  <el-button type="primary" plain>选择模型文件</el-button>
                </el-upload>
              </el-form-item>

              <el-form-item label="数据集 zip">
                <el-upload
                  v-model:file-list="datasetFileList"
                  :auto-upload="false"
                  :limit="1"
                  accept=".zip"
                  :on-change="handleDatasetFileChange"
                  :on-remove="handleDatasetFileRemove"
                >
                  <el-button type="primary" plain>选择数据集 zip</el-button>
                </el-upload>
              </el-form-item>
            </template>

            <el-form-item label="算法版本">
              <el-select v-model="algorithmType" style="width: 100%">
                <el-option label="YOLOv8" value="YOLOv8" />
                <el-option label="YOLOv10" value="YOLOv10" />
                <el-option label="YOLOv11" value="YOLOv11" />
              </el-select>
            </el-form-item>

            <el-form-item label="操作">
              <el-space wrap>
                <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="handleSubmit">
                  开始验证
                </el-button>
                <el-button :loading="refreshing" @click="refreshWorkbench">刷新</el-button>
              </el-space>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12">
        <el-card class="panel-card">
          <template #header>
            <div class="card-title">当前任务</div>
          </template>

          <div class="status-wrap">
            <el-tag :type="statusTagType(displayValidation?.status)" effect="dark">
              {{ formatValidationStatus(displayValidation?.status) }}
            </el-tag>
            <div class="status-tip">{{ currentStatusTip }}</div>
          </div>

          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="验证编号">
              {{ displayValidation?.validationCode || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="输入模式">
              {{ formatInputMode(displayValidation?.inputMode) }}
            </el-descriptions-item>
            <el-descriptions-item label="模型">
              {{ displayValidation?.modelAssetName || resolveNameFromPath(displayValidation?.modelPath) || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="数据集">
              {{ displayValidation?.datasetAssetName || resolveNameFromPath(displayValidation?.datasetPath) || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="输入清理">
              {{ formatCleanupStatus(displayValidation?.inputCleanupStatus, displayValidation?.retainInput) }}
            </el-descriptions-item>
            <el-descriptions-item label="发起时间">
              {{ formatDateTime(displayValidation?.createdAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="完成时间">
              {{ formatDateTime(displayValidation?.finishedAt || displayValidation?.updatedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="错误信息">
              {{ displayValidation?.errorMessage || '-' }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="panel-card">
      <template #header>
        <div class="card-title">最近独立验证记录</div>
      </template>

      <el-table
        v-if="recentValidations.length > 0"
        :data="recentValidations"
        size="small"
        border
        @row-click="handleSelectRecent"
      >
        <el-table-column prop="validationCode" label="验证编号" min-width="170" />
        <el-table-column label="输入模式" width="120">
          <template #default="{ row }">
            {{ formatInputMode(row.inputMode) }}
          </template>
        </el-table-column>
        <el-table-column prop="modelAssetName" label="模型" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.modelAssetName || resolveNameFromPath(row.modelPath) || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="datasetAssetName" label="数据集" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.datasetAssetName || resolveNameFromPath(row.datasetPath) || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ formatValidationStatus(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发起时间" min-width="170">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column width="90" label="查看">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="handleSelectRecent(row)">
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else description="暂无独立验证记录" />
    </el-card>

    <el-card class="panel-card">
      <template #header>
        <div class="card-title">最近一次验证结果</div>
      </template>
      <ValidationResultCard :result="displayValidation" :view="currentResultView" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadFiles, UploadUserFile } from 'element-plus'
import {
  getStandaloneValidationDetail,
  getStandaloneValidationResultView,
  listMyStandaloneValidations,
  submitStandaloneValidation,
  submitTemporaryStandaloneValidation,
  type StandaloneValidationVO,
  type ValidationResultView
} from '@/api/validation'
import { listAllModelAssetsApi, type ModelAssetItem } from '@/api/model'
import { listAllDatasetAssetsApi, type DatasetAssetItem } from '@/api/dataset'
import ValidationResultCard from './ValidationResultCard.vue'

interface Props {
  roleCode: 'CLIENT' | 'SERVER'
}

const props = defineProps<Props>()
const route = useRoute()

const isServer = computed(() => props.roleCode === 'SERVER')
const submitMode = ref<'registry' | 'temp'>(isServer.value ? 'registry' : 'temp')
const algorithmType = ref('YOLOv10')

const modelsLoading = ref(false)
const datasetsLoading = ref(false)
const refreshing = ref(false)
const submitting = ref(false)

const modelOptions = ref<ModelAssetItem[]>([])
const datasetOptions = ref<DatasetAssetItem[]>([])
const recentValidations = ref<StandaloneValidationVO[]>([])
const currentValidation = ref<StandaloneValidationVO | null>(null)
const currentResultView = ref<ValidationResultView | null>(null)
const selectedValidationId = ref<number | null>(null)

const registryForm = reactive<{
  modelAssetId?: number
  datasetAssetId?: number
}>({})

const tempFiles = reactive<{
  modelFile: File | null
  datasetArchive: File | null
}>({
  modelFile: null,
  datasetArchive: null
})

const modelFileList = ref<UploadUserFile[]>([])
const datasetFileList = ref<UploadUserFile[]>([])

let pollTimer: number | null = null

const headerTitle = computed(() => (isServer.value ? '服务端独立验证工作台' : '客户端独立验证工作台'))
const headerDesc = computed(() =>
  isServer.value
    ? '服务端可以使用浏览器上传、服务端路径导入或工作流自动纳管的正式资产发起验证，也可以临时上传模型和数据集做一次性验证。'
    : '客户端独立验证默认走临时上传模式。验证完成后保留结果，不保留本次临时上传的模型与数据集。'
)

const hintText = computed(() => {
  const parts: string[] = []
  if (route.query.modelName) {
    parts.push(`模型资产：${route.query.modelName}`)
  }
  if (route.query.datasetName) {
    parts.push(`数据集资产：${route.query.datasetName}`)
  }
  if (parts.length === 0) {
    return ''
  }
  if (submitMode.value === 'temp') {
    return `${parts.join('；')}。请重新选择对应本地文件后开始临时验证。`
  }
  return `${parts.join('；')}。已自动带入服务端正式资产选择。`
})

const displayValidation = computed<StandaloneValidationVO | null>(() => {
  if (currentValidation.value) {
    return currentValidation.value
  }
  if (selectedValidationId.value != null) {
    return recentValidations.value.find((item) => item.id === selectedValidationId.value) || null
  }
  return recentValidations.value[0] || null
})

const currentStatusTip = computed(() => {
  if (!displayValidation.value) {
    return submitMode.value === 'temp'
      ? '选择本次验证需要的模型文件和数据集 zip 包后即可开始。'
      : '选择服务端已校验通过的正式模型和数据集资产后即可开始。'
  }
  return `当前任务 ${displayValidation.value.validationCode}，状态：${formatValidationStatus(displayValidation.value.status)}`
})

const canSubmit = computed(() => {
  if (submitting.value) return false
  if (submitMode.value === 'registry') {
    return Boolean(registryForm.modelAssetId && registryForm.datasetAssetId)
  }
  return Boolean(tempFiles.modelFile && tempFiles.datasetArchive)
})

function formatModelOptionLabel(item: ModelAssetItem) {
  return `${item.assetName} | ${item.modelVersion || '-'} | ${item.sourcePath || item.filePath || '-'}`
}

function formatDatasetOptionLabel(item: DatasetAssetItem) {
  return `${item.assetName} | ${item.imageCount ?? '-'} 张图片 | ${item.sourcePath || item.filePath || '-'}`
}

function formatValidationStatus(status?: string) {
  switch (status) {
    case 'INITIATED':
      return '已提交'
    case 'VALIDATING':
      return '验证中'
    case 'COMPLETED':
      return '已完成'
    case 'FAILED':
      return '失败'
    default:
      return status || '未开始'
  }
}

function formatInputMode(inputMode?: string) {
  if (inputMode === 'TEMP_UPLOAD') return '临时上传'
  if (inputMode === 'ASSET_REFERENCE') return '正式资产引用'
  return '-'
}

function formatCleanupStatus(status?: string, retainInput?: number) {
  if (retainInput === 1) return '保留输入'
  if (status === 'COMPLETED') return '已清理'
  if (status === 'FAILED') return '清理失败'
  if (status === 'SKIPPED') return '无需清理'
  if (status) return status
  return '待处理'
}

function statusTagType(status?: string) {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'VALIDATING':
      return 'warning'
    case 'INITIATED':
      return 'info'
    default:
      return 'info'
  }
}

function formatDateTime(value?: string) {
  if (!value) return '-'
  try {
    return new Date(value).toLocaleString('zh-CN')
  } catch {
    return value
  }
}

function resolveNameFromPath(path?: string) {
  if (!path) return ''
  const normalized = path.replaceAll('\\', '/')
  const index = normalized.lastIndexOf('/')
  return index >= 0 ? normalized.slice(index + 1) : normalized
}

function resolveErrorMessage(error: any, fallback: string) {
  return error?.response?.data?.message || error?.message || fallback
}

function shouldPoll(status?: string) {
  return status === 'INITIATED' || status === 'VALIDATING'
}

function handleModelFileChange(file: UploadFile, files: UploadFiles) {
  tempFiles.modelFile = (file.raw as File) || null
  modelFileList.value = files.slice(-1)
}

function handleModelFileRemove() {
  tempFiles.modelFile = null
}

function handleDatasetFileChange(file: UploadFile, files: UploadFiles) {
  tempFiles.datasetArchive = (file.raw as File) || null
  datasetFileList.value = files.slice(-1)
}

function handleDatasetFileRemove() {
  tempFiles.datasetArchive = null
}

async function loadRegistryOptions() {
  if (!isServer.value) return

  modelsLoading.value = true
  datasetsLoading.value = true
  try {
    const [models, datasets] = await Promise.all([
      listAllModelAssetsApi({
        recordMode: 'FORMAL_ASSET',
        validated: true
      }),
      listAllDatasetAssetsApi({
        recordMode: 'FORMAL_ASSET',
        validated: true
      })
    ])
    modelOptions.value = models
    datasetOptions.value = datasets

    const routeModelId = Number(route.query.modelRegistryId || 0)
    const routeDatasetId = Number(route.query.datasetRegistryId || 0)
    if (routeModelId) {
      registryForm.modelAssetId = routeModelId
    }
    if (routeDatasetId) {
      registryForm.datasetAssetId = routeDatasetId
    }
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '加载服务端正式资产失败'))
  } finally {
    modelsLoading.value = false
    datasetsLoading.value = false
  }
}

async function loadRecentValidations(preserveSelection: boolean = true) {
  try {
    const res = await listMyStandaloneValidations(10)
    recentValidations.value = res.data || []

    if (!preserveSelection || selectedValidationId.value == null) {
      selectedValidationId.value = recentValidations.value[0]?.id ?? null
    }

    if (!currentValidation.value && recentValidations.value[0]) {
      currentValidation.value = recentValidations.value[0]
    }

    const activeValidation = currentValidation.value ?? recentValidations.value[0] ?? null
    if (activeValidation?.id) {
      await loadValidationResultView(activeValidation.id)
    } else {
      currentResultView.value = null
    }
    if (activeValidation && shouldPoll(activeValidation.status)) {
      startPolling(activeValidation.id)
    }
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '加载最近验证记录失败'))
  }
}

async function loadValidationDetail(validationId: number) {
  try {
    const res = await getStandaloneValidationDetail(validationId)
    currentValidation.value = res.data
    selectedValidationId.value = validationId
    await loadValidationResultView(validationId)

    if (shouldPoll(res.data?.status)) {
      startPolling(validationId)
    } else {
      stopPolling()
    }
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '加载验证详情失败'))
  }
}

async function loadValidationResultView(validationId: number) {
  try {
    const res = await getStandaloneValidationResultView(validationId)
    currentResultView.value = res.data
  } catch {
    currentResultView.value = null
  }
}

async function handleSubmit() {
  submitting.value = true
  try {
    let res
    if (submitMode.value === 'registry') {
      if (!registryForm.modelAssetId || !registryForm.datasetAssetId) {
        ElMessage.warning('请选择服务器模型登记项和数据集登记项')
        return
      }
      res = await submitStandaloneValidation({
        modelAssetId: registryForm.modelAssetId,
        datasetAssetId: registryForm.datasetAssetId,
        algorithmType: algorithmType.value
      })
    } else {
      if (!tempFiles.modelFile || !tempFiles.datasetArchive) {
        ElMessage.warning('请重新选择模型文件和数据集 zip 包')
        return
      }
      res = await submitTemporaryStandaloneValidation({
        modelFile: tempFiles.modelFile,
        datasetArchive: tempFiles.datasetArchive,
        algorithmType: algorithmType.value
      })
    }

    currentValidation.value = res.data
    selectedValidationId.value = res.data?.id ?? null
    if (res.data?.id) {
      await loadValidationResultView(res.data.id)
    }
    await loadRecentValidations(false)

    if (res.data?.status === 'FAILED' && res.data?.errorMessage) {
      ElMessage.error(res.data.errorMessage)
      return
    }

    ElMessage.success('独立验证任务已提交')

    if (res.data?.id && shouldPoll(res.data.status)) {
      startPolling(res.data.id)
    }
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '发起独立验证失败'))
  } finally {
    submitting.value = false
  }
}

async function handleSelectRecent(row: StandaloneValidationVO) {
  await loadValidationDetail(row.id)
}

async function refreshWorkbench() {
  refreshing.value = true
  try {
    await Promise.all([loadRegistryOptions(), loadRecentValidations(false)])
  } finally {
    refreshing.value = false
  }
}

function startPolling(validationId: number) {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    try {
      const res = await getStandaloneValidationDetail(validationId)
      currentValidation.value = res.data
      if (!shouldPoll(res.data?.status)) {
        stopPolling()
        await loadValidationResultView(validationId)
        await loadRecentValidations(false)
      }
    } catch {
      // keep silent in polling
    }
  }, 4000)
}

function stopPolling() {
  if (pollTimer != null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(
  () => route.fullPath,
  async () => {
    if (isServer.value) {
      await loadRegistryOptions()
    }
  }
)

onMounted(async () => {
  await Promise.all([loadRegistryOptions(), loadRecentValidations(false)])
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.workbench {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.intro-alert {
  margin-bottom: 0;
}

.panel-card {
  border-radius: 16px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.selection-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.mode-switch {
  margin-bottom: 14px;
}

.mode-alert {
  margin-bottom: 12px;
}

.status-wrap {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 14px;
}

.status-tip {
  font-size: 13px;
  color: #64748b;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.workbench :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
