import type { UploadProgressVO, WorkflowDetail, WorkflowListItem, WorkflowStatus } from '@/api/workflow'

export const WORKFLOW_PROGRESS_COPY_PACK = 'zh-CN.workflow-progress'

export const WORKFLOW_STATUS_LABELS: Record<WorkflowStatus, string> = {
  CREATED: '\u5df2\u521b\u5efa',
  ACCEPTED: '\u670d\u52a1\u7aef\u5df2\u63a5\u6536',
  PREPARING: '\u51c6\u5907\u4e2d',
  TRAINING_RUNNING: '\u8bad\u7ec3\u4e2d',
  VALIDATING: '\u9a8c\u8bc1\u4e2d',
  COMPLETED: '\u5df2\u5b8c\u6210',
  FAILED: '\u6d41\u7a0b\u5931\u8d25',
  WITHDRAWN: '\u5df2\u64a4\u56de'
}

export const UPLOAD_STATUS_LABELS: Record<string, string> = {
  PENDING: '\u51c6\u5907\u4e0a\u4f20',
  UPLOADING: '\u6b63\u5728\u4e0a\u4f20\u6a21\u578b',
  ENCRYPTED_STORED: '\u6d17\u724c\u52a0\u5bc6\u5e76\u4e0a\u4f20\u5b8c\u6210',
  DECRYPTING: '\u6b63\u5728\u53cd\u6d17\u724c\u89e3\u5bc6',
  COMPLETED: '\u89e3\u5bc6\u548c\u6a21\u578b\u7eb3\u7ba1\u5b8c\u6210',
  FAILED: '\u5904\u7406\u5931\u8d25'
}

export const WORKFLOW_CURRENT_STEP_TEXT = {
  serverAccepted: '\u670d\u52a1\u7aef\u5df2\u63a5\u6536',
  downloadingModel: '\u6b63\u5728\u4e0b\u8f7d\u6a21\u578b',
  reverseShuffleDecrypting: '\u6b63\u5728\u53cd\u6d17\u724c\u89e3\u5bc6',
  registeringServerModel: '\u6b63\u5728\u5bfc\u5165\u670d\u52a1\u7aef\u6a21\u578b\u7ba1\u7406',
  secureShuffleProcessing: '安全洗牌处理中',
  dpProcessing: '差分隐私处理中',
  secureAggregationProcessing: '安全聚合处理中',
  weightsInspectedWaitingFederated: '\u6743\u91cd\u68c0\u67e5\u5b8c\u6210\uff0c\u7b49\u5f85\u8054\u90a6\u805a\u5408',
  federatedAggregating: '\u8054\u90a6\u5b66\u4e60\u805a\u5408\u4e2d',
  federatedCompleted: '\u8054\u90a6\u5b66\u4e60\u5b8c\u6210',
  federatedFailed: '\u8054\u90a6\u5b66\u4e60\u5931\u8d25',
  decryptReady: '\u89e3\u5bc6\u5b8c\u6210\uff0c\u53ef\u7ee7\u7eed\u9a8c\u8bc1',
  decryptFailed: '\u53cd\u6d17\u724c\u89e3\u5bc6\u5931\u8d25'
} as const

export const PRIVACY_STATUS_LABELS: Record<string, string> = {
  DISABLED: '未启用',
  ENABLED: '已启用',
  WAITING: '等待洗牌',
  PREPARING: '聚合准备中',
  RUNNING: '处理中',
  COMPLETED: '已完成',
  FAILED: '处理失败'
}

export function getPrivacyStatusLabel(status?: string) {
  if (!status) return '-'
  return PRIVACY_STATUS_LABELS[status] || status
}

export function formatMechanismEnabled(enabled?: boolean) {
  return enabled ? '已启用' : '未启用'
}

