<template>
  <div class="overview-page" v-loading="loading">
    <section class="hero">
      <div class="hero-copy">
        <div class="hero-eyebrow">{{ roleLabel }}总览</div>
        <div class="hero-title">{{ SYSTEM_NAME_FULL }}</div>
        <div class="hero-desc">{{ roleDescription }}</div>
        <div class="hero-meta">
          <el-tag size="large" type="primary">{{ roleLabel }}</el-tag>
          <span>当前用户：{{ user?.username || '未命名用户' }}</span>
          <span>首页聚合展示工作流、验证结果、模型资产和快捷入口。</span>
        </div>
      </div>

      <div class="hero-score">
        <div class="hero-score-label">最近验证准确率</div>
        <div class="hero-score-value">{{ formatPercent(overview?.metrics?.latestAccuracy) }}</div>
        <div class="hero-score-tip">{{ accuracyTip }}</div>
      </div>
    </section>

    <section class="section-block">
      <div class="section-head">
        <div>
          <div class="section-title">核心指标</div>
          <div class="section-desc">优先展示当前角色最常查看的工作流与验证指标。</div>
        </div>
        <el-button @click="loadOverview">刷新概览</el-button>
      </div>

      <div class="metric-grid">
        <el-card class="metric-card metric-card-primary" shadow="hover">
          <div class="metric-label">最近一次验证准确率</div>
          <div class="metric-value">{{ formatPercent(overview?.metrics?.latestAccuracy) }}</div>
          <div class="metric-tip">{{ accuracyTip }}</div>
        </el-card>

        <el-card v-for="metric in secondaryMetrics" :key="metric.label" class="metric-card" shadow="hover">
          <div class="metric-label">{{ metric.label }}</div>
          <div class="metric-value">{{ metric.value }}</div>
          <div class="metric-tip">{{ metric.tip }}</div>
        </el-card>
      </div>
    </section>

    <section class="section-block">
      <div class="section-head">
        <div>
          <div class="section-title">当前关注</div>
          <div class="section-desc">帮助你快速定位最近工作流和最近验证结果。</div>
        </div>
      </div>

      <div class="content-grid">
        <el-card class="content-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">最近工作流</div>
                <div class="card-desc">{{ latestWorkflowDesc }}</div>
              </div>
              <el-tag v-if="overview?.latestWorkflow" :type="workflowStatusTagType(overview.latestWorkflow.status)">
                {{ workflowStatusLabel(overview.latestWorkflow.status) }}
              </el-tag>
            </div>
          </template>

          <template v-if="overview?.latestWorkflow">
            <div class="entity-title">{{ overview.latestWorkflow.workflowName || '未命名工作流' }}</div>

            <div class="meta-grid">
              <div class="meta-item">
                <span class="meta-label">工作流编号</span>
                <span class="meta-value">{{ overview.latestWorkflow.workflowCode || '--' }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">当前阶段</span>
                <span class="meta-value">{{ overview.latestWorkflow.currentStep || '暂无阶段信息' }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">最近更新时间</span>
                <span class="meta-value">{{ formatDateTime(overview.latestWorkflow.updatedAt) }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">{{ role === 'CLIENT' ? '已选模型资产' : '已绑数据集资产' }}</span>
                <span class="meta-value">
                  {{
                    role === 'CLIENT'
                      ? overview.latestWorkflow.clientModelAssetName || '暂无模型资产'
                      : overview.latestWorkflow.serverDatasetAssetName || '暂无数据集资产'
                  }}
                </span>
              </div>
            </div>

            <div class="status-strip">
              <div class="status-chip" :class="{ active: Boolean(overview.latestWorkflow.receivedByServer) }">
                {{ role === 'CLIENT' ? '服务端已接收' : '工作流已接收' }}
              </div>
              <div class="status-chip" :class="{ active: Boolean(overview.latestWorkflow.decryptCompleted) }">
                {{ role === 'CLIENT' ? '模型已纳管' : '解密纳管完成' }}
              </div>
              <div
                class="status-chip"
                :class="{ active: Boolean(overview.latestWorkflow.validationReady) }"
                :title="overview.latestWorkflow.validationReadyReason || ''"
              >
                {{ role === 'CLIENT' ? '验证条件' : '可启动验证' }}
              </div>
            </div>

            <div v-if="role === 'SERVER'" class="workflow-extra">
              <span>已接收模型：{{ overview.latestWorkflow.receivedModelCount ?? 0 }}</span>
              <span>已纳管模型：{{ overview.latestWorkflow.collectedModelCount ?? 0 }}</span>
              <span>目标模型数：{{ overview.latestWorkflow.requiredModelCount ?? 1 }}</span>
              <span
                :class="{ 'unavailable-text': overview.latestWorkflow.federatedModelAvailable === false }"
                :title="overview.latestWorkflow.federatedModelUnavailableReason || ''"
              >
                全局模型：{{ overview.latestWorkflow.federatedModelAvailable ? '可用' : '不可用' }}
              </span>
            </div>
            <div
              v-if="role === 'SERVER' && !overview.latestWorkflow.validationReady"
              class="readiness-note"
            >
              {{ overview.latestWorkflow.validationReadyReason || '请按进度页提示完成数据集绑定、模型纳管和联邦聚合。' }}
            </div>

            <div class="action-row">
              <el-button type="primary" @click="goToWorkflowManage">进入工作流管理</el-button>
              <el-button @click="goToWorkflowProgress(overview.latestWorkflow.id)">查看进度</el-button>
              <el-button @click="goToWorkflowValidation(overview.latestWorkflow.id)">进入验证页</el-button>
            </div>
          </template>

          <el-empty v-else description="暂无工作流">
            <el-button type="primary" @click="goToWorkflowManage">进入工作流管理</el-button>
          </el-empty>
        </el-card>

        <el-card class="content-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">最近验证结果</div>
                <div class="card-desc">优先展示最近一次成功验证，没有成功结果时展示最近任务。</div>
              </div>
              <el-tag v-if="overview?.recentValidation" :type="validationStatusTagType(overview.recentValidation.status)">
                {{ validationStatusLabel(overview.recentValidation.status) }}
              </el-tag>
            </div>
          </template>

          <template v-if="overview?.recentValidation">
            <div class="entity-title">{{ overview.recentValidation.taskName || '最近验证任务' }}</div>
            <div class="validation-source">{{ overview.recentValidation.sourceLabel || '最近验证结果' }}</div>

            <div class="meta-grid">
              <div class="meta-item">
                <span class="meta-label">对应模型</span>
                <span class="meta-value">{{ overview.recentValidation.modelName || '暂无模型信息' }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">数据集名称</span>
                <span class="meta-value">{{ overview.recentValidation.datasetName || '暂无数据集信息' }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">最近更新时间</span>
                <span class="meta-value">{{ formatDateTime(overview.recentValidation.updatedAt) }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">状态</span>
                <span class="meta-value">{{ validationStatusLabel(overview.recentValidation.status) }}</span>
              </div>
            </div>

            <div class="validation-metrics">
              <div class="validation-metric">
                <span class="validation-metric-label">准确率</span>
                <span class="validation-metric-value">{{ formatPercent(overview.recentValidation.accuracy) }}</span>
              </div>
              <div class="validation-metric">
                <span class="validation-metric-label">Precision</span>
                <span class="validation-metric-value">{{ formatPercent(overview.recentValidation.precision) }}</span>
              </div>
              <div class="validation-metric">
                <span class="validation-metric-label">Recall</span>
                <span class="validation-metric-value">{{ formatPercent(overview.recentValidation.recall) }}</span>
              </div>
            </div>

            <div class="action-row">
              <el-button type="primary" @click="goToValidationPage(overview.recentValidation)">进入验证页</el-button>
            </div>
          </template>

          <el-empty v-else description="暂无验证结果">
            <el-button type="primary" @click="goToValidationHome">进入验证页</el-button>
          </el-empty>
        </el-card>
      </div>
    </section>

    <section class="section-block">
      <div class="section-head">
        <div>
          <div class="section-title">资源与入口</div>
          <div class="section-desc">模型资产概览和常用快捷入口放在首页底部，便于快速处理下一步操作。</div>
        </div>
      </div>

      <div class="content-grid content-grid-bottom">
        <el-card class="content-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">模型资产概览</div>
                <div class="card-desc">
                  {{ role === 'CLIENT' ? '查看客户端模型资产。' : '查看服务端模型资产纳管情况。' }}
                </div>
              </div>
            </div>
          </template>

          <div class="model-shell">
            <div class="model-total">
              <span class="model-total-label">模型资产数量</span>
              <span class="model-total-value">{{ overview?.modelOverview?.totalModelCount ?? 0 }}</span>
            </div>

            <template v-if="overview?.modelOverview?.latestModelName">
              <div class="meta-grid">
                <div class="meta-item">
                  <span class="meta-label">最近模型</span>
                  <span class="meta-value">{{ overview.modelOverview.latestModelName }}</span>
                </div>
                <div class="meta-item">
                  <span class="meta-label">模型版本</span>
                  <span class="meta-value">{{ overview.modelOverview.latestModelVersion || '--' }}</span>
                </div>
                <div class="meta-item">
                  <span class="meta-label">{{ role === 'CLIENT' ? '最近上传时间' : '最近纳管时间' }}</span>
                  <span class="meta-value">{{ formatDateTime(overview.modelOverview.latestModelCreatedAt) }}</span>
                </div>
                <div class="meta-item">
                  <span class="meta-label">当前状态</span>
                  <span class="meta-value">{{ formatAssetStatus(overview.modelOverview.latestModelStatus) }}</span>
                </div>
              </div>
            </template>

            <el-empty v-else description="暂无模型资产">
              <el-button type="primary" @click="goToModelManage">进入模型资产管理</el-button>
            </el-empty>

            <div v-if="overview?.modelOverview?.latestModelName" class="action-row">
              <el-button type="primary" @click="goToModelManage">进入模型资产管理</el-button>
            </div>
          </div>
        </el-card>

        <el-card class="content-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">快捷入口</div>
                <div class="card-desc">把最常用的页面放在首页，减少二次跳转。</div>
              </div>
            </div>
          </template>

          <div class="quick-grid">
            <button
              v-for="item in quickActions"
              :key="item.label"
              type="button"
              class="quick-card"
              @click="router.push(item.path)"
            >
              <span class="quick-title">{{ item.label }}</span>
              <span class="quick-desc">{{ item.desc }}</span>
            </button>
          </div>
        </el-card>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getDashboardOverview, type DashboardOverview, type DashboardValidationCard } from '@/api/dashboard'
import { ROLE_LABELS, SYSTEM_NAME_FULL } from '@/constants/system'
import { VALIDATION_STATUS_LABELS } from '@/constants/validation'
import { getLoginUser } from '@/utils/auth'

const props = defineProps<{
  role: 'CLIENT' | 'SERVER'
}>()

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const overview = ref<DashboardOverview | null>(null)
let overviewRequestSeq = 0
const user = computed(() => getLoginUser())
const role = computed(() => props.role)
const basePath = computed(() => (props.role === 'CLIENT' ? '/client' : '/server'))

const roleLabel = computed(() => ROLE_LABELS[props.role] || props.role)

const roleDescription = computed(() =>
  props.role === 'CLIENT'
    ? '面向农业保险场景的联邦学习与隐私保护原型系统，帮助客户端操作者快速掌握我的工作流、最近验证结果与模型资产状态。'
    : '面向农业保险场景的联邦学习与隐私保护原型系统，帮助服务端操作者快速掌握工作流接收、解密纳管、验证分析与联邦学习状态。'
)

const accuracyTip = computed(() => {
  const accuracy = overview.value?.metrics?.latestAccuracy
  if (accuracy == null) {
    return '暂无验证结果，可进入验证页查看或发起新的验证任务。'
  }
  return `取值来源：${overview.value?.metrics?.latestAccuracySourceLabel || '最近一次验证结果'}`
})

const latestWorkflowDesc = computed(() =>
  props.role === 'CLIENT'
    ? '展示当前用户最近一条工作流的状态、阶段与快捷操作。'
    : '展示当前服务端最近接收到的工作流，以及接收、解密、验证等处理情况。'
)

const secondaryMetrics = computed(() => {
  const metrics = overview.value?.metrics
  if (props.role === 'CLIENT') {
    return [
      {
        label: '我的工作流总数',
        value: formatCount(metrics?.totalWorkflows),
        tip: '当前客户端账号下的全部工作流数量。'
      },
      {
        label: '待服务端接收',
        value: formatCount(metrics?.pendingServerReceiveCount),
        tip: '状态仍为已创建、尚未被服务端接收的工作流数量。'
      },
      {
        label: '已完成验证数',
        value: formatCount(metrics?.completedValidationCount),
        tip: '包含工作流验证和独立验证中已完成的任务。'
      }
    ]
  }

  return [
    {
      label: '待接收工作流',
      value: formatCount(metrics?.pendingReceiveWorkflowCount),
      tip: '等待服务端接收的工作流数量。'
    },
    {
      label: '待解密 / 待纳管',
      value: formatCount(metrics?.pendingDecryptCount),
      tip: '已收到模型但仍需完成解密或纳管的工作流数量。'
    },
    {
      label: '待验证工作流',
      value: formatCount(metrics?.pendingValidationCount),
      tip: '模型纳管和数据集绑定已完成、可以进入验证阶段的工作流数量。'
    },
    {
      label: '已完成验证数',
      value: formatCount(metrics?.completedValidationCount),
      tip: '包含工作流验证和独立验证中已完成的任务。'
    }
  ]
})

const quickActions = computed(() => [
  { label: '工作流管理', desc: '查看与管理工作流列表。', path: `${basePath.value}/workflows` },
  { label: '工作流进度', desc: '跟踪当前工作流处理进度。', path: `${basePath.value}/progress` },
  { label: '验证页', desc: '查看验证结果或发起验证。', path: `${basePath.value}/validation` },
  { label: '模型资产管理', desc: '查看模型资产。', path: `${basePath.value}/models` }
])

function workflowStatusLabel(status?: string) {
  return VALIDATION_STATUS_LABELS[status || ''] || status || '未知'
}

function validationStatusLabel(status?: string) {
  return VALIDATION_STATUS_LABELS[status || ''] || status || '未知'
}

function workflowStatusTagType(status?: string) {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'VALIDATING':
    case 'TRAINING_RUNNING':
    case 'PREPARING':
    case 'ACCEPTED':
      return 'warning'
    default:
      return 'info'
  }
}

function validationStatusTagType(status?: string) {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'VALIDATING':
    case 'TRAINING_RUNNING':
    case 'PREPARING':
    case 'INITIATED':
      return 'warning'
    default:
      return 'info'
  }
}

function formatDateTime(value?: string | null) {
  if (!value) return '--'
  try {
    return new Date(value).toLocaleString('zh-CN')
  } catch {
    return value
  }
}

function formatPercent(value?: number | null) {
  if (value == null || Number.isNaN(Number(value))) return '--'
  const normalized = Number(value)
  return `${(normalized * 100).toFixed(2)}%`
}

function formatCount(value?: number | null) {
  if (value == null || Number.isNaN(Number(value))) return '--'
  return `${value}`
}

function formatAssetStatus(status?: string | null) {
  switch (status) {
    case 'READY':
      return '可用'
    case 'FAILED':
      return '失败'
    case 'CREATED':
      return '已创建'
    default:
      return status || '未知'
  }
}

function goToWorkflowManage() {
  router.push(`${basePath.value}/workflows`)
}

function goToWorkflowProgress(workflowId: number) {
  router.push({
    path: `${basePath.value}/progress`,
    query: { id: String(workflowId) }
  })
}

function goToWorkflowValidation(workflowId: number) {
  router.push({
    path: `${basePath.value}/validation`,
    query: { workflowId: String(workflowId) }
  })
}

function goToValidationHome() {
  router.push(`${basePath.value}/validation`)
}

function goToValidationPage(card: DashboardValidationCard) {
  if (card.sourceType === 'WORKFLOW' && card.workflowId) {
    goToWorkflowValidation(card.workflowId)
    return
  }
  goToValidationHome()
}

function goToModelManage() {
  router.push(`${basePath.value}/models`)
}

async function loadOverview() {
  const requestSeq = ++overviewRequestSeq
  loading.value = true
  try {
    const res = await getDashboardOverview()
    if (requestSeq !== overviewRequestSeq) {
      return
    }
    overview.value = res.data
    if (import.meta.env.DEV) {
      console.debug('[dashboard][overview]', {
        latestAccuracy: res.data?.metrics?.latestAccuracy,
        latestAccuracySourceType: res.data?.metrics?.latestAccuracySourceType,
        latestAccuracySourceLabel: res.data?.metrics?.latestAccuracySourceLabel,
        recentValidationId: res.data?.recentValidation?.validationId,
        recentWorkflowId: res.data?.recentValidation?.workflowId,
        recentValidationUpdatedAt: res.data?.recentValidation?.updatedAt
      })
    }
  } catch (error: any) {
    ElMessage.error(error.message || '加载概览失败')
  } finally {
    if (requestSeq === overviewRequestSeq) {
      loading.value = false
    }
  }
}

onMounted(() => {
  loadOverview()
})

onActivated(() => {
  loadOverview()
})

watch(
  () => route.fullPath,
  () => {
    loadOverview()
  }
)
</script>

<style scoped>
.overview-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1.8fr) minmax(280px, 0.8fr);
  gap: 20px;
  padding: 28px;
  border-radius: 8px;
  background:
    radial-gradient(circle at top right, rgba(34, 197, 94, 0.14), transparent 34%),
    linear-gradient(135deg, #f8fafc 0%, #eef6ff 46%, #fff7ed 100%);
  border: 1px solid rgba(148, 163, 184, 0.24);
}

.hero-eyebrow {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.12em;
  color: #0369a1;
  text-transform: uppercase;
}

.hero-title {
  margin-top: 10px;
  font-size: 32px;
  line-height: 1.25;
  font-weight: 800;
  color: #0f172a;
}

.hero-desc {
  margin-top: 12px;
  line-height: 1.8;
  color: #334155;
  max-width: 760px;
  overflow-wrap: anywhere;
}

.hero-meta {
  margin-top: 16px;
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  color: #475569;
  font-size: 14px;
}

.hero-score {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 24px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.76);
  border: 1px solid rgba(148, 163, 184, 0.2);
}

.hero-score-label {
  font-size: 14px;
  color: #64748b;
}

.hero-score-value {
  margin-top: 10px;
  font-size: 44px;
  font-weight: 800;
  color: #0f172a;
}

.hero-score-tip {
  margin-top: 10px;
  color: #475569;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.section-block {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}

.section-title {
  font-size: 22px;
  font-weight: 800;
  color: #0f172a;
}

.section-desc {
  margin-top: 6px;
  color: #64748b;
  line-height: 1.7;
}

.metric-grid,
.content-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.metric-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.metric-card,
.content-card {
  border-radius: 8px;
}

.metric-card-primary {
  background: linear-gradient(145deg, #0f172a 0%, #1d4ed8 100%);
  color: #fff;
}

.metric-label {
  font-size: 14px;
  color: inherit;
  opacity: 0.88;
}

.metric-value {
  margin-top: 10px;
  font-size: 30px;
  font-weight: 800;
  color: inherit;
}

.metric-tip {
  margin-top: 10px;
  line-height: 1.7;
  color: inherit;
  opacity: 0.84;
  overflow-wrap: anywhere;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.card-title {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.card-desc {
  margin-top: 6px;
  color: #64748b;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.entity-title {
  font-size: 20px;
  font-weight: 700;
  color: #111827;
  overflow-wrap: anywhere;
}

.validation-source {
  margin-top: 6px;
  color: #64748b;
}

.meta-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px;
  border-radius: 8px;
  background: #f8fafc;
}

.meta-label {
  font-size: 13px;
  color: #64748b;
}

.meta-value {
  color: #0f172a;
  font-weight: 600;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.status-strip,
.workflow-extra,
.action-row {
  margin-top: 16px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.readiness-note {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  color: #9a3412;
  line-height: 1.6;
  word-break: break-word;
}

.unavailable-text {
  color: #b42318;
  font-weight: 600;
}

.status-chip {
  padding: 8px 12px;
  border-radius: 999px;
  background: #e2e8f0;
  color: #475569;
  font-size: 13px;
}

.status-chip.active {
  background: #dcfce7;
  color: #166534;
}

.validation-metrics {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.validation-metric {
  padding: 14px;
  border-radius: 8px;
  background: #eff6ff;
}

.validation-metric-label {
  display: block;
  color: #64748b;
  font-size: 13px;
}

.validation-metric-value {
  margin-top: 8px;
  display: block;
  font-size: 24px;
  font-weight: 800;
  color: #0f172a;
}

.model-shell {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.model-total {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.model-total-label {
  color: #64748b;
}

.model-total-value {
  font-size: 30px;
  font-weight: 800;
  color: #0f172a;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.quick-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 18px;
  border-radius: 8px;
  border: 1px solid #dbeafe;
  background: linear-gradient(180deg, #f8fbff 0%, #eff6ff 100%);
  text-align: left;
  cursor: pointer;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease;
}

.quick-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 28px rgba(30, 64, 175, 0.12);
}

.quick-title {
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
}

.quick-desc {
  color: #64748b;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.action-row :deep(.el-button + .el-button) {
  margin-left: 0;
}

@media (max-width: 1200px) {
  .hero,
  .metric-grid,
  .content-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .meta-grid,
  .validation-metrics,
  .quick-grid {
    grid-template-columns: 1fr;
  }

  .hero {
    padding: 20px;
  }
}
</style>
