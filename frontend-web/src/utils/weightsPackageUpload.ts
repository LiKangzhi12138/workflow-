import type { WorkflowUploadProtocol } from '@/api/workflow'

export interface WeightsPackageMetadataFiles {
  manifest: File | null
  descriptor: File | null
}

export function protocolUploadReady(
  protocol: WorkflowUploadProtocol,
  weights: File | null,
  metadata: WeightsPackageMetadataFiles
): boolean {
  if (!weights) return false
  if (protocol === 'LEGACY_CHECKPOINT') return true
  return Boolean(metadata.manifest && metadata.descriptor && /\.pt$/i.test(weights.name))
}

export async function appendWeightsProtocolMetadata(
  formData: FormData,
  protocol: WorkflowUploadProtocol,
  metadata: WeightsPackageMetadataFiles
): Promise<void> {
  if (protocol !== 'WEIGHTS_V1') return
  if (!metadata.manifest || !metadata.descriptor) {
    throw new Error('请选择 manifest.json 和 descriptor.json。')
  }
  const [manifest, descriptor] = await Promise.all([
    readJsonObject(metadata.manifest, 'manifest'),
    readJsonObject(metadata.descriptor, 'descriptor')
  ])
  formData.append('manifest', manifest)
  formData.append('descriptor', descriptor)
}

async function readJsonObject(file: File, label: string): Promise<string> {
  if (file.size > 1024 * 1024) throw new Error(`${label} 不能超过 1 MiB。`)
  const text = await file.text()
  const value = JSON.parse(text)
  if (!value || Array.isArray(value) || typeof value !== 'object') {
    throw new Error(`${label} 必须是 JSON object。`)
  }
  return JSON.stringify(value)
}

export function weightsUploadErrorMessage(codeOrMessage: string): string {
  if (/MANIFEST|SCHEMA/i.test(codeOrMessage)) return '权重包 manifest/descriptor 格式错误。'
  if (/DEFINITION|ARCHITECTURE|KEY|SHAPE|DTYPE|COMPATIBILITY/i.test(codeOrMessage)) {
    return '上传的模型权重与当前工作流选择的模型不兼容。'
  }
  if (/SHA/i.test(codeOrMessage)) return '权重文件摘要校验失败，请重新生成权重包。'
  if (/PROTOCOL.*DISABLED/i.test(codeOrMessage)) return '权重协议当前未启用，请刷新页面后重试。'
  return '权重包上传失败，请稍后重试。'
}