export const WORKFLOW_PROGRESS_TEXT = {
  latestWorkflows: '\u6700\u8fd1\u5de5\u4f5c\u6d41',
  latestWorkflowsDescClient:
    '\u65e0\u8bba\u662f\u76f4\u63a5\u8fdb\u5165\u9875\u9762\u8fd8\u662f\u5237\u65b0\uff0c\u90fd\u4f1a\u9ed8\u8ba4\u6253\u5f00\u6700\u65b0\u4e00\u6761\u5de5\u4f5c\u6d41\u3002',
  latestWorkflowsDescServer:
    '\u76f4\u63a5\u8fdb\u5165\u9875\u9762\u65f6\u4f1a\u9ed8\u8ba4\u6253\u5f00\u6700\u65b0\u4e00\u6761\u53ef\u89c1\u5de5\u4f5c\u6d41\uff0c\u5e76\u53ef\u5728\u6b64\u76f4\u63a5\u63a8\u8fdb\u6d41\u7a0b\u3002',
  emptyClient:
    '\u5f53\u524d\u8fd8\u6ca1\u6709\u5de5\u4f5c\u6d41\u8bb0\u5f55\uff0c\u53ef\u4ee5\u5148\u5728\u5de5\u4f5c\u6d41\u7ba1\u7406\u9875\u521b\u5efa\u5e76\u4e0a\u4f20\u6a21\u578b\u3002',
  emptyServer:
    '\u5f53\u524d\u8fd8\u6ca1\u6709\u53ef\u89c1\u5de5\u4f5c\u6d41\uff0c\u6709\u65b0\u7684\u5ba2\u6237\u7aef\u5de5\u4f5c\u6d41\u540e\u4f1a\u5728\u8fd9\u91cc\u81ea\u52a8\u51fa\u73b0\u3002',
  workflowInfo: '\u5de5\u4f5c\u6d41\u57fa\u7840\u4fe1\u606f',
  detailSteps: '\u8be6\u7ec6\u6b65\u9aa4\u8bb0\u5f55',
  uploadRecords: '\u6a21\u578b\u4e0a\u4f20\u4e0e\u89e3\u5bc6\u8bb0\u5f55',
  currentStep: '\u5f53\u524d\u6b65\u9aa4',
  currentProgress: '\u5f53\u524d\u8fdb\u5ea6',
  workflowStatus: '\u5de5\u4f5c\u6d41\u72b6\u6001',
  clientVisualTitle: '\u5ba2\u6237\u7aef\u6d17\u724c\u52a0\u5bc6\u4e0e\u4e0a\u4f20\u6d41\u7a0b',
  clientVisualDesc:
    '\u8fd9\u91cc\u5c55\u793a\u7684\u662f\u9636\u6bb5\u578b\u8fdb\u5ea6\uff0c\u7528\u6765\u5e2e\u52a9\u7528\u6237\u611f\u77e5\u6d17\u724c\u52a0\u5bc6\u3001\u4e0a\u4f20\u3001\u670d\u52a1\u7aef\u63a5\u6536\u4e0e\u8054\u90a6\u805a\u5408\u7684\u5f53\u524d\u8fdb\u5ea6\u3002',
  serverVisualTitle: '\u670d\u52a1\u7aef\u53cd\u6d17\u724c\u89e3\u5bc6\u4e0e\u7eb3\u7ba1\u6d41\u7a0b',
  serverVisualDesc:
    '页面会根据现有解密、纳管、隐私保护与联邦聚合状态自动轮询，让用户看见“服务端已接收 -> 安全洗牌 -> 反洗牌解密 -> 差分隐私处理 -> 安全聚合 -> FEDML 联邦聚合”的过程。',
  phaseProgressHint:
    '\u8fd9\u662f\u4e1a\u52a1\u9636\u6bb5\u8fdb\u5ea6\uff0c\u7528\u6765\u53cd\u6620\u52a0\u5bc6/\u4e0a\u4f20/\u89e3\u5bc6\u73af\u8282\u5df2\u8d70\u5230\u54ea\u4e00\u6b65\uff0c\u4e0d\u4ee3\u8868\u5b57\u8282\u7ea7\u7cbe\u786e\u8fdb\u5ea6\u3002',
  waitServerAccept: '\u5df2\u5b8c\u6210\u6d17\u724c\u52a0\u5bc6\u4e0e\u4e0a\u4f20\uff0c\u6b63\u7b49\u5f85\u670d\u52a1\u7aef\u63a5\u6536\u548c\u53cd\u6d17\u724c\u89e3\u5bc6\u3002',
  decryptReady: '\u53cd\u6d17\u724c\u89e3\u5bc6\u5df2\u5b8c\u6210\uff0c\u6a21\u578b\u5df2\u7eb3\u5165\u670d\u52a1\u7aef\u672c\u5730\u7ba1\u7406\uff0c\u53ef\u4ee5\u7ee7\u7eed\u9a8c\u8bc1\u3002',
  noWorkflowSelected: '\u8bf7\u5148\u4ece\u5de6\u4fa7\u9009\u62e9\u4e00\u6761\u5de5\u4f5c\u6d41\u3002',
  workflowListEmpty: '\u6682\u65e0\u5de5\u4f5c\u6d41',
  selectedBadge: '\u5f53\u524d',
  modelCountLabel: '\u6a21\u578b\u9700\u6c42',
  receivedCountLabel: '\u5df2\u63a5\u6536',
  managedCountLabel: '\u5df2\u7eb3\u7ba1',
  serverDatasetLabel: '\u9a8c\u8bc1\u6570\u636e\u96c6',
  bindDataset: '\u7ed1\u5b9a\u6570\u636e\u96c6',
  startValidation: '\u542f\u52a8\u9a8c\u8bc1',
  acceptWorkflow: '\u63a5\u6536\u5de5\u4f5c\u6d41',
  openValidation: '\u6253\u5f00\u9a8c\u8bc1\u9875',
  refresh: '\u5237\u65b0',
  uploadListEmpty: '\u6682\u65e0\u6a21\u578b\u4e0a\u4f20\u8bb0\u5f55',
  loadingLatestWorkflow: '\u6b63\u5728\u52a0\u8f7d\u6700\u65b0\u5de5\u4f5c\u6d41...',
  clientPageTitle: '\u5ba2\u6237\u7aef\u5de5\u4f5c\u6d41\u8fdb\u5ea6\u5de5\u4f5c\u53f0',
  serverPageTitle: '\u670d\u52a1\u7aef\u5de5\u4f5c\u6d41\u8fdb\u5ea6\u5de5\u4f5c\u53f0',
  defaultSelectLogClient: '[client-workflow-progress] default selected workflow',
  defaultSelectLogServer: '[server-workflow-progress] default selected workflow',
  uploadSwitchLog: '[client-workflow-progress] upload stage switched',
  decryptPollingLog: '[server-workflow-progress] decrypt stage polling',
  bindDatasetPlaceholder: '\u8bf7\u9009\u62e9\u81ea\u5df1\u7684\u670d\u52a1\u7aef\u6570\u636e\u96c6',
  bindDatasetSuccess: '\u670d\u52a1\u7aef\u9a8c\u8bc1\u6570\u636e\u96c6\u5df2\u7ed1\u5b9a\u3002',
  acceptSuccess: '\u5de5\u4f5c\u6d41\u5df2\u63a5\u6536\uff0c\u540e\u53f0\u6b63\u5728\u6267\u884c\u591a\u6a21\u578b\u63a5\u6536\u3001\u53cd\u6d17\u724c\u89e3\u5bc6\u3001\u6a21\u578b\u7eb3\u7ba1\u4e0e\u8054\u90a6\u805a\u5408\u3002',
  startValidationSuccess: '\u9a8c\u8bc1\u4efb\u52a1\u5df2\u63d0\u4ea4\uff0c\u8bf7\u7ee7\u7eed\u5173\u6ce8\u8fdb\u5ea6\u3002',
  actionFailed: '\u64cd\u4f5c\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002',
  uploadDialogTitle: '\u6d17\u724c\u52a0\u5bc6\u4e0e\u6a21\u578b\u4e0a\u4f20',
  uploadDialogWaitingClose: '\u4e0a\u4f20\u5df2\u5b8c\u6210\uff0c\u8bf7\u624b\u52a8\u5173\u95ed\u5f39\u7a97\u3002',
  uploadDialogClose: '\u5173\u95ed\u4e0a\u4f20\u7a97\u53e3',
  decryptDialogTitle: '\u670d\u52a1\u7aef\u63a5\u6536\u3001\u89e3\u5bc6\u3001\u7eb3\u7ba1\u4e0e\u8054\u90a6\u5b66\u4e60',
  decryptDialogWaitingClose: '\u670d\u52a1\u7aef\u5904\u7406\u5df2\u8fbe\u5230\u5f53\u524d\u7ec8\u6001\uff0c\u8bf7\u624b\u52a8\u5173\u95ed\u5f39\u7a97\u3002',
  decryptDialogClose: '\u5173\u95ed\u89e3\u5bc6\u7a97\u53e3'
} as const

