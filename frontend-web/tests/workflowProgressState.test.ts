import assert from 'node:assert/strict'
import test from 'node:test'
import { resolveValidPage } from '../src/utils/pagination.ts'
import {
  isCurrentRequest,
  resolveProgressWorkflowTarget,
  resolveUploadProtocolReadError,
  shouldRequestUploadProtocol
} from '../src/utils/workflowProgressState.ts'

test('upload protocol is requested only while an upload can be started or replaced', () => {
  assert.equal(shouldRequestUploadProtocol('CREATED', null), true)
  assert.equal(shouldRequestUploadProtocol('ACCEPTED', { requiredModelCount: 2, activeUploadCount: 1 }), true)
  assert.equal(shouldRequestUploadProtocol('ACCEPTED', { requiredModelCount: 2, activeUploadCount: 2 }), false)
  assert.equal(shouldRequestUploadProtocol('ACCEPTED', { requiredModelCount: 1, activeUploadCount: 1, replaceAllowed: true }), true)
  assert.equal(shouldRequestUploadProtocol('COMPLETED', null), false)
  assert.equal(shouldRequestUploadProtocol('VALIDATING', null), false)
  assert.equal(shouldRequestUploadProtocol('ACCEPTED', null, { requiredModelCount: 2, receivedModelCount: 2 }), false)
})

test('upload protocol errors distinguish permission, stale workflow state, and transient failures', () => {
  assert.match(resolveUploadProtocolReadError(403), /无权/)
  assert.match(resolveUploadProtocolReadError(undefined, undefined, 'Request failed with status code 403'), /无权/)
  assert.match(resolveUploadProtocolReadError(409), /状态已变化/)
  assert.match(resolveUploadProtocolReadError(undefined, 'WORKFLOW_STATUS_INVALID', '当前工作流不允许上传模型'), /状态已变化/)
  assert.match(resolveUploadProtocolReadError(), /刷新重试/)
})

test('route selection remains tied to the requested workflow even when it is not on the current page', () => {
  assert.equal(resolveProgressWorkflowTarget(41, 42, 43), 41)
  assert.equal(resolveProgressWorkflowTarget(null, 42, 43), 42)
  assert.equal(resolveProgressWorkflowTarget(null, null, 43), 43)
  assert.equal(isCurrentRequest(4, 5), false)
  assert.equal(isCurrentRequest(5, 5), true)
})

test('server pagination clamps last page after deletion and supports page-size changes', () => {
  assert.equal(resolveValidPage(1, 10, 0), 1)
  assert.equal(resolveValidPage(3, 10, 21), 3)
  assert.equal(resolveValidPage(3, 10, 20), 2)
  assert.equal(resolveValidPage(5, 50, 21), 1)
})
