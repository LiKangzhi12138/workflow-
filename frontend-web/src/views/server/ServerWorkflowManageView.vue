<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="toolbar">
          <div class="title-wrap">
            <div class="page-title">服务器端管理工作流</div>
            <div class="page-desc">本页只负责查看与跳转，真实流程推进请统一在工作流进度页完成。</div>
          </div>

          <div class="toolbar-actions">
            <el-select
              v-model="query.status"
              clearable
              placeholder="按状态筛选"
              style="width: 180px"
              @change="handleSearch"
            >
              <el-option
                v-for="item in statusOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>

            <el-button @click="loadList">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="tableData" border>
        <el-table-column prop="workflowCode" label="工作流编码" min-width="180" show-overflow-tooltip />
        <el-table-column prop="workflowName" label="工作流名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="模型定义" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            {{ getWorkflowModelDisplayName(row) }}
          </template>
        </el-table-column>
        <el-table-column prop="initiatorUsername" label="客户端用户" min-width="140" />
        <el-table-column prop="clientModelAssetName" label="绑定模型" min-width="180" show-overflow-tooltip />
        <el-table-column prop="serverDatasetAssetName" label="服务端数据集" min-width="180" show-overflow-tooltip />
        <el-table-column prop="serverDatasetDataFormat" label="数据格式" width="110" />
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="getStatusTagType(row.status)">
              {{ getStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="currentStep" label="当前步骤" min-width="180" />
        <el-table-column label="进度" width="180">
          <template #default="{ row }">
            <el-progress :percentage="row.progress || 0" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-space wrap>
              <el-button link type="primary" @click="openProgress(row.id)">进度页</el-button>
              <el-button link type="primary" @click="openDetail(row.id)">查看详情</el-button>
              <el-button link type="primary" @click="openValidation(row.id)">验证页</el-button>
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

    <el-drawer
      v-model="detailDrawerVisible"
      title="工作流详情"
      size="860px"
      destroy-on-close
    >
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border class="workflow-detail-descriptions">
            <el-descriptions-item label="工作流编码">
              {{ detail.workflowCode }}
            </el-descriptions-item>
            <el-descriptions-item label="工作流名称">
              {{ detail.workflowName }}
            </el-descriptions-item>
            <el-descriptions-item label="客户端用户">
              {{ detail.initiatorUsername }}
            </el-descriptions-item>
            <el-descriptions-item label="服务端用户">
              {{ detail.serverUsername }}
            </el-descriptions-item>
            <el-descriptions-item label="绑定模型">
              {{ detail.clientModelAssetName || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="模型版本">
              {{ detail.clientModelVersion || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="模型定义">
              {{ getWorkflowModelDisplayName(detail) }}
            </el-descriptions-item>
            <el-descriptions-item label="服务端数据集">
              {{ detail.serverDatasetAssetName || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="数据格式">
              {{ detail.serverDatasetDataFormat || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="任务类型">
              {{ detail.serverDatasetTaskType || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="客户端模型数量">
              {{ detail.clientModelCount ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="公开状态">
              {{ detail.isPublic === 1 ? '公开' : '私有' }}
            </el-descriptions-item>
            <el-descriptions-item label="差分隐私">
              {{ formatMechanismEnabled(detail.dpEnabled) }} / {{ getPrivacyStatusLabel(detail.dpStatus) }}
            </el-descriptions-item>
            <el-descriptions-item label="DP 参数">
              ε={{ detail.dpEpsilon ?? '-' }}，δ={{ detail.dpDelta ?? '-' }}，裁剪={{ detail.dpClipNorm ?? '-' }}，噪声={{ detail.dpNoiseMultiplier ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="安全洗牌">
              {{ formatMechanismEnabled(detail.shuffleEnabled) }} / {{ getPrivacyStatusLabel(detail.shuffleStatus) }}
            </el-descriptions-item>
            <el-descriptions-item label="洗牌批次">
              {{ detail.shuffleBatchNo || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="安全聚合">
              {{ formatMechanismEnabled(detail.secureAggregationEnabled) }} / {{ getPrivacyStatusLabel(detail.secureAggregationStatus) }}
            </el-descriptions-item>
            <el-descriptions-item label="聚合模式">
              {{ detail.secureAggregationMode || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="隐私保护摘要" :span="2">
              {{ detail.dpSummary || '-' }}；{{ detail.shuffleOrderSummary || '-' }}；{{ detail.secureAggregationSummary || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="getStatusTagType(detail.status)">
                {{ getStatusLabel(detail.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="当前步骤">
              {{ detail.currentStep }}
            </el-descriptions-item>
            <el-descriptions-item label="进度">
              <el-progress :percentage="detail.progress || 0" />
            </el-descriptions-item>
            <el-descriptions-item label="Python Job ID">
              {{ detail.pythonJobId || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="备注" :span="2">
              {{ detail.remark || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="错误信息" :span="2">
              {{ detail.errorMessage || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="结果文件" :span="2">
              {{ detail.resultFilePath || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ formatDateTime(detail.createdAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ formatDateTime(detail.updatedAt) }}
            </el-descriptions-item>
          </el-descriptions>

          <div class="section-title">步骤记录</div>
          <el-timeline>
            <el-timeline-item
              v-for="step in detail.steps"
              :key="`${step.stepNo}-${step.createdAt}`"
              :timestamp="formatDateTime(step.createdAt)"
              placement="top"
            >
              <div class="step-title">
                {{ step.stepNo }}. {{ step.stepName }}
              </div>
              <div class="step-text">步骤编码：{{ step.stepCode }}</div>
              <div class="step-text">状态流转：{{ step.fromStatus || '-' }} -> {{ step.toStatus }}</div>
              <div class="step-text">
                操作角色：{{ step.operatorRole || '-' }} / 操作人ID：{{ step.operatorUserId || '-' }}
              </div>
              <div class="step-text">说明：{{ step.message || '-' }}</div>
            </el-timeline-item>
          </el-timeline>

          <div class="section-title">指标信息</div>
          <el-input
            :model-value="formatMetrics(detail.metricsJson)"
            type="textarea"
            :rows="10"
            readonly
          />
        </template>

        <el-empty v-else description="暂无详情数据" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { TagProps } from 'element-plus'
import {
  getWorkflowDetailApi,
  listWorkflowsApi,
  type WorkflowDetail,
  type WorkflowListItem,
  type WorkflowStatus
} from '@/api/workflow'
import { formatMechanismEnabled, getPrivacyStatusLabel } from '@/constants/workflowProgress'
import { resolveValidPage } from '@/utils/pagination'
import { getWorkflowModelDisplayName } from '@/utils/workflowModelDisplay'

const router = useRouter()

const loading = ref(false)
const detailLoading = ref(false)
const tableData = ref<WorkflowListItem[]>([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  status: ''
})

const statusOptions = [
  { label: '已创建', value: 'CREATED' },
  { label: '已接收', value: 'ACCEPTED' },
  { label: '准备中', value: 'PREPARING' },
  { label: '联邦训练中', value: 'TRAINING_RUNNING' },
  { label: '验证中', value: 'VALIDATING' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '失败', value: 'FAILED' },
  { label: '已撤回', value: 'WITHDRAWN' }
]

const statusLabelMap: Record<WorkflowStatus, string> = {
  CREATED: '已创建',
  ACCEPTED: '已接收',
  PREPARING: '准备中',
  TRAINING_RUNNING: '联邦训练中',
  VALIDATING: '验证中',
  COMPLETED: '已完成',
  FAILED: '失败',
  WITHDRAWN: '已撤回'
}

const detailDrawerVisible = ref(false)
const detail = ref<WorkflowDetail | null>(null)

function getStatusTagType(status?: string): TagProps['type'] {
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

function getStatusLabel(status?: string) {
  if (!status) return '-'
  return statusLabelMap[status as WorkflowStatus] || status
}

function formatDateTime(value?: string) {
  if (!value) return '-'
  return value.replace('T', ' ')
}

function formatMetrics(metricsJson?: string) {
  if (!metricsJson) return '-'
  try {
    return JSON.stringify(JSON.parse(metricsJson), null, 2)
  } catch {
    return metricsJson
  }
}

async function loadList() {
  loading.value = true
  try {
    const res = await listWorkflowsApi({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status || undefined
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
    ElMessage.error(error.message || '加载工作流列表失败')
  } finally {
    loading.value = false
  }
}

async function openDetail(id: number) {
  detailDrawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const res = await getWorkflowDetailApi(id)
    detail.value = res.data
  } catch (error: any) {
    ElMessage.error(error.message || '加载工作流详情失败')
  } finally {
    detailLoading.value = false
  }
}

function openProgress(id: number) {
  router.push({
    name: 'server-progress',
    query: { id: String(id) }
  })
}

function openValidation(id: number) {
  router.push({
    name: 'server-validation',
    query: { workflowId: String(id) }
  })
}

function handleSearch() {
  query.pageNum = 1
  loadList()
}

function handlePageSizeChange() {
  query.pageNum = 1
  loadList()
}

onMounted(() => {
  console.info('[server-workflows] retained actions on manage page', {
    actions: ['progress', 'detail', 'validation']
  })
  loadList()
})
</script>

<style scoped>
.page {
  padding: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.title-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.page-desc {
  font-size: 13px;
  color: #909399;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.section-title {
  margin: 24px 0 12px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.step-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 8px;
}

.step-text {
  color: #606266;
  line-height: 1.8;
  overflow-wrap: anywhere;
}

.workflow-detail-descriptions :deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}

.workflow-detail-descriptions :deep(.el-progress) {
  min-width: 180px;
}

:deep(.el-table .el-button + .el-button) {
  margin-left: 0;
}
</style>
