<template>
  <div class="page">
    <el-card shadow="never" class="panel-card">
      <div class="hero">
        <div>
          <div class="page-title">{{ title }}</div>
          <div class="page-desc">{{ desc }}</div>
        </div>
        <div class="hero-tags">
          <el-tag type="info">运行模式：{{ runtimeProfile?.runtimeMode || '-' }}</el-tag>
          <el-tag :type="activeRecordMode === 'FORMAL_ASSET' ? 'warning' : 'success'">
            {{ activeRecordMode === 'FORMAL_ASSET' ? '正式模型资产' : '客户端路径登记簿' }}
          </el-tag>
        </div>
      </div>

      <el-alert type="info" :closable="false" show-icon class="intro-alert">
        <template #title>{{ isServer ? '这是服务端正式模型资产库' : '这是客户端模型资产管理页' }}</template>
        <div v-if="isServer">
          可通过浏览器上传或服务端路径导入创建正式资产；工作流解密模型和联邦聚合模型也会自动纳管到这里。
        </div>
        <div v-else>
          路径登记只记录模型名称和本地路径；浏览器不会仅凭登记的路径读取电脑文件。也可以上传模型形成正式资产。
        </div>
      </el-alert>

      <el-alert
        v-if="isServer"
        type="warning"
        :closable="false"
        show-icon
        class="intro-alert"
      >
        <template #title>服务端路径导入说明</template>
        <div>
          服务端路径导入会从允许的挂载目录复制模型文件到系统受控的正式资产目录。
          <span v-if="runtimeProfile?.serverPathImportRoots?.length">
            当前允许根目录：{{ runtimeProfile.serverPathImportRoots.join('；') }}
          </span>
        </div>
      </el-alert>

      <el-radio-group
        v-if="!isServer"
        v-model="activeRecordMode"
        class="record-mode-switch"
        @change="handleRecordModeChange"
      >
        <el-radio-button label="PATH_REGISTRY">本地路径登记</el-radio-button>
        <el-radio-button label="FORMAL_ASSET">正式上传资产</el-radio-button>
      </el-radio-group>

      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="按名称、版本或路径搜索"
          clearable
          style="width: 320px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <div class="toolbar-actions">
          <el-button @click="loadList">刷新</el-button>
          <el-button v-if="showPathRegistry" type="primary" @click="openCreateDialog">新增登记</el-button>
          <el-button type="primary" plain @click="openUploadDialog">上传模型</el-button>
          <el-button
            v-if="isServer && canUseServerImport"
            type="warning"
            plain
            @click="openImportDialog"
          >
            导入正式资产
          </el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="tableData" border class="asset-table">
        <el-table-column prop="assetName" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="modelType" label="模型类型" width="120" />
        <el-table-column prop="modelVersion" label="版本" width="120" />
        <el-table-column :label="showPathRegistry ? '登记路径' : '存储路径'" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            {{ resolveDisplayPath(row) }}
          </template>
        </el-table-column>
        <el-table-column v-if="activeRecordMode === 'FORMAL_ASSET'" label="资产来源" width="140">
          <template #default="{ row }">
            <el-tag type="info">{{ formatSourceType(row.sourceType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="校验状态" width="120">
          <template #default="{ row }">
            <el-tag :type="checkTagType(row)">{{ formatCheckStatus(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近校验" min-width="200">
          <template #default="{ row }">
            <div>{{ formatDateTime(row.lastCheckAt) }}</div>
            <div class="subtle-text">{{ row.lastCheckMessage || '未校验' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.status === 'READY' ? 'success' : 'info'">
              {{ formatRecordStatus(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="340" fixed="right">
          <template #default="{ row }">
            <el-space wrap>
              <el-button v-if="showPathRegistry" link type="primary" @click="openEditDialog(row)">编辑</el-button>
              <el-button link type="warning" @click="handleCheck(row.id)">校验</el-button>
              <el-button link type="info" @click="copyPath(row)">复制路径</el-button>
              <el-button link type="success" @click="goToValidation(row)">发起验证</el-button>
              <el-button
                v-if="showPathRegistry"
                link
                type="primary"
                @click="goToWorkflow(row)"
              >
                发起工作流上传
              </el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
            </el-space>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="[10, 20, 50]"
          :total="total"
          @current-change="loadList"
          @size-change="handlePageSizeChange"
        />
      </div>
    </el-card>

    <el-dialog
      v-if="!isServer"
      v-model="editDialogVisible"
      :title="currentEditId ? '编辑模型路径登记项' : '新增模型路径登记项'"
      width="760px"
      destroy-on-close
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="132px">
        <el-form-item label="名称" prop="assetName">
          <el-input v-model="form.assetName" placeholder="例如：玉米病害模型 A" />
        </el-form-item>
        <el-form-item label="模型类型" prop="modelType">
          <el-select v-model="form.modelType" style="width: 100%">
            <el-option label="YOLO" value="YOLO" />
            <el-option label="ResNet" value="ResNet" />
            <el-option label="Custom" value="Custom" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型版本" prop="modelVersion">
          <el-input v-model="form.modelVersion" placeholder="例如：YOLO10" />
        </el-form-item>
        <el-form-item label="任务类型" prop="taskType">
          <el-select v-model="form.taskType" style="width: 100%">
            <el-option label="DETECTION" value="DETECTION" />
            <el-option label="CLASSIFICATION" value="CLASSIFICATION" />
            <el-option label="SEGMENTATION" value="SEGMENTATION" />
          </el-select>
        </el-form-item>
        <el-form-item label="YOLO 版本">
          <el-select v-model="form.yoloVersion" style="width: 100%">
            <el-option label="YOLOv8" value="YOLOv8" />
            <el-option label="YOLOv10" value="YOLOv10" />
            <el-option label="YOLOv11" value="YOLOv11" />
          </el-select>
        </el-form-item>
        <el-form-item :label="isServer ? '服务器路径' : '本地路径'" prop="filePath">
          <el-input
            v-model="form.filePath"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            :placeholder="isServer ? '填写服务器可访问路径，例如 /mnt/models/best.pt' : '填写你自己的本地路径，例如 C:/Users/.../best.pt'"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.description" type="textarea" :rows="4" placeholder="补充模型用途、来源、注意事项" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">
          {{ currentEditId ? '保存修改' : '保存登记' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="uploadDialogVisible"
      title="上传正式模型资产"
      width="760px"
      destroy-on-close
      @closed="resetUploadForm"
    >
      <el-form ref="uploadFormRef" :model="uploadForm" :rules="uploadRules" label-width="132px">
        <el-form-item label="名称" prop="assetName">
          <el-input v-model="uploadForm.assetName" placeholder="正式资产名称" />
        </el-form-item>
        <el-form-item label="模型类型" prop="modelType">
          <el-select v-model="uploadForm.modelType" style="width: 100%">
            <el-option label="YOLO" value="YOLO" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型版本" prop="modelVersion">
          <el-input v-model="uploadForm.modelVersion" placeholder="例如：YOLO10" />
        </el-form-item>
        <el-form-item label="任务类型" prop="taskType">
          <el-select v-model="uploadForm.taskType" style="width: 100%">
            <el-option label="DETECTION" value="DETECTION" />
          </el-select>
        </el-form-item>
        <el-form-item label="YOLO 版本">
          <el-select v-model="uploadForm.yoloVersion" style="width: 100%">
            <el-option label="YOLOv8" value="YOLOv8" />
            <el-option label="YOLOv10" value="YOLOv10" />
            <el-option label="YOLOv11" value="YOLOv11" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型文件" required>
          <el-upload
            v-model:file-list="uploadFileList"
            :auto-upload="false"
            :limit="1"
            accept=".pt,.pth,.weights,.onnx,.bin"
            :on-change="handleUploadFileChange"
            :on-remove="handleUploadFileRemove"
          >
            <el-button type="primary" plain>选择模型文件</el-button>
          </el-upload>
        </el-form-item>
        <el-form-item v-if="uploadLoading" label="上传进度">
          <el-progress :percentage="uploadPercent" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="uploadForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploadLoading" @click="handleUpload">上传并纳管</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="importDialogVisible"
      title="导入正式模型资产"
      width="760px"
      destroy-on-close
      @closed="resetImportForm"
    >
      <el-alert type="warning" :closable="false" show-icon class="intro-alert">
        <template #title>导入后会进入正式资产链路</template>
        这是服务器管理员能力。导入后，模型文件会复制进入服务端正式存储，可用于服务端验证、工作流正式链路和联邦学习。
      </el-alert>

      <el-form ref="importFormRef" :model="importForm" :rules="importRules" label-width="132px">
        <el-form-item label="名称" prop="assetName">
          <el-input v-model="importForm.assetName" placeholder="正式资产名称" />
        </el-form-item>
        <el-form-item label="模型类型" prop="modelType">
          <el-select v-model="importForm.modelType" style="width: 100%">
            <el-option label="YOLO" value="YOLO" />
            <el-option label="ResNet" value="ResNet" />
            <el-option label="Custom" value="Custom" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型版本" prop="modelVersion">
          <el-input v-model="importForm.modelVersion" />
        </el-form-item>
        <el-form-item label="任务类型" prop="taskType">
          <el-select v-model="importForm.taskType" style="width: 100%">
            <el-option label="DETECTION" value="DETECTION" />
            <el-option label="CLASSIFICATION" value="CLASSIFICATION" />
            <el-option label="SEGMENTATION" value="SEGMENTATION" />
          </el-select>
        </el-form-item>
        <el-form-item label="YOLO 版本">
          <el-select v-model="importForm.yoloVersion" style="width: 100%">
            <el-option label="YOLOv8" value="YOLOv8" />
            <el-option label="YOLOv10" value="YOLOv10" />
            <el-option label="YOLOv11" value="YOLOv11" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务器路径" prop="filePath">
          <el-input
            v-model="importForm.filePath"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="填写服务器或挂载目录中的真实模型路径"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="importForm.description" type="textarea" :rows="4" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button type="warning" :loading="importLoading" @click="handleImport">
          导入正式资产
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules, UploadFile, UploadFiles, UploadUserFile } from 'element-plus'
import {
  checkModelAssetApi,
  createModelAssetApi,
  deleteModelAssetApi,
  importModelAssetApi,
  listModelAssetsApi,
  uploadModelAssetApi,
  updateModelAssetApi,
  type ModelAssetItem,
  type ModelRecordMode
} from '@/api/model'
import { getRuntimeProfileApi, type RuntimeProfile } from '@/api/system'
import { getLoginUser } from '@/utils/auth'
import { resolveValidPage } from '@/utils/pagination'

defineProps<{
  title: string
  desc: string
}>()

const router = useRouter()
const currentUser = getLoginUser()
const isServer = computed(() => currentUser?.roleCode === 'SERVER')
const activeRecordMode = ref<ModelRecordMode>(isServer.value ? 'FORMAL_ASSET' : 'PATH_REGISTRY')
const showPathRegistry = computed(() => !isServer.value && activeRecordMode.value === 'PATH_REGISTRY')

const runtimeProfile = ref<RuntimeProfile | null>(null)
const loading = ref(false)
const submitLoading = ref(false)
const importLoading = ref(false)
const uploadLoading = ref(false)
const uploadPercent = ref(0)
const tableData = ref<ModelAssetItem[]>([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: ''
})

const editDialogVisible = ref(false)
const importDialogVisible = ref(false)
const uploadDialogVisible = ref(false)
const currentEditId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const importFormRef = ref<FormInstance>()
const uploadFormRef = ref<FormInstance>()
const uploadFile = ref<File | null>(null)
const uploadFileList = ref<UploadUserFile[]>([])

const form = reactive({
  assetName: '',
  modelType: 'YOLO',
  modelVersion: 'YOLO10',
  taskType: 'DETECTION',
  yoloVersion: 'YOLOv10',
  filePath: '',
  description: ''
})

const importForm = reactive({
  assetName: '',
  modelType: 'YOLO',
  modelVersion: 'YOLO10',
  taskType: 'DETECTION',
  yoloVersion: 'YOLOv10',
  filePath: '',
  description: ''
})

const uploadForm = reactive({
  assetName: '',
  modelType: 'YOLO',
  modelVersion: 'YOLO10',
  taskType: 'DETECTION',
  yoloVersion: 'YOLOv10',
  description: ''
})

const rules: FormRules = {
  assetName: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  modelType: [{ required: true, message: '请选择模型类型', trigger: 'change' }],
  modelVersion: [{ required: true, message: '请输入模型版本', trigger: 'blur' }],
  taskType: [{ required: true, message: '请选择任务类型', trigger: 'change' }],
  filePath: [{ required: true, message: '请输入登记路径', trigger: 'blur' }]
}

const importRules: FormRules = {
  assetName: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  modelType: [{ required: true, message: '请选择模型类型', trigger: 'change' }],
  modelVersion: [{ required: true, message: '请输入模型版本', trigger: 'blur' }],
  taskType: [{ required: true, message: '请选择任务类型', trigger: 'change' }],
  filePath: [{ required: true, message: '请输入服务器路径', trigger: 'blur' }]
}

const uploadRules: FormRules = {
  assetName: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  modelType: [{ required: true, message: '请选择模型类型', trigger: 'change' }],
  modelVersion: [{ required: true, message: '请输入模型版本', trigger: 'blur' }],
  taskType: [{ required: true, message: '请选择任务类型', trigger: 'change' }]
}

const canUseServerImport = computed(
  () => Boolean(runtimeProfile.value?.serverPathImportEnabled && runtimeProfile.value?.serverPathImportAllowed)
)

function formatDateTime(value?: string) {
  if (!value) return '-'
  return value.replace('T', ' ')
}

function formatCheckStatus(row: ModelAssetItem) {
  if (row.lastCheckStatus === 'OK') return '已通过'
  if (row.lastCheckStatus === 'FAILED') return '未通过'
  return '未校验'
}

function checkTagType(row: ModelAssetItem) {
  if (row.lastCheckStatus === 'OK') return 'success'
  if (row.lastCheckStatus === 'FAILED') return 'danger'
  return 'info'
}

function formatRecordStatus(status?: string) {
  if (status === 'READY') return '可直接使用'
  if (status === 'REGISTERED') return '已登记'
  return status || '-'
}

function formatSourceType(sourceType?: string) {
  if (sourceType === 'BROWSER_UPLOAD') return '浏览器上传'
  if (sourceType === 'SERVER_IMPORT') return '服务端导入'
  if (sourceType === 'WORKFLOW_DECRYPT') return '工作流解密'
  if (sourceType === 'FEDERATED_OUTPUT') return '联邦全局模型'
  return sourceType || '历史资产'
}

function resolveDisplayPath(row: ModelAssetItem) {
  if (row.recordMode === 'FORMAL_ASSET') return row.filePath || row.sourcePath || '-'
  return row.sourcePath || row.filePath || '-'
}

function resetForm() {
  currentEditId.value = null
  form.assetName = ''
  form.modelType = 'YOLO'
  form.modelVersion = 'YOLO10'
  form.taskType = 'DETECTION'
  form.yoloVersion = 'YOLOv10'
  form.filePath = ''
  form.description = ''
  formRef.value?.clearValidate()
}

function resetImportForm() {
  importForm.assetName = ''
  importForm.modelType = 'YOLO'
  importForm.modelVersion = 'YOLO10'
  importForm.taskType = 'DETECTION'
  importForm.yoloVersion = 'YOLOv10'
  importForm.filePath = ''
  importForm.description = ''
  importFormRef.value?.clearValidate()
}

function resetUploadForm() {
  uploadForm.assetName = ''
  uploadForm.modelType = 'YOLO'
  uploadForm.modelVersion = 'YOLO10'
  uploadForm.taskType = 'DETECTION'
  uploadForm.yoloVersion = 'YOLOv10'
  uploadForm.description = ''
  uploadFile.value = null
  uploadFileList.value = []
  uploadPercent.value = 0
  uploadFormRef.value?.clearValidate()
}

async function loadRuntimeProfile() {
  const res = await getRuntimeProfileApi()
  runtimeProfile.value = res.data
}

async function loadList() {
  loading.value = true
  try {
    const res = await listModelAssetsApi({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      recordMode: activeRecordMode.value
    })
    const nextRecords = res.data?.records || []
    const nextTotal = Number(res.data?.total || 0)
    const validPage = resolveValidPage(query.pageNum, query.pageSize, nextTotal)
    total.value = nextTotal
    if (validPage !== query.pageNum) {
      query.pageNum = validPage
      await loadList()
      return
    }
    tableData.value = nextRecords
  } catch (error: any) {
    ElMessage.error(error.message || '加载模型登记列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  void loadList()
}

function handlePageSizeChange() {
  query.pageNum = 1
  void loadList()
}

function handleRecordModeChange() {
  query.pageNum = 1
  void loadList()
}

function openCreateDialog() {
  if (!showPathRegistry.value) return
  resetForm()
  editDialogVisible.value = true
}

function openUploadDialog() {
  resetUploadForm()
  uploadDialogVisible.value = true
}

function openEditDialog(row: ModelAssetItem) {
  currentEditId.value = row.id
  form.assetName = row.assetName
  form.modelType = row.modelType
  form.modelVersion = row.modelVersion
  form.taskType = row.taskType
  form.yoloVersion = row.yoloVersion || 'YOLOv10'
  form.filePath = row.sourcePath || row.filePath || ''
  form.description = row.description || ''
  editDialogVisible.value = true
}

function openImportDialog(row?: ModelAssetItem) {
  resetImportForm()
  if (row) {
    importForm.assetName = row.assetName
    importForm.modelType = row.modelType
    importForm.modelVersion = row.modelVersion
    importForm.taskType = row.taskType
    importForm.yoloVersion = row.yoloVersion || 'YOLOv10'
    importForm.filePath = row.filePath || row.sourcePath || ''
    importForm.description = row.description || ''
  }
  importDialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    const payload = {
      assetName: form.assetName,
      modelType: form.modelType,
      modelVersion: form.modelVersion,
      taskType: form.taskType,
      yoloVersion: form.yoloVersion,
      filePath: form.filePath,
      description: form.description || undefined
    }
    if (currentEditId.value) {
      await updateModelAssetApi(currentEditId.value, payload)
      ElMessage.success('模型路径登记项已更新')
    } else {
      await createModelAssetApi(payload)
      ElMessage.success('模型路径登记成功')
    }
    editDialogVisible.value = false
    await loadList()
  } catch (error: any) {
    ElMessage.error(error.message || '保存模型路径登记失败')
  } finally {
    submitLoading.value = false
  }
}

async function handleImport() {
  const valid = await importFormRef.value?.validate().catch(() => false)
  if (!valid) return

  importLoading.value = true
  try {
    await importModelAssetApi({
      assetName: importForm.assetName,
      modelType: importForm.modelType,
      modelVersion: importForm.modelVersion,
      taskType: importForm.taskType,
      yoloVersion: importForm.yoloVersion,
      filePath: importForm.filePath,
      description: importForm.description || undefined
    })
    ElMessage.success('正式模型资产导入成功')
    importDialogVisible.value = false
    activeRecordMode.value = 'FORMAL_ASSET'
    query.pageNum = 1
    await loadList()
  } catch (error: any) {
    ElMessage.error(error.message || '导入正式模型资产失败')
  } finally {
    importLoading.value = false
  }
}

function handleUploadFileChange(file: UploadFile, files: UploadFiles) {
  uploadFile.value = (file.raw as File) || null
  uploadFileList.value = files.slice(-1)
}

function handleUploadFileRemove() {
  uploadFile.value = null
}

async function handleUpload() {
  const valid = await uploadFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!uploadFile.value) {
    ElMessage.warning('请先选择需要上传的模型文件')
    return
  }

  uploadLoading.value = true
  uploadPercent.value = 0
  try {
    await uploadModelAssetApi(
      {
        assetName: uploadForm.assetName,
        modelType: uploadForm.modelType,
        modelVersion: uploadForm.modelVersion,
        taskType: uploadForm.taskType,
        yoloVersion: uploadForm.yoloVersion,
        description: uploadForm.description || undefined,
        file: uploadFile.value
      },
      (percent) => {
        uploadPercent.value = percent
      }
    )
    ElMessage.success('正式模型资产上传成功')
    uploadDialogVisible.value = false
    activeRecordMode.value = 'FORMAL_ASSET'
    query.pageNum = 1
    await loadList()
  } catch (error: any) {
    ElMessage.error(error.message || '上传正式模型资产失败')
  } finally {
    uploadLoading.value = false
  }
}

async function handleCheck(id: number) {
  try {
    await checkModelAssetApi(id)
    ElMessage.success('模型路径校验完成')
    await loadList()
  } catch (error: any) {
    ElMessage.error(error.message || '模型路径校验失败')
    await loadList()
  }
}

async function handleDelete(row: ModelAssetItem) {
  try {
    const isFederatedOutput = isServer.value && row.sourceType === 'FEDERATED_OUTPUT'
    const message = isFederatedOutput
      ? h('div', { class: 'federated-delete-message' }, [
          h('p', '确定删除该联邦全局模型吗？'),
          h('p', '删除后：'),
          h('ol', [
            h('li', '模型文件将从服务器磁盘中物理清理；'),
            h('li', '该工作流以后无法再次发起模型验证；'),
            h('li', '已产生的验证指标、识别样例和已保存结果不会删除。')
          ]),
          h('strong', '此操作不可恢复。')
        ])
      : showPathRegistry.value
        ? '删除后仅移除这条路径登记，不会删除你电脑上的文件。确认继续吗？'
        : '删除后该正式模型资产将不再可用；已被工作流引用的资产无法删除。确认继续吗？'
    await ElMessageBox.confirm(message, isFederatedOutput ? '删除联邦全局模型' : '删除确认', {
      type: 'warning'
    })
    await deleteModelAssetApi(row.id)
    ElMessage.success(
      isFederatedOutput
        ? '联邦全局模型已删除，历史验证结果已保留。'
        : showPathRegistry.value
          ? '模型路径登记项已删除'
          : '正式模型资产已删除'
    )
    await loadList()
  } catch (error: any) {
    if (error === 'cancel' || error?.message === 'cancel') return
    ElMessage.error(error.message || (showPathRegistry.value ? '删除模型路径登记项失败' : '删除正式模型资产失败'))
  }
}

async function copyPath(row: ModelAssetItem) {
  const text = resolveDisplayPath(row)
  if (text === '-') {
    ElMessage.warning('当前资产没有可复制的路径')
    return
  }
  await navigator.clipboard.writeText(text)
  ElMessage.success('路径已复制')
}

function goToValidation(row: ModelAssetItem) {
  router.push({
    name: isServer.value ? 'server-validation' : 'client-validation',
    query: {
      modelRegistryId: String(row.id),
      modelName: row.assetName
    }
  })
}

function goToWorkflow(row: ModelAssetItem) {
  router.push({
    name: 'client-workflows',
    query: {
      registryModelId: String(row.id)
    }
  })
}

onMounted(async () => {
  await Promise.all([loadRuntimeProfile(), loadList()])
})
</script>

<style scoped>
.page {
  padding: 16px;
}

.panel-card {
  border-radius: 20px;
}

.hero {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}

.hero-tags {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.page-title {
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
}

.page-desc {
  margin-top: 6px;
  color: #475569;
  line-height: 1.7;
  max-width: 760px;
}

.intro-alert {
  margin-bottom: 16px;
}

.record-mode-switch {
  margin-bottom: 16px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.toolbar-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.subtle-text {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
  overflow-wrap: anywhere;
}

.asset-table :deep(.el-button + .el-button) {
  margin-left: 0;
}

.asset-table :deep(.cell) {
  min-height: 28px;
}

@media (max-width: 768px) {
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