export const CLIENT_VISUAL_STAGES = [
  {
    key: 'created',
    title: '\u5df2\u521b\u5efa\u5de5\u4f5c\u6d41',
    description: '\u5df2\u751f\u6210\u5de5\u4f5c\u6d41\uff0c\u968f\u65f6\u53ef\u5f00\u59cb\u9009\u62e9\u6a21\u578b\u5e76\u4e0a\u4f20\u3002'
  },
  {
    key: 'encrypting',
    title: '\u6b63\u5728\u8fdb\u884c\u6d17\u724c\u52a0\u5bc6',
    description: '\u5ba2\u6237\u7aef\u6b63\u5728\u672c\u5730\u5b8c\u6210\u9636\u6bb5\u5316\u6d17\u724c\u52a0\u5bc6\u5904\u7406\u3002'
  },
  {
    key: 'uploading',
    title: '\u6b63\u5728\u4e0a\u4f20\u6a21\u578b',
    description: '\u52a0\u5bc6\u540e\u7684\u6a21\u578b\u6587\u4ef6\u6b63\u5728\u4e0a\u4f20\u5230\u670d\u52a1\u7aef\u3002'
  },
  {
    key: 'waiting-server',
    title: '\u4e0a\u4f20\u5b8c\u6210\uff0c\u7b49\u5f85\u670d\u52a1\u7aef\u63a5\u6536',
    description: '\u6587\u4ef6\u5df2\u9001\u8fbe\u670d\u52a1\u7aef\uff0c\u6b63\u5728\u7b49\u5f85\u53cd\u6d17\u724c\u89e3\u5bc6\u4e0e\u6a21\u578b\u7eb3\u7ba1\u3002'
  },
  {
    key: 'server-processing',
    title: '\u670d\u52a1\u7aef\u6b63\u5728\u5904\u7406\u6a21\u578b',
    description: '\u670d\u52a1\u7aef\u5df2\u63a5\u6536\u5f53\u524d\u5de5\u4f5c\u6d41\uff0c\u6b63\u5728\u4e0b\u8f7d\u6a21\u578b\u3001\u53cd\u6d17\u724c\u89e3\u5bc6\u5e76\u5bfc\u5165\u672c\u5730\u6a21\u578b\u7ba1\u7406\u3002'
  },
  {
    key: 'secure-shuffle',
    title: '安全洗牌处理',
    description: '服务端正在对已纳管模型执行安全洗牌批次处理，生成洗牌批次号和处理顺序摘要。'
  },
  {
    key: 'dp',
    title: '差分隐私处理',
    description: '服务端正在记录并执行差分隐私参数化处理链路，形成隐私预算和裁剪噪声参数摘要。'
  },
  {
    key: 'secure-aggregation',
    title: '安全聚合协议处理',
    description: '服务端正在执行安全聚合协议原型链路，完成聚合准备、聚合执行和结果生成控制。'
  },
  {
    key: 'waiting-federated',
    title: '\u6743\u91cd\u68c0\u67e5\u5b8c\u6210\uff0c\u7b49\u5f85\u8054\u90a6\u805a\u5408',
    description: '\u5df2\u7eb3\u7ba1\u7684 weights-only \u6743\u91cd\u5df2\u901a\u8fc7\u68c0\u67e5\uff0c\u5c1a\u672a\u542f\u52a8\u8054\u90a6\u805a\u5408\u3002'
  },
  {
    key: 'federated',
    title: '\u8054\u90a6\u5b66\u4e60\u805a\u5408\u4e2d',
    description: '服务端已收齐模型，正在基于 FEDML 对多个解密后模型执行联邦聚合。'
  },
  {
    key: 'ready',
    title: '\u8054\u90a6\u5b66\u4e60\u5b8c\u6210\uff0c\u53ef\u7ee7\u7eed\u9a8c\u8bc1',
    description: '\u670d\u52a1\u7aef\u5df2\u751f\u6210\u5168\u5c40\u6a21\u578b\uff0c\u540e\u7eed\u5c06\u4ee5\u8054\u90a6\u805a\u5408\u540e\u7684\u5168\u5c40\u6a21\u578b\u8fdb\u5165\u9a8c\u8bc1\u6d41\u7a0b\u3002'
  }
] as const

