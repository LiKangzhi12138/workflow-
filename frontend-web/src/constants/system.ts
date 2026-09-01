export const SYSTEM_NAME_FULL = '基于联邦学习的农业保险信息安全防御与隐私保护原型系统'

export const SYSTEM_NAME_SHORT = '原型系统'

export function buildDocumentTitle(pageTitle?: string) {
  return pageTitle ? `${pageTitle} - ${SYSTEM_NAME_SHORT}` : SYSTEM_NAME_SHORT
}

export const ROLE_LABELS: Record<string, string> = {
  CLIENT: '客户端',
  SERVER: '服务器端'
}
