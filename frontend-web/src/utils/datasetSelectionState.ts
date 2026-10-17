export function normalizeDatasetId(value: number | string | null | undefined): number | null {
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

export function reconcileDatasetSelection(
  draftId: number | null,
  persistedId: number | string | null | undefined,
  draftDirty: boolean
): number | null {
  return draftDirty ? draftId : normalizeDatasetId(persistedId)
}

export function isCurrentWorkflowResponse(
  requestSequence: number,
  latestSequence: number,
  targetWorkflowId: number,
  activeWorkflowId: number | null
): boolean {
  return requestSequence === latestSequence && targetWorkflowId === activeWorkflowId
}