export const SERVER_VISUAL_STAGES = [
  {
    key: 'accepted',
    title: '\u670d\u52a1\u7aef\u5df2\u63a5\u6536',
    description: '\u5de5\u4f5c\u6d41\u5df2\u8fdb\u5165\u670d\u52a1\u7aef\u63a5\u6536\u9636\u6bb5\uff0c\u540e\u7eed\u4f1a\u81ea\u52a8\u53cd\u6d17\u724c\u89e3\u5bc6\u3002'
  },
  {
    key: 'downloading',
    title: '\u6b63\u5728\u4e0b\u8f7d\u6a21\u578b',
    description: '\u670d\u52a1\u7aef\u6b63\u5728\u4ece\u672c\u5730\u4e0a\u4f20\u5b58\u50a8\u4e2d\u8bfb\u53d6\u672c\u6b21\u6a21\u578b\uff0c\u51c6\u5907\u8fdb\u5165\u53cd\u6d17\u724c\u89e3\u5bc6\u3002'
  },
  {
    key: 'decrypting',
    title: '\u6b63\u5728\u53cd\u6d17\u724c\u89e3\u5bc6',
    description: '\u670d\u52a1\u7aef\u6b63\u5728\u5bf9\u5df2\u4e0a\u4f20\u7684\u6a21\u578b\u6587\u4ef6\u6267\u884c\u89e3\u5bc6\u4e0e\u6821\u9a8c\u3002'
  },
  {
    key: 'registering',
    title: '\u6b63\u5728\u5bfc\u5165\u670d\u52a1\u7aef\u6a21\u578b\u7ba1\u7406',
    description: '\u89e3\u5bc6\u540e\u7684\u6a21\u578b\u6b63\u5728\u5bfc\u5165\u670d\u52a1\u7aef\u672c\u5730\u6a21\u578b\u8d44\u4ea7\u7ba1\u7406\u3002'
  },
  {
    key: 'secure-shuffle',
    title: '安全洗牌处理',
    description: '服务端正在执行安全洗牌处理，记录洗牌批次号和顺序摘要。'
  },
  {
    key: 'dp',
    title: '差分隐私处理',
    description: '服务端正在执行差分隐私处理阶段，记录隐私预算、失效概率、裁剪阈值和噪声系数。'
  },
  {
    key: 'secure-aggregation',
    title: '安全聚合协议处理',
    description: '服务端正在执行安全聚合协议处理链路。'
  },
  {
    key: 'waiting-federated',
    title: '\u6743\u91cd\u68c0\u67e5\u5b8c\u6210\uff0c\u7b49\u5f85\u8054\u90a6\u805a\u5408',
    description: '\u5df2\u7eb3\u7ba1\u7684 weights-only \u6743\u91cd\u5df2\u901a\u8fc7\u68c0\u67e5\uff0c\u5c1a\u672a\u542f\u52a8\u8054\u90a6\u805a\u5408\u3002'
  },
  {
    key: 'federated',
    title: '\u8054\u90a6\u5b66\u4e60\u805a\u5408\u4e2d',
    description: '服务端已收齐并纳管模型，正在基于 FEDML 生成全局模型。'
  },
  {
    key: 'ready',
    title: '\u8054\u90a6\u5b66\u4e60\u5b8c\u6210',
    description: '\u5168\u5c40\u6a21\u578b\u5df2\u805a\u5408\u751f\u6210\uff0c\u53ef\u7528\u4e8e\u7ed1\u5b9a\u6570\u636e\u96c6\u548c\u542f\u52a8\u9a8c\u8bc1\u3002'
  },
  {
    key: 'dataset',
    title: '\u5df2\u7ed1\u5b9a\u9a8c\u8bc1\u6570\u636e\u96c6',
    description: '\u670d\u52a1\u7aef\u9a8c\u8bc1\u6570\u636e\u96c6\u5df2\u7ecf\u7ed1\u5b9a\uff0c\u53ef\u76f4\u63a5\u542f\u52a8\u9a8c\u8bc1\u3002'
  },
  {
    key: 'validation',
    title: '\u6b63\u5728\u542f\u52a8\u9a8c\u8bc1\u4efb\u52a1',
    description: '\u670d\u52a1\u7aef\u6b63\u5728\u63d0\u4ea4\u9a8c\u8bc1\u4efb\u52a1\uff0c\u540e\u7eed\u53ef\u8df3\u8f6c\u9a8c\u8bc1\u9875\u67e5\u770b\u7ed3\u679c\u3002'
  }
] as const

