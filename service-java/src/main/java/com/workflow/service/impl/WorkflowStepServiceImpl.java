package com.workflow.service.impl;

import com.workflow.entity.WorkflowStep;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.WorkflowStepMapper;
import com.workflow.service.WorkflowStepService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowStepServiceImpl implements WorkflowStepService {

    private static final int STEP_LOCK_BUCKET_SIZE = 64;

    private final WorkflowStepMapper workflowStepMapper;
    private final Object[] stepLocks = createStepLocks();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowStep appendWorkflowStep(Long workflowId,
                                           String stepCode,
                                           String stepName,
                                           String fromStatus,
                                           String toStatus,
                                           Long operatorUserId,
                                           String operatorRole,
                                           String message) {
        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId 不能为空");
        }
        if (!StringUtils.hasText(stepCode)) {
            throw new IllegalArgumentException("stepCode 不能为空");
        }
        if (!StringUtils.hasText(stepName)) {
            throw new IllegalArgumentException("stepName 不能为空");
        }
        if (!StringUtils.hasText(toStatus)) {
            throw new IllegalArgumentException("toStatus 不能为空");
        }

        Object lock = stepLocks[Math.floorMod(workflowId.hashCode(), stepLocks.length)];
        synchronized (lock) {
            Integer maxStepNo = workflowStepMapper.selectMaxStepNoByWorkflowId(workflowId);
            int nextStepNo = (maxStepNo == null ? 0 : maxStepNo) + 1;

            WorkflowStep step = new WorkflowStep();
            step.setWorkflowId(workflowId);
            step.setStepNo(nextStepNo);
            step.setStepCode(stepCode);
            step.setStepName(stepName);
            step.setFromStatus(fromStatus);
            step.setToStatus(toStatus);
            step.setOperatorUserId(operatorUserId);
            step.setOperatorRole(operatorRole);
            step.setMessage(message);

            try {
                workflowStepMapper.insert(step);
                return step;
            } catch (RuntimeException e) {
                log.error(
                        "Failed to append workflow step: workflowId={}, stepCode={}, stepName={}, fromStatus={}, toStatus={}, stepNo={}",
                        workflowId,
                        stepCode,
                        stepName,
                        fromStatus,
                        toStatus,
                        nextStepNo,
                        e
                );
                throw new BusinessException("WORKFLOW_STEP_APPEND_FAILED", "工作流步骤记录失败，请稍后重试", e);
            }
        }
    }

    private Object[] createStepLocks() {
        Object[] locks = new Object[STEP_LOCK_BUCKET_SIZE];
        for (int i = 0; i < STEP_LOCK_BUCKET_SIZE; i++) {
            locks[i] = new Object();
        }
        return locks;
    }
}
