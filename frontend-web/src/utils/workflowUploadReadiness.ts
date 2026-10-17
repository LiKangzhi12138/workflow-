import type { WorkflowUploadProtocol } from '../api/workflow'
import { protocolUploadReady } from './weightsPackageUpload.ts'

export type UploadReadinessCode =
  | 'UPLOAD_IN_PROGRESS'
  | 'WORKFLOW_NOT_LOADED'
  | 'WORKFLOW_STATUS_INVALID'
  | 'UPLOAD_PROGRESS_UNAVAILABLE'
  | 'UPLOAD_SLOTS_FULL'
  | 'UPLOAD_PROTOCOL_UNAVAILABLE'
  | 'UPLOAD_PROTOCOL_MISMATCH'
  | 'MODEL_ASSETS_NONE_AVAILABLE'
  | 'MODEL_ASSET_NOT_SELECTED'
  | 'MODEL_ASSETS_UNAVAILABLE'
  | 'MODEL_ASSET_NOT_AVAILABLE'
  | 'WEIGHTS_NOT_SELECTED'
  | 'WEIGHTS_COUNT_INVALID'
  | 'WEIGHTS_FORMAT_INVALID'
  | 'MANIFEST_NOT_SELECTED'
  | 'DESCRIPTOR_NOT_SELECTED'
  | 'PACKAGE_INCOMPATIBLE'

export interface UploadReadinessReason {
  code: UploadReadinessCode
  message: string
}

export interface UploadReadinessInput {
  workflowId: number | null
  workflowStatus: string | null
  progressAvailable: boolean
  progressError?: string
  requiredModelCount: number
  remainingSlots: number
  protocolLoaded: boolean
  contractWorkflowId: number | null
  protocol: WorkflowUploadProtocol
  protocolError?: string
  modelAssetId: number | null
  assetOptionsAvailable: boolean
  assetOptionCount: number
  selectedAssetAvailable: boolean
  assetError?: string
  weightsFiles: File[]
  manifest: File | null
  descriptor: File | null
  uploading: boolean
}

export function evaluateUploadReadiness(input: UploadReadinessInput) {
  const reasons: UploadReadinessReason[] = []
  const add = (code: UploadReadinessCode, message: string) => reasons.push({ code, message })

  if (input.uploading) add('UPLOAD_IN_PROGRESS', '上传任务正在进行中，请勿重复提交。')
  if (!input.workflowId) add('WORKFLOW_NOT_LOADED', '工作流尚未加载完成。')
  if (!['CREATED', 'ACCEPTED'].includes(input.workflowStatus || '')) {
    add('WORKFLOW_STATUS_INVALID', '当前工作流状态不允许上传。')
  }
  if (!input.progressAvailable) {
    add('UPLOAD_PROGRESS_UNAVAILABLE', input.progressError || '上传名额暂时无法确认，请刷新后重试。')
  } else if (input.remainingSlots <= 0) {
    add('UPLOAD_SLOTS_FULL', '当前工作流没有剩余上传名额。')
  }
  if (!input.protocolLoaded) {
    add('UPLOAD_PROTOCOL_UNAVAILABLE', input.protocolError || '可信上传协议尚未加载，请刷新后重试。')
  } else if (input.contractWorkflowId !== input.workflowId) {
    add('UPLOAD_PROTOCOL_MISMATCH', '上传协议与当前工作流不一致，请重新选择工作流。')
  }

  if (input.protocol === 'WEIGHTS_V1') {
    if (!input.assetOptionsAvailable) {
      add('MODEL_ASSETS_UNAVAILABLE', input.assetError || '可用模型资产尚未加载，请稍候。')
    } else if (input.assetOptionCount === 0) {
      add('MODEL_ASSETS_NONE_AVAILABLE', '当前账号没有可用于上传的模型资产，请先到模型管理登记或校验兼容资产。')
    } else if (!input.modelAssetId) {
      add('MODEL_ASSET_NOT_SELECTED', '请选择当前账号可用的模型资产。')
    } else if (!input.selectedAssetAvailable) {
      add('MODEL_ASSET_NOT_AVAILABLE', '所选模型资产不在当前账号可用列表中，请重新选择。')
    }
  } else if (!input.modelAssetId) {
    add('MODEL_ASSET_NOT_SELECTED', '请选择当前账号可用的模型资产。')
  }

  if (input.weightsFiles.length === 0) add('WEIGHTS_NOT_SELECTED', '请选择权重文件。')
  const maxFiles = input.protocol === 'WEIGHTS_V1'
    ? 1
    : input.requiredModelCount <= 1 ? 1 : input.remainingSlots
  if (input.protocolLoaded && input.weightsFiles.length > maxFiles) {
    add('WEIGHTS_COUNT_INVALID', '所选模型文件数超过本次可上传数量。')
  }
  if (input.protocolLoaded && input.protocol === 'WEIGHTS_V1') {
    if (input.weightsFiles.some((file) => !/\.pt$/i.test(file.name))) {
      add('WEIGHTS_FORMAT_INVALID', '权重文件必须是 .pt 格式。')
    }
    if (!input.manifest) add('MANIFEST_NOT_SELECTED', '请选择 Manifest 文件。')
    if (!input.descriptor) add('DESCRIPTOR_NOT_SELECTED', '请选择 Descriptor 文件。')
  }
  if (input.protocolLoaded && input.weightsFiles.length > 0 &&
      input.weightsFiles.some((file) => !protocolUploadReady(input.protocol, file, {
        manifest: input.manifest,
        descriptor: input.descriptor
      })) &&
      !reasons.some((reason) => ['WEIGHTS_FORMAT_INVALID', 'MANIFEST_NOT_SELECTED', 'DESCRIPTOR_NOT_SELECTED'].includes(reason.code))) {
    add('PACKAGE_INCOMPATIBLE', '所选文件与可信上传协议不兼容。')
  }

  return { ready: reasons.length === 0, reasons }
}
