<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="toolbar">
          <div class="title-wrap">
            <div class="page-title">客户端管理工作流</div>
            <div class="page-desc">创建、查看、撤回自己的工作流，并通过进度页完成模型上传与状态跟踪</div>
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
            <el-button type="primary" @click="openCreateDialog">新建工作流</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="tableData" border row-key="id">
        <el-table-column prop="workflowCode" label="工作流编码" min-width="180" show-overflow-tooltip />
        <el-table-column prop="workflowName" label="工作流名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="模型定义" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            {{ getWorkflowModelDisplayName(row) }}
          </template>
        </el-table-column>
        <el-table-column prop="clientModelAssetName" label="绑定模型" min-width="180" show-overflow-tooltip />
        <el-table-column prop="serverDatasetAssetName" label="服务端数据集" min-width="180" show-overflow-tooltip />
        <el-table-column prop="serverDatasetDataFormat" label="数据格式" width="110" />
        <el-table-column prop="serverUsername" label="服务端用户" min-width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="getStatusTagType(row.status)">
              {{ getStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="currentStep" label="当前步骤" min-width="140" />
        <el-table-column label="进度" width="180">
          <template #default="{ row }">
            <el-progress :percentage="row.progress || 0" />
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
        <el-table-column label="创建时间" min-width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-space wrap>
              <el-button link type="primary" @click="openDetail(row.id)">查看详情</el-button>
              <el-button link type="info" @click="goToProgress(row)">进度页</el-button>
              <el-button
                v-if="canOpenValidation(row)"
                link
                type="warning"
                @click="goToValidation(row)"
              >
                验证页
              </el-button>
              <el-button
                v-if="canWithdraw(row.status)"
                link
                type="danger"
                @click="handleWithdraw(row)"
              >
                撤回
              </el-button>
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
      v-model="createDialogVisible"
      title="新建工作流"
      width="820px"
      destroy-on-close
      @closed="resetCreateForm"
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="140px"
      >
        <el-form-item label="工作流名称" prop="workflowName">
          <el-input
            v-model="createForm.workflowName"
            placeholder="请输入工作流名称"
            maxlength="128"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="服务端用户" prop="serverUserId">
          <el-select
            v-model="createForm.serverUserId"
            placeholder="请选择服务端用户"
            style="width: 100%"
            filterable
            :disabled="serverUserOptions.length === 0"
            no-data-text="暂无可用服务端用户"
          >
            <el-option
              v-for="item in serverUserOptions"
              :key="item.id"
              :label="item.label || item.displayName || item.username"
              :value="item.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="客户端模型" prop="clientModelAssetId">
          <el-select
            v-model="createForm.clientModelAssetId"
            placeholder="请选择自己的模型"
            style="width: 100%"
            filterable
          >
            <el-option
              v-for="item in modelOptions"
              :key="item.id"
              :label="`${item.assetName}（${item.modelVersion}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="客户端模型数量" prop="clientModelCount">
          <el-input-number
            v-model="createForm.clientModelCount"
            :min="1"
            :max="99"
            style="width: 100%"
            @change="onModelCountChange"
          />
          <span v-if="createForm.clientModelCount > 1" style="color: #409eff; margin-left: 8px; font-size: 12px">
            多模型工作流将自动设为公开
          </span>
        </el-form-item>

        <template v-if="modelSelectionMode === 'definition'">
          <el-form-item v-if="availableModelsLoading">
            <el-alert
              title="正在加载服务器可用模型"
              type="info"
              :closable="false"
              show-icon
            />
          </el-form-item>

          <el-form-item
            v-if="!availableModelsLoading && minimalModelSelection.family.options.length > 0"
            label="模型类型"
            prop="modelDefinitionId"
          >
            <span
              v-if="minimalModelSelection.family.kind === 'static'"
              class="model-selection-value"
            >
              {{ minimalModelSelection.family.value }}
            </span>
            <el-select
              v-else
              :model-value="minimalModelSelection.family.value"
              placeholder="请选择模型类型"
              style="width: 100%"
              @change="selectModelFamily"
            >
              <el-option
                v-for="option in minimalModelSelection.family.options"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item v-if="minimalModelSelection.version" label="模型版本">
            <span
              v-if="minimalModelSelection.version.kind === 'static'"
              class="model-selection-value"
            >
              {{ minimalModelSelection.version.value }}
            </span>
            <el-select
              v-else
              :model-value="minimalModelSelection.version.value"
              placeholder="请选择模型版本"
              style="width: 100%"
              @change="selectModelVersion"
            >
              <el-option
                v-for="option in minimalModelSelection.version.options"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item v-if="minimalModelSelection.variant" label="模型规格">
            <el-select
              :model-value="minimalModelSelection.variant.value"
              placeholder="请选择模型规格"
              style="width: 100%"
              @change="selectModelVariant"
            >
              <el-option
                v-for="option in minimalModelSelection.variant.options"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item v-if="minimalModelSelection.taskType" label="任务类型">
            <el-select
              :model-value="minimalModelSelection.taskType.value"
              placeholder="请选择任务类型"
              style="width: 100%"
              @change="selectModelTaskType"
            >
              <el-option
                v-for="option in minimalModelSelection.taskType.options"
                :key="option.value"
                :label="formatTaskType(option.label)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item v-if="minimalModelSelection.definition" label="模型">
            <el-select
              :model-value="minimalModelSelection.definition.value"
              placeholder="请选择模型"
              style="width: 100%"
              filterable
              @change="selectExactModelDefinition"
            >
              <el-option
                v-for="option in minimalModelSelection.definition.options"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item v-if="!availableModelsLoading && availableModelDefinitions.length === 0">
            <el-alert
              title="当前服务器暂无可用模型"
              type="warning"
              :closable="false"
              show-icon
            />
          </el-form-item>
        </template>

        <el-form-item v-else-if="modelSelectionMode === 'legacy'" label="YOLO版本" prop="yoloVersion">
          <el-select v-model="createForm.yoloVersion" placeholder="请选择YOLO版本" style="width: 100%">
            <el-option label="YOLOv8" value="YOLOv8" />
            <el-option label="YOLOv10" value="YOLOv10" />
            <el-option label="YOLOv11" value="YOLOv11" />
          </el-select>
        </el-form-item>

        <el-form-item v-else>
          <el-alert
            :title="modelSelectionMessage"
            :type="modelSelectionMode === 'error' ? 'error' : 'info'"
            :closable="false"
            show-icon
          />
        </el-form-item>

        <el-form-item label="是否公开">
          <el-switch
            v-model="isPublicSwitch"
            active-text="公开"
            inactive-text="私有"
          />
        </el-form-item>

        <el-divider content-position="left">隐私计算与联邦聚合配置</el-divider>

        <el-form-item label="差分隐私">
          <el-switch v-model="createForm.dpEnabled" active-text="启用" inactive-text="未启用" />
        </el-form-item>

        <template v-if="createForm.dpEnabled">
          <el-form-item label="隐私预算 ε">
            <el-input-number v-model="createForm.dpEpsilon" :min="0.000001" :precision="6" :step="0.1" style="width: 100%" />
          </el-form-item>
          <el-form-item label="失效概率 δ">
            <el-input-number v-model="createForm.dpDelta" :min="0.000000000001" :precision="12" :step="0.000001" style="width: 100%" />
          </el-form-item>
          <el-form-item label="裁剪阈值">
            <el-input-number v-model="createForm.dpClipNorm" :min="0.000001" :precision="6" :step="0.1" style="width: 100%" />
          </el-form-item>
          <el-form-item label="噪声系数">
            <el-input-number v-model="createForm.dpNoiseMultiplier" :min="0" :precision="6" :step="0.05" style="width: 100%" />
          </el-form-item>
        </template>

        <el-form-item label="安全洗牌">
          <el-switch v-model="createForm.shuffleEnabled" active-text="启用" inactive-text="未启用" />
        </el-form-item>

        <el-form-item label="安全聚合">
          <el-switch v-model="createForm.secureAggregationEnabled" active-text="安全聚合" inactive-text="普通聚合" />
        </el-form-item>

        <el-form-item label="聚合模式">
          <el-radio-group v-model="createForm.secureAggregationMode" :disabled="!createForm.secureAggregationEnabled">
            <el-radio-button label="PLAIN">普通聚合</el-radio-button>
            <el-radio-button label="SECURE">安全聚合</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="createForm.remark"
            type="textarea"
            :rows="4"
            placeholder="可选，用于补充这次任务说明"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="createSubmitting"
          :disabled="createSubmissionBlocked"
          @click="submitCreate"
        >
          创建
        </el-button>
      </template>
    </el-dialog>

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
            <el-descriptions-item label="发起人">
              {{ detail.initiatorUsername }}
            </el-descriptions-item>
            <el-descriptions-item label="服务端用户">
              {{ detail.serverUsername }}
            </el-descriptions-item>
            <el-descriptions-item label="客户端模型">
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
              <div class="step-text">
                状态流转：{{ step.fromStatus || '-' }} → {{ step.toStatus }}
              </div>
              <div class="step-text">
                操作角色：{{ step.operatorRole || '-' }} / 操作人ID：{{ step.operatorUserId || '-' }}
              </div>
              <div class="step-text">
                说明：{{ step.message || '-' }}
              </div>
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

    <!-- 模型上传对话框 -->
    <el-dialog
      v-model="uploadDialogVisible"
      title="为工作流提交模型"
      width="720px"
      :close-on-click-modal="uploadStep === 0"
    >
      <!-- 模式切换 Tab -->
      <el-tabs v-if="false" v-model="uploadMode" style="margin-bottom: 8px">
        <el-tab-pane label="从已管理的模型中选择" name="local" />
        <el-tab-pane label="上传新的模型文件" name="file" />
      </el-tabs>

      <!-- ===== 模式A：选择已有模型资产 ===== -->
      <div v-if="false">
        <div v-if="myModelAssets.length === 0">
          <el-empty description="你还没有有效的本地模型">
            <el-button type="primary" @click="$router.push('/client/models')">
              去添加模型
            </el-button>
          </el-empty>
        </div>

        <div v-else>
          <el-alert type="info" :closable="false" style="margin-bottom: 16px">
            <template #default>
              选择你在"管理本地模型"页面已添加的模型。系统将直接使用该模型文件，无需重复上传。
            </template>
          </el-alert>

          <el-radio-group v-model="selectedModelAssetId" style="width: 100%">
            <div style="display: flex; flex-direction: column; gap: 8px">
              <div
                v-for="model in myModelAssets"
                :key="model.id"
                style="
                  border: 1px solid #dcdfe6;
                  border-radius: 8px; padding: 12px 16px; cursor: pointer;
                  transition: border-color 0.2s;
                "
                :style="selectedModelAssetId === model.id
                  ? 'border-color: #409eff; background: #ecf5ff'
                  : ''"
                @click="selectedModelAssetId = model.id"
              >
                <el-radio :value="model.id" style="width: 100%">
                  <div style="display: flex; justify-content: space-between; align-items: center">
                    <div>
                      <div style="font-weight: 500; font-size: 14px">{{ model.assetName }}</div>
                      <div style="font-size: 12px; color: #909399; margin-top: 2px">
                        {{ model.filePath }}
                      </div>
                    </div>
                    <div style="text-align: right; flex-shrink: 0; margin-left: 12px">
                      <el-tag size="small" type="success">{{ model.yoloVersion || '未知版本' }}</el-tag>
                      <div style="font-size: 11px; color: #909399; margin-top: 2px">
                        {{ model.fileSize ? (Number(model.fileSize || 0) / 1024 / 1024).toFixed(1) + ' MB' : '' }}
                      </div>
                    </div>
                  </div>
                </el-radio>
              </div>
            </div>
          </el-radio-group>

          <div v-if="uploadError" style="margin-top: 12px">
            <el-alert type="error" :title="uploadError" show-icon :closable="false" />
          </div>
        </div>
      </div>

      <!-- ===== 模式B：上传新文件（原有加密上传流程） ===== -->
      <div v-if="uploadMode === 'file'">
        <!-- step 0: 选文件 -->
        <div v-if="uploadStep === 0">
          <el-alert type="info" :closable="false" style="margin-bottom: 12px">
            <template #default>
              <div>当前工作流状态：{{ currentWorkflowUploadStageText }}</div>
              <div style="margin-top: 4px">
                已接收 {{ currentUploadWorkflow?.receivedModelCount ?? 0 }}/{{ requiredUploadCount(currentUploadWorkflow) }}
                ，已纳管 {{ currentUploadWorkflow?.collectedModelCount ?? 0 }}/{{ requiredUploadCount(currentUploadWorkflow) }}
              </div>
              <div style="margin-top: 6px; color: #909399">
                上传开始后会按“准备上传 -> 洗牌加密 -> 上传模型 -> 等待服务端接收”的阶段流程展示。
              </div>
            </template>
          </el-alert>
          <el-alert
            v-if="replaceExistingMode"
            type="warning"
            :closable="false"
            style="margin-bottom: 12px"
            title="当前是替换上传模式，本次文件会覆盖该工作流之前的客户端上传结果。"
          />
          <el-alert
            type="warning"
            :closable="false"
            style="margin-bottom: 12px"
            title="这里展示的是阶段型进度，不代表真实字节级加密进度。上传完成后可进入工作流进度页查看服务端反洗牌解密状态。"
          />
        </div>

        <div v-if="false && uploadStep === 0">
          <el-alert type="info" :closable="false" style="margin-bottom: 12px">
            <template #default>
              <div>当前状态：{{ currentWorkflowUploadStageText }}</div>
              <div style="margin-top: 4px">
                已接收 {{ currentUploadWorkflow?.receivedModelCount ?? 0 }}/{{ requiredUploadCount(currentUploadWorkflow) }}，
                已解密 {{ currentUploadWorkflow?.collectedModelCount ?? 0 }}/{{ requiredUploadCount(currentUploadWorkflow) }}
              </div>
            </template>
          </el-alert>
          <el-alert v-if="replaceExistingMode" type="warning" :closable="false" style="margin-bottom: 12px">
            <template #default>
              本次会显式替换当前工作流已有的有效上传记录，旧记录不会再参与 ACCEPT 或后续验证。
            </template>
          </el-alert>
          <el-alert type="warning" :closable="false" style="margin-bottom: 12px">
            <template #default>
              上传新文件会在浏览器端加密后传输，适用于需要跨网络传输的场景。
              如果模型文件已在服务器本地，推荐使用左侧"选择已有模型"方式。
            </template>
          </el-alert>
          <el-form label-position="top">
            <el-form-item label="第一步：从模型管理中选择一个已有模型资产（可选）">
              <el-select
                v-model="selectedModelAssetId"
                placeholder="选择用于预填和关联的模型资产"
                style="width: 100%"
                clearable
                filterable
              >
                <el-option
                  v-for="model in myModelAssets"
                  :key="model.id"
                  :label="`${model.assetName} (${model.yoloVersion || model.modelVersion || 'UNKNOWN'})`"
                  :value="model.id"
                />
              </el-select>
              <div class="upload-tip">
                这里只用于预填和关联 `model_asset_id`，浏览器仍必须重新选择真实文件后才能上传。
              </div>
            </el-form-item>
          </el-form>
          <el-upload
            drag
            :auto-upload="false"
            :accept="uploadContract.uploadProtocol === 'WEIGHTS_V1' ? '.pt' : '.pt,.weights,.onnx'"
            :limit="1"
            :on-change="handleFileChange"
          >
            <el-icon style="font-size: 40px; color: #409eff"><Upload /></el-icon>
            <div style="margin-top: 8px; font-size: 14px">
              {{ uploadContract.uploadProtocol === 'WEIGHTS_V1'
                ? '拖拽或点击选择 weights-only 文件'
                : '拖拽或点击选择 YOLO 模型文件' }}
            </div>
            <template #tip>
              <div style="color: #909399; font-size: 12px">
                {{ uploadContract.uploadProtocol === 'WEIGHTS_V1'
                  ? '仅接受 packaging tool 生成的 weights.pt，最大 500MB'
                  : '支持 .pt / .weights / .onnx，最大 500MB' }}
              </div>
            </template>
          </el-upload>
          <div v-if="uploadContract.uploadProtocol === 'WEIGHTS_V1'" class="upload-tip" style="margin-top: 12px">
            <div>当前工作流仅接受 packaging tool 生成的 weights-only 包。</div>
            <label>Manifest <input type="file" accept=".json,application/json" @change="handleManifestFileChange" /></label>
            <label style="margin-left: 16px">Descriptor <input type="file" accept=".json,application/json" @change="handleDescriptorFileChange" /></label>
          </div>
          <div v-if="uploadError" style="margin-top: 8px">
            <el-alert type="error" :title="uploadError" show-icon :closable="false" />
          </div>
        </div>

        <!-- step 1~4: 加密上传进度 -->
        <div v-else-if="uploadStep > 0 && uploadStep < 5" style="padding: 16px 0">
          <el-progress
            :percentage="uploadDialogStagePercent"
            :stroke-width="12"
            style="margin-bottom: 16px"
          />
          <el-steps :active="uploadStep" finish-status="success" style="margin-bottom: 20px">
            <el-step title="准备上传" />
            <el-step title="生成上传令牌" />
            <el-step title="正在进行洗牌加密" />
            <el-step title="正在上传模型" />
          </el-steps>

          <el-alert type="info" :closable="false" style="margin-bottom: 16px">
            <template #default>
              <div style="font-weight: 600">{{ uploadDialogStageLabel }}</div>
              <div style="margin-top: 4px">{{ uploadDialogStageDescription }}</div>
            </template>
          </el-alert>

          <div v-if="uploadStep === 3" style="text-align: center; padding: 8px 0">
            <div
              style="
                display: inline-flex;
                align-items: center;
                gap: 10px;
                background: #f5f7fa;
                border-radius: 10px;
                padding: 10px 18px;
              "
            >
              <span style="font-size: 20px; animation: spin 1.5s linear infinite; display: inline-block">旋转中</span>
              <div>
                <div style="font-size: 13px; font-weight: 500">正在进行洗牌加密</div>
                <div style="font-size: 11px; color: #909399">
                  文件摘要：{{ fileSha256Preview || '计算中...' }}
                </div>
              </div>
            </div>
          </div>

          <div v-if="uploadStep === 4">
            <el-progress :percentage="uploadProgress" :stroke-width="12" striped striped-flow :duration="8" />
            <div style="font-size: 12px; color: #909399; margin-top: 8px; text-align: center">
              当前展示的是阶段型上传进度，用来帮助你感知模型文件已走到哪个业务阶段。
            </div>
          </div>

          <div style="text-align: center; color: #909399; font-size: 13px; margin-top: 12px">
            {{ uploadDialogStageDescription }}
          </div>
        </div>

        <div v-else-if="false && uploadStep > 0 && uploadStep < 5" style="padding: 16px 0">
          <el-steps :active="uploadStep" finish-status="success" style="margin-bottom: 20px">
            <el-step title="校验文件" />
            <el-step title="获取密钥" />
            <el-step title="加密中" />
            <el-step title="上传中" />
          </el-steps>

          <div v-if="uploadStep === 3" style="text-align: center; padding: 8px 0">
            <div style="
              display: inline-flex; align-items: center; gap: 10px;
              background: #f5f7fa;
              border-radius: 10px; padding: 10px 18px;
            ">
              <span style="font-size: 20px; animation: spin 1.5s linear infinite; display: inline-block">🔐</span>
              <div>
                <div style="font-size: 13px; font-weight: 500">正在 AES-256-CBC 加密</div>
                <div style="font-size: 11px; color: #909399">
                  校验码：{{ fileSha256Preview || '计算中...' }}
                </div>
              </div>
            </div>
          </div>

          <div v-if="uploadStep === 4">
            <el-progress
              :percentage="uploadProgress"
              :stroke-width="12"
              striped striped-flow :duration="8"
            />
            <div style="font-size: 12px; color: #909399; margin-top: 8px; text-align: center">
              🔒 传输内容已加密，服务端收到后需手动触发解密
            </div>
          </div>

          <div style="text-align: center; color: #909399; font-size: 13px; margin-top: 12px">
            {{ ['','正在计算文件校验码...','正在获取加密密钥...','正在加密文件内容...','正在上传加密文件...'][uploadStep] }}
          </div>
        </div>

        <!-- step 5: 上传完成 -->
        <div v-else-if="uploadStep === 5" style="text-align: center; padding: 24px">
          <div style="font-size: 36px">完成</div>
          <div style="margin-top: 8px; font-size: 15px; color: #67c23a">
            模型上传完成，等待服务端接收
          </div>
          <div style="font-size: 12px; color: #909399; margin-top: 4px">
            当前已完成洗牌加密与上传，请切换到工作流进度页查看服务端反洗牌解密与模型纳管状态。
          </div>
        </div>

        <div v-else-if="false && uploadStep === 5" style="text-align: center; padding: 24px">
          <div style="font-size: 36px">✅</div>
          <div style="margin-top: 8px; font-size: 15px; color: #67c23a">
            加密文件已上传！
          </div>
          <div style="font-size: 12px; color: #909399; margin-top: 4px">
            请通知服务端管理员在工作流进度页点击"解密文件"完成后续操作
          </div>
        </div>
      </div>

      <!-- 对话框底部按钮 -->
      <template #footer>
        <!-- 模式A底部按钮 -->
        <template v-if="false">
          <el-button @click="uploadDialogVisible = false" :disabled="bindingLocal">取消</el-button>
          <el-button
            type="primary"
            :loading="bindingLocal"
            :disabled="!selectedModelAssetId"
            @click="handleBindLocalModel"
          >
            确认使用此模型
          </el-button>
        </template>

        <!-- 模式B底部按钮 -->
        <template v-else>
          <el-button
            v-if="uploadStep === 0"
            type="primary"
            :disabled="!uploadPackageReady"
            @click="startEncryptedUpload"
          >
            开始洗牌加密并上传
          </el-button>
          <el-button
            v-if="uploadStep === 5"
            @click="uploadDialogVisible = false"
          >
            完成
          </el-button>
          <template v-if="false">
          <el-button
            v-if="uploadStep === 0"
            type="primary"
            :disabled="!uploadPackageReady"
            @click="startEncryptedUpload"
          >
            开始加密上传
          </el-button>
          </template>
          <el-button
            v-if="uploadStep === 5"
            @click="uploadDialogVisible = false"
          >
            关闭
          </el-button>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules, TagProps } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import {
  createWorkflowApi,
  getWorkflowDetailApi,
  listServerUsersApi,
  listWorkflowsApi,
  withdrawWorkflowApi,
  initModelUpload,
  getWorkflowUploadContract,
  uploadEncryptedModelFile,
  bindLocalModelToWorkflow,
  type ServerUserOption,
  type WorkflowDetail,
  type WorkflowListItem,
  type WorkflowStatus,
  type WorkflowUploadContract
} from '@/api/workflow'
import { listAllModelAssetsApi, type ModelAssetItem } from '@/api/model'
import {
  getModelDefinitionRegistryStatusApi,
  listAvailableModelDefinitionsApi,
  type AvailableModelDefinition
} from '@/api/modelDefinition'
import {
  computeUploadDialogPercent,
  formatMechanismEnabled,
  getUploadDialogStageDescription,
  getUploadDialogStageLabel,
  getPrivacyStatusLabel
} from '@/constants/workflowProgress'
import { summarizeUiErrorMessage } from '@/utils/errorMessage'
import {
  buildWorkflowModelSelectionPayload,
  normalizeAvailableModelDefinitions,
  resolveModelSelectionFailure,
  resolveModelSelectionMode,
  type ModelSelectionMode
} from '@/utils/modelDefinitionSelection'
import {
  buildMinimalModelSelectionPlan,
  type MinimalModelSelectionAnswers
} from '@/utils/minimalModelSelection'
import { resolveValidPage } from '@/utils/pagination'
import { getWorkflowModelDisplayName } from '@/utils/workflowModelDisplay'
import {
  appendWeightsProtocolMetadata,
  protocolUploadReady,
  weightsUploadErrorMessage
} from '@/utils/weightsPackageUpload'

const router = useRouter()
const route = useRoute()

const loading = ref(false)
const detailLoading = ref(false)
const createSubmitting = ref(false)

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

const createDialogVisible = ref(false)
const createFormRef = ref<FormInstance>()
const serverUserOptions = ref<ServerUserOption[]>([])
const modelOptions = ref<ModelAssetItem[]>([])
const modelSelectionMode = ref<ModelSelectionMode>('loading')
const availableModelsLoading = ref(false)
const availableModelDefinitions = ref<AvailableModelDefinition[]>([])
const modelSelectionMessage = ref('正在加载服务器可用模型')
const modelSelectionAnswers = reactive<MinimalModelSelectionAnswers>({})

const createForm = reactive({
  workflowName: '',
  serverUserId: undefined as number | undefined,
  clientModelAssetId: undefined as number | undefined,
  clientModelCount: 1,
  modelDefinitionId: undefined as number | undefined,
  yoloVersion: 'YOLOv10',
  isPublic: 0,
  dpEnabled: true,
  dpEpsilon: 1,
  dpDelta: 0.000001,
  dpClipNorm: 1,
  dpNoiseMultiplier: 0,
  shuffleEnabled: true,
  secureAggregationEnabled: true,
  secureAggregationMode: 'SECURE' as 'PLAIN' | 'SECURE',
  remark: ''
})

const isPublicSwitch = computed({
  get: () => createForm.isPublic === 1,
  set: (val: boolean) => {
    createForm.isPublic = val ? 1 : 0
  }
})

const minimalModelSelection = computed(() =>
  buildMinimalModelSelectionPlan(availableModelDefinitions.value, modelSelectionAnswers)
)

const createSubmissionBlocked = computed(() => {
  if (modelSelectionMode.value === 'loading' || modelSelectionMode.value === 'error') return true
  return modelSelectionMode.value === 'definition' && !createForm.modelDefinitionId
})

watch(
  () => minimalModelSelection.value.selectedDefinition?.id,
  (definitionId) => {
    createForm.modelDefinitionId = definitionId
  },
  { immediate: true }
)

watch(
  () => createForm.secureAggregationEnabled,
  (enabled) => {
    createForm.secureAggregationMode = enabled ? 'SECURE' : 'PLAIN'
  }
)

const validateDefinitionSelection = (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
  if (modelSelectionMode.value !== 'definition' || Number(value) > 0) {
    callback()
    return
  }
  callback(new Error('请选择服务器当前可用的模型'))
}

const validateLegacyVersion = (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
  if (modelSelectionMode.value !== 'legacy' || String(value || '').trim()) {
    callback()
    return
  }
  callback(new Error('请选择 YOLO 版本'))
}

const createRules: FormRules = {
  workflowName: [{ required: true, message: '请输入工作流名称', trigger: 'blur' }],
  serverUserId: [{ required: true, message: '请选择服务端用户', trigger: 'change' }],
  clientModelAssetId: [{ required: true, message: '请选择客户端模型', trigger: 'change' }],
  clientModelCount: [{ required: true, message: '请输入客户端模型数量', trigger: 'change' }],
  modelDefinitionId: [{ validator: validateDefinitionSelection, trigger: 'change' }],
  yoloVersion: [{ validator: validateLegacyVersion, trigger: 'change' }]
}

const detailDrawerVisible = ref(false)
const detail = ref<WorkflowDetail | null>(null)
const replaceExistingMode = ref(false)

const currentWorkflowUploadStageText = computed(() => {
  return getStatusLabel(currentUploadWorkflow.value?.status)
})
const uploadDialogStageLabel = computed(() => getUploadDialogStageLabel(uploadStep.value))
const uploadDialogStageDescription = computed(() => getUploadDialogStageDescription(uploadStep.value))
const uploadDialogStagePercent = computed(() =>
  computeUploadDialogPercent(uploadStep.value, uploadProgress.value)
)

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

function canWithdraw(status?: string) {
  return status === 'CREATED' || status === 'ACCEPTED'
}

function canOpenValidation(row: WorkflowListItem) {
  return row.status !== 'WITHDRAWN'
}

function requiredUploadCount(row?: Pick<WorkflowListItem, 'clientModelCount'> | null) {
  const count = Number(row?.clientModelCount || 1)
  return count > 0 ? count : 1
}

function formatDateTime(value?: string) {
  if (!value) return '-'
  return value.replace('T', ' ')
}

function formatTaskType(taskType?: string) {
  const labels: Record<string, string> = {
    DETECTION: '目标检测',
    SEMANTIC_SEGMENTATION: '语义分割'
  }
  return taskType ? labels[taskType] || taskType : '-'
}

function clearDefinitionSelectionAnswers() {
  modelSelectionAnswers.modelFamily = undefined
  modelSelectionAnswers.version = undefined
  modelSelectionAnswers.variant = undefined
  modelSelectionAnswers.taskType = undefined
  modelSelectionAnswers.definitionId = undefined
}

function selectModelFamily(value: string) {
  modelSelectionAnswers.modelFamily = value
  modelSelectionAnswers.version = undefined
  modelSelectionAnswers.variant = undefined
  modelSelectionAnswers.taskType = undefined
  modelSelectionAnswers.definitionId = undefined
}

function selectModelVersion(value: string) {
  modelSelectionAnswers.version = value
  modelSelectionAnswers.variant = undefined
  modelSelectionAnswers.taskType = undefined
  modelSelectionAnswers.definitionId = undefined
}

function selectModelVariant(value: string) {
  modelSelectionAnswers.variant = value
  modelSelectionAnswers.taskType = undefined
  modelSelectionAnswers.definitionId = undefined
}

function selectModelTaskType(value: string) {
  modelSelectionAnswers.taskType = value
  modelSelectionAnswers.definitionId = undefined
}

function selectExactModelDefinition(value: number) {
  modelSelectionAnswers.definitionId = value
}

function formatMetrics(metricsJson?: string) {
  if (!metricsJson) return '-'
  try {
    return JSON.stringify(JSON.parse(metricsJson), null, 2)
  } catch {
    return metricsJson
  }
}

function resetCreateForm() {
  createForm.workflowName = ''
  createForm.serverUserId = undefined
  createForm.clientModelAssetId = undefined
  createForm.clientModelCount = 1
  createForm.modelDefinitionId = undefined
  clearDefinitionSelectionAnswers()
  createForm.yoloVersion = 'YOLOv10'
  createForm.isPublic = 0
  createForm.dpEnabled = true
  createForm.dpEpsilon = 1
  createForm.dpDelta = 0.000001
  createForm.dpClipNorm = 1
  createForm.dpNoiseMultiplier = 0
  createForm.shuffleEnabled = true
  createForm.secureAggregationEnabled = true
  createForm.secureAggregationMode = 'SECURE'
  createForm.remark = ''
  createFormRef.value?.clearValidate()
}

async function loadList() {
  loading.value = true
  try {
    const res = await listWorkflowsApi({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status || undefined
    })

    const pageData: any = res.data ?? res
    const nextRecords = pageData?.records ?? pageData?.rows ?? pageData?.list ?? []
    const nextTotal = Number(pageData?.total ?? pageData?.count ?? nextRecords.length) || 0
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

async function loadServerUsers() {
  try {
    const res = await listServerUsersApi()
    const userData: any = res.data
    serverUserOptions.value = Array.isArray(userData) ? userData : userData?.records || []
    const selectedServerStillExists = serverUserOptions.value.some((item) => item.id === createForm.serverUserId)
    if (!selectedServerStillExists) {
      const onlyServerUser = serverUserOptions.value.length === 1 ? serverUserOptions.value[0] : undefined
      createForm.serverUserId = onlyServerUser ? onlyServerUser.id : undefined
    }
    if (serverUserOptions.value.length === 0) {
      ElMessage.warning('当前没有可分配的服务端用户，请先注册并启用 SERVER 账号')
    }
  } catch (error: any) {
    ElMessage.error(error.message || '加载服务端用户失败')
  }
}

async function loadModelOptions() {
  try {
    modelOptions.value = await listAllModelAssetsApi()
  } catch (error: any) {
    ElMessage.error(error.message || '加载模型列表失败')
  }
}

async function loadAvailableModelDefinitions() {
  availableModelsLoading.value = true
  availableModelDefinitions.value = []
  createForm.modelDefinitionId = undefined
  clearDefinitionSelectionAnswers()
  try {
    const response = await listAvailableModelDefinitionsApi()
    availableModelDefinitions.value = normalizeAvailableModelDefinitions(response.data)
    if (availableModelDefinitions.value.length === 0) {
      modelSelectionMessage.value = '当前服务器暂无可用模型'
    }
  } catch (error: any) {
    const failure = resolveModelSelectionFailure(error.message)
    modelSelectionMode.value = failure.mode
    modelSelectionMessage.value = failure.message
  } finally {
    availableModelsLoading.value = false
  }
}

async function loadModelSelectionMode() {
  modelSelectionMode.value = 'loading'
  modelSelectionMessage.value = '正在加载服务器可用模型'
  availableModelDefinitions.value = []
  try {
    const response = await getModelDefinitionRegistryStatusApi()
    modelSelectionMode.value = resolveModelSelectionMode(response.data?.enabled === true)
    if (modelSelectionMode.value === 'definition') {
      await loadAvailableModelDefinitions()
      return
    }
  } catch (error: any) {
    const failure = resolveModelSelectionFailure(error.message)
    modelSelectionMode.value = failure.mode
    modelSelectionMessage.value = failure.message
  }
}

async function openCreateDialog() {
  createDialogVisible.value = true
  await Promise.all([loadServerUsers(), loadModelOptions(), loadModelSelectionMode()])
}

async function submitCreate() {
  if (createSubmissionBlocked.value) {
    ElMessage.warning(modelSelectionMessage.value || '当前模型选择不可用')
    return
  }
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid) return

  createSubmitting.value = true
  try {
    const modelSelection = buildWorkflowModelSelectionPayload(
      modelSelectionMode.value,
      createForm.modelDefinitionId,
      createForm.yoloVersion
    )
    await createWorkflowApi({
      workflowName: createForm.workflowName,
      serverUserId: Number(createForm.serverUserId),
      clientModelAssetId: Number(createForm.clientModelAssetId),
      clientModelCount: Number(createForm.clientModelCount),
      ...modelSelection,
      isPublic: createForm.isPublic,
      dpEnabled: createForm.dpEnabled,
      dpEpsilon: Number(createForm.dpEpsilon),
      dpDelta: Number(createForm.dpDelta),
      dpClipNorm: Number(createForm.dpClipNorm),
      dpNoiseMultiplier: Number(createForm.dpNoiseMultiplier),
      shuffleEnabled: createForm.shuffleEnabled,
      secureAggregationEnabled: createForm.secureAggregationEnabled,
      secureAggregationMode: createForm.secureAggregationEnabled ? 'SECURE' : 'PLAIN',
      remark: createForm.remark
    })

    ElMessage.success('工作流创建成功')
    createDialogVisible.value = false
    query.pageNum = 1
    await loadList()
  } catch (error: any) {
    ElMessage.error(error.message || '创建工作流失败')
  } finally {
    createSubmitting.value = false
  }
}

async function openDetail(id: number) {
  detailDrawerVisible.value = true
  detailLoading.value = true
  detail.value = null

  try {
    const res = await getWorkflowDetailApi(id)
    detail.value = {
      ...(res.data ?? {}),
      steps: Array.isArray(res.data?.steps) ? res.data.steps : []
    } as WorkflowDetail
  } catch (error: any) {
    ElMessage.error(error.message || '加载详情失败')
  } finally {
    detailLoading.value = false
  }
}

async function handleWithdraw(row: WorkflowListItem) {
  try {
    await ElMessageBox.confirm(
      `确认撤回工作流【${row.workflowName}】吗？`,
      '撤回确认',
      {
        type: 'warning'
      }
    )

    await withdrawWorkflowApi(row.id)
    ElMessage.success('撤回成功')

    await loadList()

    if (detail.value?.id === row.id) {
      await openDetail(row.id)
    }
  } catch (error: any) {
    if (error === 'cancel' || error?.message === 'cancel') return
    ElMessage.error(error.message || '撤回失败')
  }
}

function handleSearch() {
  query.pageNum = 1
  loadList()
}

function handlePageSizeChange() {
  query.pageNum = 1
  loadList()
}

function goToProgress(row: Pick<WorkflowListItem, 'id'>) {
  router.push({
    name: 'client-progress',
    query: { id: String(row.id) }
  })
}

function goToValidation(row: Pick<WorkflowListItem, 'id'>) {
  router.push({
    name: 'client-validation',
    query: { workflowId: String(row.id) }
  })
}

// ===== 模型上传相关逻辑 =====

/** 判断工作流是否可以上传模型 */
const canUploadModel = (row: WorkflowListItem) => {
  const currentCount = Number(row.activeUploadCount || 0)
  return (row.status === 'CREATED' || row.status === 'ACCEPTED') && currentCount < requiredUploadCount(row)
}

const canReplaceUpload = (row: WorkflowListItem) => {
  return (row.status === 'CREATED' || row.status === 'ACCEPTED')
    && (
      Number(row.activeUploadCount || 0) > 0
      || Number(row.receivedModelCount || 0) > 0
      || Number(row.collectedModelCount || 0) > 0
      || !!row.latestUploadStatus
    )
}

/** 模型数量变化时，多模型自动公开 */
const onModelCountChange = (val: number) => {
  if (val > 1) {
    createForm.isPublic = 1
  }
}

// 上传对话框相关状态
const uploadDialogVisible = ref(false)
const currentUploadWorkflow = ref<WorkflowListItem | null>(null)
const uploadFile = ref<File | null>(null)
const uploadStep = ref(0) // 0=选择文件 1=计算SHA256 2=获取密钥 3=加密中 4=上传中 5=完成
const uploadProgress = ref(0)
const uploadError = ref('')
const fileSha256Preview = ref('')
const uploadContract = ref<WorkflowUploadContract>({
  workflowId: 0,
  uploadProtocol: 'LEGACY_CHECKPOINT',
  manifestRequired: false,
  descriptorRequired: false,
  acceptedArtifactType: 'FULL_CHECKPOINT'
})
const uploadManifestFile = ref<File | null>(null)
const uploadDescriptorFile = ref<File | null>(null)
const uploadPackageReady = computed(() => protocolUploadReady(
  uploadContract.value.uploadProtocol,
  uploadFile.value,
  { manifest: uploadManifestFile.value, descriptor: uploadDescriptorFile.value }
))

// 本地模型资产相关
const uploadMode = ref<'local' | 'file'>('local')

function resolveWorkflowUploadErrorMessage(stage: string, error: unknown) {
  const rawMessage =
    error instanceof Error
      ? error.message
      : typeof error === 'string'
        ? error
        : ''

  if (/digest|subtle|importKey|encrypt/i.test(rawMessage)) {
    return '当前浏览器环境不支持原生摘要或加密，系统已自动切换兼容上传模式，请重试。'
  }

  return summarizeUiErrorMessage(rawMessage, `模型${stage}失败，请稍后重试。`)
}
const myModelAssets = ref<ModelAssetItem[]>([])
const selectedModelAssetId = ref<number | null>(null)
const bindingLocal = ref(false)

watch(
  () => uploadStep.value,
  (value, previous) => {
    if (value !== previous) {
      console.info('[workflow-upload] stage switched', {
        workflowId: currentUploadWorkflow.value?.id || null,
        stage: value,
        label: getUploadDialogStageLabel(value),
        uploadPercent: computeUploadDialogPercent(value, uploadProgress.value)
      })
    }
  }
)

const openUploadDialog = async (row: WorkflowListItem, replaceExisting: boolean = false) => {
  currentUploadWorkflow.value = row
  uploadDialogVisible.value = true
  uploadMode.value = 'file'
  replaceExistingMode.value = replaceExisting
  uploadStep.value = 0
  uploadFile.value = null
  uploadProgress.value = 0
  uploadError.value = ''
  fileSha256Preview.value = ''
  uploadManifestFile.value = null
  uploadDescriptorFile.value = null
  selectedModelAssetId.value = row.clientModelAssetId ?? null
  bindingLocal.value = false

  try {
    const response = await getWorkflowUploadContract(row.id)
    uploadContract.value = response.data
  } catch (error) {
    uploadError.value = '无法读取当前工作流上传协议，请稍后重试。'
    return
  }

  // 加载当前用户的模型资产列表（file_path_validated = 1 的）
  try {
    const list = await listAllModelAssetsApi()
    myModelAssets.value = list.filter(
      (model: any) => model.filePathValidated === 1 || model.file_path_validated === 1
    )
  } catch (e) {
    myModelAssets.value = []
  }
}

// 绑定本地模型资产
const handleBindLocalModel = async () => {
  if (!currentUploadWorkflow.value) {
    uploadError.value = '当前工作流不存在，请关闭弹窗后重试'
    return
  }

  if (!selectedModelAssetId.value) {
    ElMessage.warning('请先选择一个模型')
    return
  }
  bindingLocal.value = true
  uploadError.value = ''
  try {
    await bindLocalModelToWorkflow(
      currentUploadWorkflow.value.id,
      selectedModelAssetId.value
    )
    ElMessage.success('模型绑定成功！')
    uploadDialogVisible.value = false
    loadList() // 刷新列表
  } catch (err: any) {
    uploadError.value = err?.response?.data?.message || err?.message || '绑定失败，请重试'
  } finally {
    bindingLocal.value = false
  }
}

const handleFileChange = (uploadFileObj: any) => {
  uploadFile.value = uploadFileObj.raw
}

const handleManifestFileChange = (event: Event) => {
  uploadManifestFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

const handleDescriptorFileChange = (event: Event) => {
  uploadDescriptorFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

const legacyStartEncryptedUpload = async () => {
  if (!uploadFile.value || !currentUploadWorkflow.value) return

  const file = uploadFile.value
  const workflow = currentUploadWorkflow.value
  const modelAssetId = selectedModelAssetId.value ?? workflow.clientModelAssetId
  uploadError.value = ''
  console.info('[workflow-upload] start', {
    workflowId: workflow.id,
    workflowCode: workflow.workflowCode,
    filename: file.name,
    bytes: file.size
  })

  if (!modelAssetId) {
    uploadError.value = '当前工作流未绑定模型资产，无法初始化上传'
    uploadStep.value = 0
    return
  }

  try {
    // Step 1: 计算SHA256
    uploadStep.value = 1
    const { sha256File } = await import('@/utils/crypto')
    const fileSha256 = await sha256File(file)
    fileSha256Preview.value = fileSha256.substring(0, 16) + '...'

    // Step 2: 申请上传（获取AES密钥）
    uploadStep.value = 2
    let initData: any = null
    try {
      console.info('[workflow-upload] initiate request start', {
        workflowId: workflow.id,
        modelAssetId,
        filename: file.name,
        bytes: file.size
      })
      const initResp = await initModelUpload({
        workflowId: workflow.id,
        modelAssetId,
        originalFilename: file.name,
        fileSize: file.size,
        fileSha256,
        replaceExisting: replaceExistingMode.value
      })
      // 响应拦截器已保证 code === 'OK'（否则会 reject）
      initData = initResp?.data
      if (!initData || !initData.uploadId) {
        throw new Error(
          initData?.message ||
          '申请上传失败，服务端未返回上传凭证，请检查工作流状态或网络连接'
        )
      }
      console.info('[workflow-upload] initiate request success', {
        workflowId: workflow.id,
        uploadId: initData.uploadId
      })
    } catch (err: any) {
      // 提取错误消息
      const msg = err?.message || '申请上传失败，请重试'
      console.error('[workflow-upload] initiate request failed', {
        workflowId: workflow.id,
        filename: file.name,
        error: msg
      })
      uploadError.value = msg
      uploadStep.value = 0
      return // 终止后续流程
    }

    const { uploadId, aesKeyBase64, aesIvBase64, uploadToken } = initData

    // Step 3: AES加密
    uploadStep.value = 3
    const { encryptFileAES } = await import('@/utils/crypto')
    const encryptedBuffer = await encryptFileAES(file, aesKeyBase64, aesIvBase64)
    const encryptedBlob = new Blob([encryptedBuffer], { type: 'application/octet-stream' })

    // Step 4: 上传加密文件
    uploadStep.value = 4
    uploadProgress.value = 0
    const formData = new FormData()
    formData.append('file', encryptedBlob, file.name + '.enc')
    console.info('[workflow-upload] encrypted file upload start', {
      workflowId: workflow.id,
      uploadId,
      filename: file.name + '.enc',
      bytes: encryptedBlob.size
    })

    await uploadEncryptedModelFile(uploadId, uploadToken, formData, (pct) => {
      uploadProgress.value = pct
    })
    console.info('[workflow-upload] encrypted file upload success', {
      workflowId: workflow.id,
      uploadId,
      bytes: encryptedBlob.size
    })

    // Step 5: 完成
    uploadStep.value = 5
    ElMessage.success('模型上传成功！')
    setTimeout(() => {
      uploadDialogVisible.value = false
      loadList() // 刷新列表
    }, 1500)
  } catch (err: any) {
    uploadError.value = err?.response?.data?.message || err?.message || '上传失败，请重试'
    console.error('[workflow-upload] failed', {
      workflowId: workflow.id,
      filename: file.name,
      error: uploadError.value
    })
    uploadStep.value = 0
  }
}

const startEncryptedUpload = async () => {
  if (!uploadFile.value || !currentUploadWorkflow.value) return

  const file = uploadFile.value
  const workflow = currentUploadWorkflow.value
  const modelAssetId = selectedModelAssetId.value ?? workflow.clientModelAssetId
  uploadError.value = ''
  console.info('[workflow-upload] start', {
    workflowId: workflow.id,
    workflowCode: workflow.workflowCode,
    filename: file.name,
    bytes: file.size
  })

  if (!modelAssetId) {
    uploadError.value = '当前工作流未绑定模型资产，无法初始化上传。'
    uploadStep.value = 0
    return
  }

  let currentStage = '摘要初始化'
  let compatibilityModeNotified = false

  try {
    const { getWorkflowCryptoDiagnostics, prepareWorkflowUploadPayload, sha256File } = await import('@/utils/crypto')
    const cryptoDiagnostics = getWorkflowCryptoDiagnostics()
    let clientCryptoMode: 'WEB_CRYPTO' | 'SERVER_COMPAT' = cryptoDiagnostics.preferredMode
    let fileSha256: string | undefined

    uploadStep.value = 1
    console.info('[workflow-upload] crypto environment', {
      workflowId: workflow.id,
      filename: file.name,
      ...cryptoDiagnostics
    })

    if (clientCryptoMode === 'WEB_CRYPTO') {
      try {
        fileSha256 = await sha256File(file)
        fileSha256Preview.value = fileSha256.substring(0, 16) + '...'
      } catch (error) {
        clientCryptoMode = 'SERVER_COMPAT'
        fileSha256Preview.value = '兼容模式'
        console.warn('[workflow-upload] native digest failed, switching to compatibility mode', {
          workflowId: workflow.id,
          filename: file.name,
          error
        })
      }
    } else {
      fileSha256Preview.value = '兼容模式'
    }

    if (clientCryptoMode === 'SERVER_COMPAT' && !compatibilityModeNotified) {
      compatibilityModeNotified = true
      ElMessage.warning('当前浏览器环境不支持原生摘要/加密，已自动切换到兼容上传模式。')
    }

    uploadStep.value = 2
    currentStage = '上传初始化'
    console.info('[workflow-upload] initiate request start', {
      workflowId: workflow.id,
      modelAssetId,
      filename: file.name,
      bytes: file.size,
      clientCryptoMode,
      fileSha256Provided: Boolean(fileSha256)
    })

    const initResp = await initModelUpload({
      workflowId: workflow.id,
      modelAssetId,
      originalFilename: file.name,
      fileSize: file.size,
      fileSha256,
      replaceExisting: replaceExistingMode.value
    })
    const initData = initResp?.data
    if (!initData || !initData.uploadId || !initData.uploadToken || !initData.aesKeyBase64 || !initData.aesIvBase64) {
      throw new Error('上传初始化返回的数据不完整，无法继续上传。')
    }

    const { uploadId, aesKeyBase64, aesIvBase64, uploadToken } = initData

    uploadStep.value = 3
    currentStage = '加密准备'
    const preparedPayload = await prepareWorkflowUploadPayload(
      file,
      aesKeyBase64,
      aesIvBase64,
      clientCryptoMode
    )

    if (preparedPayload.noticeMessage && !compatibilityModeNotified) {
      compatibilityModeNotified = true
      ElMessage.warning(preparedPayload.noticeMessage)
    }

    console.info('[workflow-upload] payload prepared', {
      workflowId: workflow.id,
      uploadId,
      cryptoMode: preparedPayload.cryptoMode,
      uploadFilename: preparedPayload.uploadFilename,
      uploadBytes: preparedPayload.uploadBlob.size,
      diagnostics: preparedPayload.diagnostics
    })

    uploadStep.value = 4
    currentStage = '文件上传'
    uploadProgress.value = 0
    const formData = new FormData()
    formData.append('file', preparedPayload.uploadBlob, preparedPayload.uploadFilename)
    await appendWeightsProtocolMetadata(formData, initData.uploadProtocol, {
      manifest: uploadManifestFile.value,
      descriptor: uploadDescriptorFile.value
    })
    console.info('[workflow-upload] file upload start', {
      workflowId: workflow.id,
      uploadId,
      filename: preparedPayload.uploadFilename,
      bytes: preparedPayload.uploadBlob.size,
      cryptoMode: preparedPayload.cryptoMode
    })

    await uploadEncryptedModelFile(
      uploadId,
      uploadToken,
      formData,
      (pct) => {
        uploadProgress.value = pct
      },
      preparedPayload.cryptoMode
    )
    console.info('[workflow-upload] file upload success', {
      workflowId: workflow.id,
      uploadId,
      bytes: preparedPayload.uploadBlob.size,
      cryptoMode: preparedPayload.cryptoMode
    })

    uploadStep.value = 5
    ElMessage.success('模型上传成功。')
    setTimeout(() => {
      uploadDialogVisible.value = false
      loadList()
    }, 1500)
  } catch (error) {
    const raw = error instanceof Error ? error.message : String(error || '')
    uploadError.value = uploadContract.value.uploadProtocol === 'WEIGHTS_V1'
      ? weightsUploadErrorMessage(raw)
      : resolveWorkflowUploadErrorMessage(currentStage, error)
    console.error('[workflow-upload] failed', {
      workflowId: workflow.id,
      filename: file.name,
      stage: currentStage,
      error,
      message: uploadError.value
    })
    uploadStep.value = 0
  }
}

onMounted(async () => {
  await loadList()
  const registryModelId = Number(route.query.registryModelId || 0)
  if (registryModelId) {
    await openCreateDialog()
    createForm.clientModelAssetId = registryModelId
  }
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

.model-selection-value {
  color: #606266;
  line-height: 32px;
}

:deep(.el-table .el-button + .el-button) {
  margin-left: 0;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.4;
  }
}
</style>


