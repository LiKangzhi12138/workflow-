package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.entity.StandaloneValidation;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.mapper.StandaloneValidationMapper;
import com.workflow.mapper.SysUserMapper;
import com.workflow.mapper.WorkflowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResultArtifactCleanupService {

    public static final String WORKFLOW_RESULT_TEMPORARY = "TEMPORARY";
    public static final String WORKFLOW_RESULT_SAVED = "SAVED";
    public static final String WORKFLOW_RESULT_DELETED = "DELETED";

    private static final String MODE_STANDALONE = "STANDALONE";
    private static final String MODE_WORKFLOW = "WORKFLOW";

    private final StandaloneValidationMapper standaloneValidationMapper;
    private final WorkflowMapper workflowMapper;
    private final SysUserMapper sysUserMapper;
    private final PythonIntegrationProperties pythonIntegrationProperties;
    private final WorkflowStorageProperties workflowStorageProperties;
    private final ValidationImageCacheService validationImageCacheService;
    private final ObjectMapper objectMapper;

    public void cleanupPreviousStandaloneResultsForUser(SysUser actor, Long keepValidationId, String reason) {
        if (actor == null || actor.getId() == null) {
            return;
        }
        List<StandaloneValidation> previousValidations = standaloneValidationMapper.selectList(
                new LambdaQueryWrapper<StandaloneValidation>()
                        .eq(StandaloneValidation::getUserId, actor.getId())
                        .eq(StandaloneValidation::getIsDeleted, 0)
                        .ne(keepValidationId != null, StandaloneValidation::getId, keepValidationId)
                        .orderByDesc(StandaloneValidation::getCreatedAt)
                        .orderByDesc(StandaloneValidation::getId)
        );

        for (StandaloneValidation validation : previousValidations) {
            cleanupStandaloneArtifacts(validation, actor, reason);
            validation.setResultFilePath(null);
            validation.setMetricsJson(null);
            validation.setIsDeleted(1);
            validation.setUpdatedAt(LocalDateTime.now());
            standaloneValidationMapper.updateById(validation);
            log.info(
                    "Standalone validation record retired after artifact cleanup: actorUserId={}, actorRole={}, validationId={}, validationCode={}, reason={}",
                    actor.getId(),
                    actor.getRoleCode(),
                    validation.getId(),
                    validation.getValidationCode(),
                    reason
            );
        }
    }

    public void cleanupStandaloneArtifacts(StandaloneValidation validation, SysUser actor, String reason) {
        if (validation == null) {
            return;
        }
        cleanupResultFile(validation.getResultFilePath(), validation.getPythonJobId(), MODE_STANDALONE, validation.getId(), actor, reason);
        cleanupPythonSampleDirectory(validation.getPythonJobId(), MODE_STANDALONE, validation.getId(), actor, reason);
        if (actor != null && StringUtils.hasText(actor.getRoleCode()) && actor.getId() != null) {
            validationImageCacheService.clearValidationImageCache(actor.getRoleCode(), actor.getId());
        } else {
            SysUser owner = validation.getUserId() == null ? null : sysUserMapper.selectById(validation.getUserId());
            if (owner != null) {
                validationImageCacheService.clearValidationImageCache(owner.getRoleCode(), owner.getId());
            }
        }
    }

    public void cleanupReplaceableWorkflowResultsBeforeStart(Workflow currentWorkflow, SysUser actor, String reason) {
        if (currentWorkflow == null || currentWorkflow.getId() == null || actor == null) {
            return;
        }

        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getIsDeleted, 0)
                .eq(Workflow::getResultRetentionStatus, WORKFLOW_RESULT_TEMPORARY)
                .ne(Workflow::getId, currentWorkflow.getId())
                .orderByDesc(Workflow::getUpdatedAt)
                .orderByDesc(Workflow::getId);

        if ("SERVER".equalsIgnoreCase(actor.getRoleCode())) {
            wrapper.eq(Workflow::getServerUserId, actor.getId());
        } else if ("CLIENT".equalsIgnoreCase(actor.getRoleCode())) {
            wrapper.eq(Workflow::getInitiatorUserId, actor.getId());
        } else {
            return;
        }

        List<Workflow> replaceableWorkflows = workflowMapper.selectList(wrapper);
        for (Workflow workflow : replaceableWorkflows) {
            cleanupWorkflowArtifacts(workflow, actor, reason);
            workflow.setResultFilePath(null);
            workflow.setMetricsJson(null);
            workflow.setResultRetentionStatus(WORKFLOW_RESULT_DELETED);
            workflow.setResultDeletedAt(LocalDateTime.now());
            workflow.setUpdatedAt(LocalDateTime.now());
            workflowMapper.updateById(workflow);
            log.info(
                    "Workflow temporary result replaced: actorUserId={}, actorRole={}, workflowId={}, workflowCode={}, reason={}",
                    actor.getId(),
                    actor.getRoleCode(),
                    workflow.getId(),
                    workflow.getWorkflowCode(),
                    reason
            );
        }
    }

    public void cleanupWorkflowArtifacts(Workflow workflow, SysUser actor, String reason) {
        if (workflow == null) {
            return;
        }
        cleanupResultFile(workflow.getResultFilePath(), workflow.getPythonJobId(), MODE_WORKFLOW, workflow.getId(), actor, reason);
        cleanupPythonSampleDirectory(workflow.getPythonJobId(), MODE_WORKFLOW, workflow.getId(), actor, reason);
        clearWorkflowCacheIfOwned("SERVER", workflow.getServerUserId(), workflow.getId(), actor, reason);
        clearWorkflowCacheIfOwned("CLIENT", workflow.getInitiatorUserId(), workflow.getId(), actor, reason);
    }

    private void cleanupResultFile(String rawResultFilePath,
                                   String pythonJobId,
                                   String mode,
                                   Long ownerId,
                                   SysUser actor,
                                   String reason) {
        Path resultPath = resolveResultFilePath(rawResultFilePath, pythonJobId);
        if (resultPath == null) {
            return;
        }
        safeDelete(resultPath, allowedResultRoots(), mode, ownerId, actor, reason);
    }

    private void cleanupPythonSampleDirectory(String pythonJobId,
                                              String mode,
                                              Long ownerId,
                                              SysUser actor,
                                              String reason) {
        if (!StringUtils.hasText(pythonJobId)) {
            return;
        }
        Path sampleRoot = normalizePath(pythonIntegrationProperties.getPythonSampleRootPath());
        if (sampleRoot == null) {
            return;
        }
        Path sampleDir = sampleRoot.resolve(pythonJobId).toAbsolutePath().normalize();
        safeDelete(sampleDir, List.of(sampleRoot), mode, ownerId, actor, reason);
    }

    private void clearWorkflowCacheIfOwned(String roleCode,
                                           Long userId,
                                           Long workflowId,
                                           SysUser actor,
                                           String reason) {
        if (!StringUtils.hasText(roleCode) || userId == null || workflowId == null) {
            return;
        }
        ValidationImageCacheService.CachedValidationImageManifest manifest =
                validationImageCacheService.getCachedManifest(roleCode, userId, MODE_WORKFLOW, workflowId);
        if (manifest == null) {
            return;
        }
        int deletedCount = validationImageCacheService.clearValidationImageCache(roleCode, userId);
        log.info(
                "Workflow validation cache cleaned: actorUserId={}, actorRole={}, cacheRole={}, cacheUserId={}, workflowId={}, deletedImageCount={}, reason={}",
                actor == null ? null : actor.getId(),
                actor == null ? null : actor.getRoleCode(),
                roleCode,
                userId,
                workflowId,
                deletedCount,
                reason
        );
    }

    private void safeDelete(Path target,
                            List<Path> allowedRoots,
                            String mode,
                            Long ownerId,
                            SysUser actor,
                            String reason) {
        if (target == null || allowedRoots == null || allowedRoots.isEmpty()) {
            return;
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        List<Path> normalizedRoots = allowedRoots.stream()
                .filter(Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toList());

        boolean allowed = normalizedRoots.stream()
                .anyMatch(root -> normalizedTarget.startsWith(root) && !normalizedTarget.equals(root));
        if (!allowed) {
            log.warn(
                    "Result artifact cleanup skipped because path is outside controlled roots: actorUserId={}, actorRole={}, mode={}, ownerId={}, target={}, allowedRoots={}, reason={}",
                    actor == null ? null : actor.getId(),
                    actor == null ? null : actor.getRoleCode(),
                    mode,
                    ownerId,
                    normalizedTarget,
                    normalizedRoots,
                    reason
            );
            return;
        }
        if (!Files.exists(normalizedTarget)) {
            log.info(
                    "Result artifact cleanup target already missing: actorUserId={}, actorRole={}, mode={}, ownerId={}, target={}, reason={}",
                    actor == null ? null : actor.getId(),
                    actor == null ? null : actor.getRoleCode(),
                    mode,
                    ownerId,
                    normalizedTarget,
                    reason
            );
            return;
        }

        try {
            List<Path> deletedPaths = new ArrayList<>();
            if (Files.isDirectory(normalizedTarget)) {
                try (Stream<Path> stream = Files.walk(normalizedTarget)) {
                    List<Path> paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
                    for (Path path : paths) {
                        Files.deleteIfExists(path);
                        deletedPaths.add(path);
                    }
                }
            } else {
                Files.deleteIfExists(normalizedTarget);
                deletedPaths.add(normalizedTarget);
            }
            log.info(
                    "Result artifact cleanup completed: actorUserId={}, actorRole={}, mode={}, ownerId={}, target={}, deletedCount={}, deletedPaths={}, reason={}",
                    actor == null ? null : actor.getId(),
                    actor == null ? null : actor.getRoleCode(),
                    mode,
                    ownerId,
                    normalizedTarget,
                    deletedPaths.size(),
                    deletedPaths,
                    reason
            );
        } catch (IOException ex) {
            log.warn(
                    "Result artifact cleanup failed: actorUserId={}, actorRole={}, mode={}, ownerId={}, target={}, reason={}",
                    actor == null ? null : actor.getId(),
                    actor == null ? null : actor.getRoleCode(),
                    mode,
                    ownerId,
                    normalizedTarget,
                    reason,
                    ex
            );
        }
    }

    private Path resolveResultFilePath(String rawResultFilePath, String pythonJobId) {
        String candidate = rawResultFilePath == null ? null : rawResultFilePath.trim();
        if (StringUtils.hasText(candidate) && candidate.startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(candidate);
                candidate = node.path("filePath").asText(null);
            } catch (Exception ex) {
                candidate = null;
            }
        }
        if (StringUtils.hasText(candidate)) {
            return Paths.get(candidate).toAbsolutePath().normalize();
        }
        if (!StringUtils.hasText(pythonJobId)) {
            return null;
        }
        Path resultRoot = normalizePath(pythonIntegrationProperties.getPythonResultRootPath());
        return resultRoot == null ? null : resultRoot.resolve(pythonJobId + "_result.json").toAbsolutePath().normalize();
    }

    private List<Path> allowedResultRoots() {
        List<Path> roots = new ArrayList<>();
        Path pythonResultRoot = normalizePath(pythonIntegrationProperties.getPythonResultRootPath());
        if (pythonResultRoot != null) {
            roots.add(pythonResultRoot);
        }
        Path workflowResultRoot = workflowStorageProperties.rootDirPath().resolve("results").toAbsolutePath().normalize();
        roots.add(workflowResultRoot);
        return roots;
    }

    private Path normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return null;
        }
    }
}
