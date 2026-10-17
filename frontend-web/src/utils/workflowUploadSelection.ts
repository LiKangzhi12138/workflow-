import { nextTick } from 'vue'
import type { ModelAssetItem } from '@/api/model'

type UploadableModelAsset = Pick<
  ModelAssetItem,
  'id' | 'recordMode' | 'sourceType' | 'status' | 'filePathValidated'
>

export function isUploadableClientModelAsset(asset: UploadableModelAsset): boolean {
  const recordMode = String(asset.recordMode || '').trim().toUpperCase()
  const sourceType = String(asset.sourceType || '').trim().toUpperCase()
  if (recordMode === 'PATH_REGISTRY' && sourceType === 'CLIENT_PATH_REGISTRY') return true

  return String(asset.status || '').trim().toUpperCase() === 'READY' &&
    Number(asset.filePathValidated) === 1
}

function normalizeAssetId(value: unknown): number | null {
  if (typeof value !== 'number' && typeof value !== 'string') return null
  if (typeof value === 'string' && !/^\d+$/.test(value.trim())) return null
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

export function resolveUploadableModelAsset(
  assets: UploadableModelAsset[],
  requestedId: unknown
): UploadableModelAsset | null {
  const id = normalizeAssetId(requestedId)
  if (id == null) return null
  return assets.find((asset) => normalizeAssetId(asset.id) === id && isUploadableClientModelAsset(asset)) || null
}

export function resolveUploadModelAssetId(
  protocol: string,
  selectedId: number | null,
  boundId: number | null | undefined
): number | null {
  if (protocol === 'WEIGHTS_V1') return selectedId
  return selectedId ?? boundId ?? null
}

export function stageUploadFiles(
  uploadFiles: Array<{ raw?: File }>,
  limit: number,
  clearQueue: () => void
): File[] {
  const files = uploadFiles
    .map((item) => item.raw)
    .filter((item): item is File => Boolean(item))
    .slice(0, limit)

  // Element Plus queues onChange for every selected file; let each callback read the same queue first.
  void nextTick(clearQueue)
  return files
}
