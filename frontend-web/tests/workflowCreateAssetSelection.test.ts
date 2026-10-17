import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const component = readFileSync(new URL('../src/views/client/ClientWorkflowManageView.vue', import.meta.url), 'utf8')
const template = component.split('<script setup lang="ts">')[0]
const progressPage = readFileSync(new URL('../src/views/client/ClientWorkflowProgressView.vue', import.meta.url), 'utf8')

test('Definition workflow creation hides the client asset selector but retains trusted Definition selection', () => {
  assert.match(template, /v-if="modelSelectionMode === 'legacy'" label="客户端模型"/)
  assert.match(template, /label="模型类型"/)
  assert.match(component, /modelSelectionMode\.value === 'legacy'\s*\? \{ clientModelAssetId:/)
  assert.match(component, /clientModelAssetId: Number\(createForm\.clientModelAssetId\)/)
})

test('the upload dialog still requires a selected asset and keeps the weights package inputs', () => {
  assert.match(component, /const uploadPackageReady = computed\(\(\) => uploadReadiness\.value\.ready\)/)
  assert.match(template, /title="为工作流提交模型"[\s\S]*?destroy-on-close/)
  assert.match(template, /v-model="selectedModelAssetId"/)
  assert.match(template, /uploadContract\.uploadProtocol === 'WEIGHTS_V1'/)
  assert.match(component, /getWorkflowUploadContract\(row\.id\)/)
  assert.match(component, /myModelAssets\.value = list\.filter\(isUploadableClientModelAsset\)/)
  assert.match(component, /resolveUploadableModelAsset\(myModelAssets\.value, selectedModelAssetId\.value\)/)
})

test('the progress upload path selects a current-user asset even after another client has bound the workflow', () => {
  assert.match(progressPage, /v-if="uploadContractLoaded && \['CREATED', 'ACCEPTED'\]\.includes\(workflowDetail\.status\)"/)
  assert.match(progressPage, /v-model="selectedUploadModelAssetId"/)
  assert.match(progressPage, /selectedUploadModelAssetId\.value = null/)
  assert.match(progressPage, /isCurrentRequest\(assetRequestSequence, uploadAssetRequestSequence\)/)
  assert.match(progressPage, /modelAssetId: resolvedClientModelAssetId\.value/)
  assert.match(progressPage, /const canStartClientUpload = computed\(\(\) => uploadReadiness\.value\.ready\)/)
  assert.match(progressPage, /resolveUploadModelAssetId\(/)
  assert.match(progressPage, /uploadAssetOptions\.value = assets\.filter\(isUploadableClientModelAsset\)/)
  assert.match(progressPage, /resolveUploadableModelAsset\(uploadAssetOptions\.value, resolvedClientModelAssetId\.value\)/)
  assert.match(progressPage, /:multiple="uploadContract\.uploadProtocol !== 'WEIGHTS_V1'"/)
  assert.match(progressPage, /stageUploadFiles\(uploadFiles, limit/)
  assert.match(progressPage, /modelAssetId,\s*originalFilename: fileItem\.name/)
})