export type ServerProcessPhaseKey =
  | 'created'
  | 'accepted'
  | 'downloading'
  | 'decrypting'
  | 'registering'
  | 'secure-shuffle'
  | 'dp'
  | 'secure-aggregation'
  | 'waiting-federated'
  | 'federated'
  | 'ready'
  | 'dataset'
  | 'validation'
  | 'completed'
  | 'failed'

type WorkflowDetailLite = Pick<
  WorkflowDetail,
  'status' | 'currentStep' | 'serverDatasetAssetId' | 'pythonJobId' | 'errorMessage' | 'federatedStatus'
>

type UploadProgressLite = Pick<
  UploadProgressVO,
  'latestUploadStatus' | 'collectedModelCount' | 'requiredModelCount'
>

function includesStepText(currentStep: string, target: string) {
  return currentStep.includes(target)
}

export function resolveServerProcessPhase(
  detail?: WorkflowDetailLite | null,
  progress?: UploadProgressLite | null
): ServerProcessPhaseKey {
  const status = detail?.status || ''
  const currentStep = detail?.currentStep || ''
  const latestUploadStatus = progress?.latestUploadStatus || ''
  const requiredCount = Number(progress?.requiredModelCount || 1)
  const managedCount = Number(progress?.collectedModelCount || 0)
  const managedReady = managedCount >= Math.max(1, requiredCount)

  if (
    status === 'FAILED' ||
    detail?.federatedStatus === 'FAILED' ||
    latestUploadStatus === 'FAILED' ||
    includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.decryptFailed) ||
    includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.federatedFailed)
  ) {
    return 'failed'
  }
  if (status === 'COMPLETED') {
    return 'completed'
  }
  if (detail?.pythonJobId || status === 'VALIDATING' || status === 'TRAINING_RUNNING') {
    return 'validation'
  }
  if (detail?.serverDatasetAssetId) {
    return 'dataset'
  }
  if (
    detail?.federatedStatus === 'COMPLETED' ||
    includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.federatedCompleted) ||
    includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.decryptReady)
  ) {
    return 'ready'
  }
  if (includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.weightsInspectedWaitingFederated)) {
    return 'waiting-federated'
  }
  if (
    detail?.federatedStatus === 'RUNNING' ||
    includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.federatedAggregating) ||
    managedReady
  ) {
    if (includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.secureShuffleProcessing)) {
      return 'secure-shuffle'
    }
    if (includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.dpProcessing)) {
      return 'dp'
    }
    if (includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.secureAggregationProcessing)) {
      return 'secure-aggregation'
    }
    return 'federated'
  }
  if (includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.registeringServerModel)) {
    return 'registering'
  }
  if (includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.downloadingModel)) {
    return 'downloading'
  }
  if (latestUploadStatus === 'DECRYPTING' || includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.reverseShuffleDecrypting)) {
    return 'decrypting'
  }
  if (status === 'ACCEPTED' || includesStepText(currentStep, WORKFLOW_CURRENT_STEP_TEXT.serverAccepted)) {
    return 'accepted'
  }
  return 'created'
}

