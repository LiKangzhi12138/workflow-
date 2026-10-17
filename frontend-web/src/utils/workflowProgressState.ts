export interface UploadProtocolProgressState {
  requiredModelCount?: number | null
  expectedModelCount?: number | null
  clientModelCount?: number | null
  activeUploadCount?: number | null
  replaceAllowed?: boolean | null
  receivedModelCount?: number | null
  collectedModelCount?: number | null
}

export function shouldRequestUploadProtocol(
  workflowStatus: string | null | undefined,
  progress?: UploadProtocolProgressState | null,
  workflow?: UploadProtocolProgressState | null
) {
  if (!['CREATED', 'ACCEPTED'].includes(workflowStatus || '')) return false

  const required = Math.max(1,
    Number(progress?.requiredModelCount ?? workflow?.requiredModelCount ?? workflow?.expectedModelCount ?? workflow?.clientModelCount) || 1
  )
  const occupied = Math.max(
    0,
    Number(progress?.activeUploadCount) || 0,
    Number(progress?.receivedModelCount) || 0,
    Number(progress?.collectedModelCount) || 0,
    Number(workflow?.activeUploadCount) || 0,
    Number(workflow?.receivedModelCount) || 0,
    Number(workflow?.collectedModelCount) || 0
  )
  if (occupied >= required) return required === 1 && progress?.replaceAllowed === true
  if (!progress) return true
  if (required === 1) return occupied === 0 || progress.replaceAllowed === true
  return occupied < required
}

export function resolveUploadProtocolReadError(status?: number, code?: string, message?: string) {
  const detail = String(message || '')
  if (status === 401 || /登录状态已失效|请重新登录|\b401\b|unauthorized/i.test(detail)) {
    return '登录状态已失效，请重新登录后查看上传协议。'
  }
  if (status === 403 || code === 'FORBIDDEN' || /无权|权限不足|不属于你|\b403\b|forbidden|access denied/i.test(detail)) {
    return '当前账号无权继续上传此工作流。'
  }
  if (status === 404 || code === 'WORKFLOW_NOT_FOUND' || /工作流不存在|上传协议不存在|\b404\b|not found/i.test(detail)) {
    return '工作流或上传协议不存在，请刷新工作流列表。'
  }
  if (status === 409 || code === 'WORKFLOW_STATUS_INVALID' || /当前工作流不允许上传|状态已变化|\b409\b|conflict/i.test(detail)) {
    return '工作流状态已变化，当前不能继续上传；请刷新进度。'
  }
  return '暂时无法读取可信上传协议，上传已禁用，请刷新重试。'
}

export function isCurrentRequest(requestSequence: number, latestSequence: number) {
  return requestSequence === latestSequence
}

export function resolveProgressWorkflowTarget(
  routeWorkflowId: number | null | undefined,
  activeWorkflowId: number | null | undefined,
  firstPageWorkflowId: number | null | undefined
) {
  return routeWorkflowId || activeWorkflowId || firstPageWorkflowId || null
}
