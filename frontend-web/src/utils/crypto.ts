export type WorkflowClientCryptoMode = 'WEB_CRYPTO' | 'SERVER_COMPAT'

export interface WorkflowCryptoDiagnostics {
  origin: string
  isSecureContext: boolean
  hasCrypto: boolean
  hasSubtle: boolean
  preferredMode: WorkflowClientCryptoMode
}

export interface PreparedWorkflowUploadPayload {
  cryptoMode: WorkflowClientCryptoMode
  uploadBlob: Blob
  uploadFilename: string
  diagnostics: WorkflowCryptoDiagnostics
  noticeMessage?: string
}

function resolveCryptoApi() {
  return globalThis.crypto
}

function resolveSubtleCrypto() {
  return resolveCryptoApi()?.subtle
}

function ensureSubtleCryptoAvailable(action: string) {
  const subtle = resolveSubtleCrypto()
  if (!subtle) {
    throw new Error(`Current browser environment does not support ${action}.`)
  }
  return subtle
}

function base64ToUint8Array(base64: string): Uint8Array {
  const binaryString = atob(base64)
  const bytes = new Uint8Array(binaryString.length)
  for (let i = 0; i < binaryString.length; i += 1) {
    bytes[i] = binaryString.charCodeAt(i)
  }
  return bytes
}

function cloneAsArrayBuffer(view: Uint8Array): ArrayBuffer {
  const buffer = new ArrayBuffer(view.byteLength)
  new Uint8Array(buffer).set(view)
  return buffer
}

function buildDiagnostics(): WorkflowCryptoDiagnostics {
  const cryptoApi = resolveCryptoApi()
  const hasCrypto = Boolean(cryptoApi)
  const hasSubtle = Boolean(cryptoApi?.subtle)

  return {
    origin: globalThis.location?.origin || 'unknown',
    isSecureContext: Boolean(globalThis.isSecureContext),
    hasCrypto,
    hasSubtle,
    preferredMode: hasSubtle ? 'WEB_CRYPTO' : 'SERVER_COMPAT'
  }
}

export function getWorkflowCryptoDiagnostics(): WorkflowCryptoDiagnostics {
  return buildDiagnostics()
}

export async function sha256File(file: File): Promise<string> {
  const subtle = ensureSubtleCryptoAvailable('native SHA-256 digest')
  const buffer = await file.arrayBuffer()
  const hashBuffer = await subtle.digest('SHA-256', buffer)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map((item) => item.toString(16).padStart(2, '0')).join('')
}

export async function encryptFileAES(
  file: File,
  aesKeyBase64: string,
  aesIvBase64: string
): Promise<ArrayBuffer> {
  const subtle = ensureSubtleCryptoAvailable('native AES encryption')
  const keyBytes = base64ToUint8Array(aesKeyBase64)
  const ivBytes = base64ToUint8Array(aesIvBase64)
  const keyBuffer = cloneAsArrayBuffer(keyBytes)
  const ivBuffer = cloneAsArrayBuffer(ivBytes)
  const cryptoKey = await subtle.importKey(
    'raw',
    keyBuffer,
    { name: 'AES-CBC' },
    false,
    ['encrypt']
  )
  const fileBuffer = await file.arrayBuffer()
  return subtle.encrypt({ name: 'AES-CBC', iv: new Uint8Array(ivBuffer) }, cryptoKey, fileBuffer)
}

export async function prepareWorkflowUploadPayload(
  file: File,
  aesKeyBase64: string,
  aesIvBase64: string,
  preferredMode?: WorkflowClientCryptoMode
): Promise<PreparedWorkflowUploadPayload> {
  const diagnostics = buildDiagnostics()
  const initialMode = preferredMode || diagnostics.preferredMode

  console.info('[workflow-crypto] upload environment detected', {
    ...diagnostics,
    requestedMode: preferredMode || null
  })

  if (initialMode === 'WEB_CRYPTO') {
    try {
      const encryptedBuffer = await encryptFileAES(file, aesKeyBase64, aesIvBase64)
      return {
        cryptoMode: 'WEB_CRYPTO',
        uploadBlob: new Blob([encryptedBuffer], { type: 'application/octet-stream' }),
        uploadFilename: `${file.name}.enc`,
        diagnostics
      }
    } catch (error) {
      console.warn('[workflow-crypto] native webcrypto encryption failed, switching to server compatibility mode', {
        diagnostics,
        filename: file.name,
        error
      })
    }
  }

  return {
    cryptoMode: 'SERVER_COMPAT',
    uploadBlob: file,
    uploadFilename: file.name,
    diagnostics,
    noticeMessage: '当前浏览器环境不支持原生摘要/加密，已自动切换到兼容上传模式。'
  }
}