export function computeServerProcessPercent(phase: ServerProcessPhaseKey) {
  switch (phase) {
    case 'created':
      return 5
    case 'accepted':
      return 18
    case 'downloading':
      return 38
    case 'decrypting':
      return 62
    case 'registering':
      return 82
    case 'secure-shuffle':
      return 86
    case 'dp':
      return 88
    case 'secure-aggregation':
      return 90
    case 'waiting-federated':
      return 90
    case 'federated':
      return 92
    case 'ready':
    case 'dataset':
    case 'validation':
    case 'completed':
      return 100
    case 'failed':
      return 100
    default:
      return 0
  }
}

export function isServerProcessDialogActive(phase: ServerProcessPhaseKey) {
  return ['accepted', 'downloading', 'decrypting', 'registering', 'secure-shuffle', 'dp', 'secure-aggregation', 'federated'].includes(phase)
}

export function isServerProcessDialogClosable(phase: ServerProcessPhaseKey) {
  return ['waiting-federated', 'ready', 'dataset', 'validation', 'completed', 'failed'].includes(phase)
}

export function getServerProcessTitle(phase: ServerProcessPhaseKey) {
  switch (phase) {
    case 'accepted':
      return WORKFLOW_CURRENT_STEP_TEXT.serverAccepted
    case 'downloading':
      return WORKFLOW_CURRENT_STEP_TEXT.downloadingModel
    case 'decrypting':
      return WORKFLOW_CURRENT_STEP_TEXT.reverseShuffleDecrypting
    case 'registering':
      return WORKFLOW_CURRENT_STEP_TEXT.registeringServerModel
    case 'secure-shuffle':
      return WORKFLOW_CURRENT_STEP_TEXT.secureShuffleProcessing
    case 'dp':
      return WORKFLOW_CURRENT_STEP_TEXT.dpProcessing
    case 'secure-aggregation':
      return WORKFLOW_CURRENT_STEP_TEXT.secureAggregationProcessing
    case 'waiting-federated':
      return WORKFLOW_CURRENT_STEP_TEXT.weightsInspectedWaitingFederated
    case 'federated':
      return WORKFLOW_CURRENT_STEP_TEXT.federatedAggregating
    case 'ready':
      return WORKFLOW_CURRENT_STEP_TEXT.federatedCompleted
    case 'dataset':
      return '\u5df2\u7ed1\u5b9a\u9a8c\u8bc1\u6570\u636e\u96c6'
    case 'validation':
      return '\u6b63\u5728\u542f\u52a8\u9a8c\u8bc1\u4efb\u52a1'
    case 'completed':
      return '\u9a8c\u8bc1\u6d41\u7a0b\u5df2\u5b8c\u6210'
    case 'failed':
      return WORKFLOW_CURRENT_STEP_TEXT.decryptFailed
    default:
      return '\u7b49\u5f85\u670d\u52a1\u7aef\u63a5\u6536'
  }
}

export function getServerProcessDescription(phase: ServerProcessPhaseKey) {
  switch (phase) {
    case 'accepted':
      return '\u5de5\u4f5c\u6d41\u5df2\u8fdb\u5165\u670d\u52a1\u7aef\u7a97\u53e3\uff0c\u7cfb\u7edf\u5df2\u5f00\u59cb\u540e\u7eed\u5904\u7406\u3002'
    case 'downloading':
      return '\u670d\u52a1\u7aef\u6b63\u5728\u4ece\u5df2\u63a5\u6536\u7684\u52a0\u5bc6\u6a21\u578b\u6587\u4ef6\u4e2d\u8bfb\u53d6\u672c\u6b21\u5185\u5bb9\u3002'
    case 'decrypting':
      return '\u670d\u52a1\u7aef\u6b63\u5728\u57fa\u4e8e\u73b0\u6709\u94fe\u8def\u6267\u884c\u53cd\u6d17\u724c\u89e3\u5bc6\u3002'
    case 'registering':
      return '\u89e3\u5bc6\u540e\u7684\u6a21\u578b\u6b63\u5728\u5bfc\u5165\u670d\u52a1\u7aef\u672c\u5730\u6a21\u578b\u7ba1\u7406\u3002'
    case 'secure-shuffle':
      return '服务端正在执行安全洗牌处理，生成批次号与顺序摘要。'
    case 'dp':
      return '服务端正在执行差分隐私处理阶段，记录隐私预算和裁剪噪声参数。'
    case 'secure-aggregation':
      return '服务端正在执行安全聚合协议处理链路，控制聚合准备、执行与结果生成。'
    case 'waiting-federated':
      return '\u6743\u91cd\u5df2\u901a\u8fc7\u5b89\u5168\u68c0\u67e5\u548c\u517c\u5bb9\u6027\u6821\u9a8c\uff0c\u5f53\u524d\u6b63\u7b49\u5f85\u670d\u52a1\u7aef\u542f\u52a8\u8054\u90a6\u805a\u5408\u3002'
    case 'federated':
      return '服务端正在基于已纳管的多个模型执行 FEDML 联邦聚合。'
    case 'ready':
      return '\u8054\u90a6\u5168\u5c40\u6a21\u578b\u5df2\u751f\u6210\uff0c\u53ef\u7ee7\u7eed\u7ed1\u5b9a\u6570\u636e\u96c6\u5e76\u542f\u52a8\u9a8c\u8bc1\u3002'
    case 'dataset':
      return '\u9a8c\u8bc1\u6570\u636e\u96c6\u5df2\u7ed1\u5b9a\uff0c\u53ef\u76f4\u63a5\u542f\u52a8\u9a8c\u8bc1\u3002'
    case 'validation':
      return '\u9a8c\u8bc1\u4efb\u52a1\u5df2\u63d0\u4ea4\uff0c\u53ef\u8df3\u8f6c\u9a8c\u8bc1\u9875\u67e5\u770b\u7ed3\u679c\u3002'
    case 'completed':
      return '\u5f53\u524d\u5de5\u4f5c\u6d41\u5df2\u7ecf\u5b8c\u6210\u5168\u90e8\u6d41\u7a0b\u3002'
    case 'failed':
      return '\u8bf7\u68c0\u67e5\u6b65\u9aa4\u8bb0\u5f55\u6216\u9519\u8bef\u4fe1\u606f\uff0c\u5fc5\u8981\u65f6\u91cd\u65b0\u89e6\u53d1\u5904\u7406\u3002'
    default:
      return WORKFLOW_PROGRESS_TEXT.waitServerAccept
  }
}

export function getWorkflowStatusLabel(status?: string) {
  if (!status) return '-'
  return WORKFLOW_STATUS_LABELS[status as WorkflowStatus] || status
}

export function getUploadStatusLabel(status?: string) {
  if (!status) return '-'
  return UPLOAD_STATUS_LABELS[status] || status
}

export function sortWorkflowsByLatest<T extends Pick<WorkflowListItem, 'createdAt' | 'updatedAt'>>(list: T[]) {
  return [...list].sort((left, right) => {
    const leftTime = new Date(left.updatedAt || left.createdAt || 0).getTime()
    const rightTime = new Date(right.updatedAt || right.createdAt || 0).getTime()
    return rightTime - leftTime
  })
}

export function computeUploadDialogPercent(uploadStep: number, uploadProgress: number) {
  switch (uploadStep) {
    case 0:
      return 0
    case 1:
      return 25
    case 2:
      return 45
    case 3:
      return 65
    case 4:
      return 65 + Math.round(Math.max(0, Math.min(100, uploadProgress)) * 0.35)
    case 5:
      return 100
    default:
      return 0
  }
}

export function getUploadDialogStageLabel(uploadStep: number) {
  const labelMap: Record<number, string> = {
    0: '\u51c6\u5907\u4e0a\u4f20',
    1: '\u6b63\u5728\u8fdb\u884c\u6d17\u724c\u52a0\u5bc6',
    2: '\u6b63\u5728\u751f\u6210\u4e0a\u4f20\u4ee4\u724c',
    3: '\u6b63\u5728\u8fdb\u884c\u6d17\u724c\u52a0\u5bc6',
    4: '\u6b63\u5728\u4e0a\u4f20\u6a21\u578b',
    5: '\u4e0a\u4f20\u5b8c\u6210\uff0c\u7b49\u5f85\u670d\u52a1\u7aef\u63a5\u6536'
  }
  return labelMap[uploadStep] || '\u5904\u7406\u4e2d'
}

export function getUploadDialogStageDescription(uploadStep: number) {
  const descMap: Record<number, string> = {
    0: '\u8bf7\u9009\u62e9\u672c\u5730\u6a21\u578b\u6587\u4ef6\uff0c\u7cfb\u7edf\u4f1a\u5148\u5b8c\u6210\u672c\u5730\u6d17\u724c\u52a0\u5bc6\u518d\u4e0a\u4f20\u3002',
    1: '\u6b63\u5728\u8ba1\u7b97\u6587\u4ef6\u6458\u8981\u5e76\u51c6\u5907\u6d17\u724c\u52a0\u5bc6\u3002',
    2: '\u6b63\u5728\u5411\u670d\u52a1\u7aef\u7533\u8bf7\u4e0a\u4f20\u4ee4\u724c\u4e0e\u4f1a\u8bdd\u5bc6\u94a5\u3002',
    3: '\u6b63\u5728\u4f7f\u7528\u5f53\u524d\u94fe\u8def\u7684\u73b0\u6709\u52a0\u5bc6\u80fd\u529b\u6267\u884c\u9636\u6bb5\u5316\u6d17\u724c\u52a0\u5bc6\u3002',
    4: '\u52a0\u5bc6\u540e\u7684\u6a21\u578b\u6587\u4ef6\u6b63\u5728\u4e0a\u4f20\u5230\u670d\u52a1\u7aef\u3002',
    5: '\u6d17\u724c\u52a0\u5bc6\u548c\u4e0a\u4f20\u5df2\u5b8c\u6210\uff0c\u6b63\u7b49\u5f85\u670d\u52a1\u7aef\u63a5\u6536\u4e0e\u53cd\u6d17\u724c\u89e3\u5bc6\u3002'
  }
  return descMap[uploadStep] || WORKFLOW_PROGRESS_TEXT.phaseProgressHint
}
